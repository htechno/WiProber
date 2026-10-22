package com.example.wiprober

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.os.SystemClock
import android.view.KeyEvent
import android.view.View
import androidx.lifecycle.ViewModelProvider
import androidx.test.core.app.ActivityScenario
import androidx.test.espresso.Espresso.onView
import androidx.test.espresso.action.ViewActions.click
import androidx.test.espresso.matcher.ViewMatchers.withId
import androidx.test.espresso.matcher.RootMatchers.isDialog
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Opt-in real system UI check, including OEM permission/settings screens.
 * Run in the isolated .r8validation package with both location permissions revoked BEFORE
 * instrumentation starts (revoking in-process kills the test). Pass -e permissionScenario
 * approximate-stop, approximate-continuous, or direct. In the system dialog choose approximate
 * (or precise for direct); when app settings subsequently opens, enable precise location and
 * return. The test itself checks cancellation/retry, recreation, mode ownership and one-shot resume.
 */
@RunWith(AndroidJUnit4::class)
class ScanPermissionRecoveryInstrumentedTest {
    @Test
    fun realPermissionRoundTripResumesExactlyTheRequestedScan() {
        val scenarioName = InstrumentationRegistry.getArguments().getString("permissionScenario")
        assumeTrue("Opt-in permission UI check", scenarioName in setOf("approximate-stop", "approximate-continuous", "direct"))
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        assertEquals(PackageManager.PERMISSION_DENIED, context.checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION))
        assertEquals(PackageManager.PERMISSION_DENIED, context.checkSelfPermission(Manifest.permission.ACCESS_COARSE_LOCATION))
        context.getSharedPreferences("WiProberPrefs", Context.MODE_PRIVATE).edit()
            .putBoolean("has_shown_english_warning", true).commit()
        val repository = ProjectRepository(context)
        val image = ByteArrayOutputStream().apply {
            val bitmap = Bitmap.createBitmap(100, 100, Bitmap.Config.ARGB_8888)
            bitmap.eraseColor(android.graphics.Color.WHITE)
            bitmap.compress(Bitmap.CompressFormat.PNG, 100, this)
            bitmap.recycle()
        }.toByteArray()
        val project = repository.createNewProject(ByteArrayInputStream(image), "permission-test.png", "Permission review", "Test floor")
        val continuous = scenarioName == "approximate-continuous"
        try {
            ActivityScenario.launch<SurveyActivity>(Intent(context, SurveyActivity::class.java)
                .putExtra(SurveyActivity.EXTRA_PROJECT_ID, project.id)).use { activityScenario ->
                var lastInteraction: SurveyInteraction? = null
                fun state(): SurveyScreenState {
                    var result: SurveyScreenState? = null
                    activityScenario.onActivity { result = ViewModelProvider(it)[MainViewModel::class.java].screenState.value }
                    return requireNotNull(result).also {
                        if (it.interaction != lastInteraction) {
                            android.util.Log.i("WiProberReviewTest", "Interaction: ${it.interaction}")
                            lastInteraction = it.interaction
                        }
                    }
                }
                waitUntil("Map ready") {
                    var ready = false
                    activityScenario.onActivity {
                        ready = it.findViewById<View>(R.id.projectOperationOverlay).visibility != View.VISIBLE &&
                            ViewModelProvider(it)[MainViewModel::class.java].screenState.value.hasMap
                    }
                    ready
                }
                if (continuous) {
                    onView(withId(R.id.continuousModeButton)).perform(click())
                    onView(withId(R.id.continuousActionButton)).perform(click())
                }
                onView(withId(R.id.mapImageView)).perform(click())
                if (scenarioName != "direct") {
                    waitUntil("Choose approximate location in the system dialog", 120_000) {
                        (state().interaction as? SurveyInteraction.AwaitingScanAccess)?.step == ScanAccessStep.PERMISSION_EXPLANATION
                    }
                    val pending = state().interaction as SurveyInteraction.AwaitingScanAccess
                    assertEquals(continuous, pending.request is PendingScanRequest.ContinuousStart)
                    assertTrue(state().scanPoints.isEmpty())
                    activityScenario.recreate()
                    waitUntil("Explanation restored after recreation") {
                        state().interaction == pending
                    }
                    SystemClock.sleep(500)
                    // Return without changing the permission: explain again, never start a scan.
                    onView(withId(android.R.id.button1)).inRoot(isDialog()).perform(click())
                    assertEquals(ScanAccessStep.APP_SETTINGS,
                        (state().interaction as? SurveyInteraction.AwaitingScanAccess)?.step)
                    waitUntil("App settings visible", 30_000) {
                        instrumentation.uiAutomation.rootInActiveWindow?.packageName == "com.android.settings"
                    }
                    SystemClock.sleep(500)
                    instrumentation.sendKeyDownUpSync(KeyEvent.KEYCODE_BACK)
                    waitUntil("Unchanged approximate grant must not start scanning") {
                        state().interaction == pending
                    }
                    SystemClock.sleep(500) // Wait for the Settings exit animation before tapping the dialog.
                    assertTrue(state().scanPoints.isEmpty())
                    assertFalse(state().isTracking)
                    // Cancel and retry from the map: do not rely on Android re-showing a prompt.
                    onView(withId(android.R.id.button2)).inRoot(isDialog()).perform(click())
                    waitUntil("Cancel releases the pending scan") { state().interaction == SurveyInteraction.Idle }
                    if (continuous) onView(withId(R.id.continuousActionButton)).perform(click())
                    onView(withId(R.id.mapImageView)).perform(click())
                    waitUntil("Approximate-only retry shows settings explanation") {
                        (state().interaction as? SurveyInteraction.AwaitingScanAccess)?.step == ScanAccessStep.PERMISSION_EXPLANATION
                    }
                    SystemClock.sleep(500)
                    onView(withId(android.R.id.button1)).inRoot(isDialog()).perform(click())
                    waitUntil("Retry opens app settings") {
                        (state().interaction as? SurveyInteraction.AwaitingScanAccess)?.step == ScanAccessStep.APP_SETTINGS
                    }
                }
                waitUntil("Enable precise location and return to WiProber", 120_000) {
                    if (continuous) state().isTracking else state().scanPoints.size == 1
                }
                assertEquals(PackageManager.PERMISSION_GRANTED, context.checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION))
                if (continuous) {
                    assertTrue(state().scanPoints.isEmpty())
                    waitUntil("Continuous scan result", 30_000) { state().activeContinuousScanCount > 0 }
                    onView(withId(R.id.continuousActionButton)).perform(click())
                    assertEquals(1, state().continuousScanSessions.size)
                    assertTrue(state().continuousScanSessions.single().scanResults.isNotEmpty())
                } else {
                    assertEquals(1, state().scanPoints.size)
                    assertTrue(state().continuousScanSessions.isEmpty())
                }
                val snapshot = state()
                activityScenario.recreate()
                SystemClock.sleep(1_000)
                assertEquals(snapshot.scanPoints, state().scanPoints)
                assertEquals(snapshot.continuousScanSessions, state().continuousScanSessions)
                assertEquals(SurveyInteraction.Idle, state().interaction)
            }
        } finally {
            repository.deleteProject(project.id)
        }
    }

    private fun waitUntil(message: String, timeout: Long = 10_000, predicate: () -> Boolean) {
        val deadline = SystemClock.uptimeMillis() + timeout
        while (SystemClock.uptimeMillis() < deadline) {
            if (predicate()) return
            SystemClock.sleep(100)
        }
        fail(message)
    }
}
