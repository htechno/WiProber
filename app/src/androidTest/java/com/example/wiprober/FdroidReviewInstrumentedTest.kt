package com.example.wiprober

import android.app.LocaleManager
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.os.Build
import android.os.LocaleList
import android.os.SystemClock
import android.view.KeyEvent
import android.view.View
import androidx.lifecycle.ViewModelProvider
import androidx.test.core.app.ActivityScenario
import androidx.test.espresso.Espresso.onView
import androidx.test.espresso.action.ViewActions.click
import androidx.test.espresso.assertion.ViewAssertions.matches
import androidx.test.espresso.matcher.ViewMatchers.*
import androidx.test.espresso.matcher.RootMatchers.isDialog
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import org.hamcrest.CoreMatchers.containsString
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class FdroidReviewInstrumentedTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context = instrumentation.targetContext

    @Test
    fun privacyButtonAndDialogFollowEnglishAndRussianAppLocales() {
        assumeTrue(Build.VERSION.SDK_INT >= 33)
        val manager = context.getSystemService(LocaleManager::class.java)
        val previous = manager.applicationLocales
        suppressWelcome()
        try {
            listOf("en" to "Privacy policy", "ru" to "Политика конфиденциальности").forEach { (tag, title) ->
                manager.applicationLocales = LocaleList.forLanguageTags(tag)
                instrumentation.waitForIdleSync()
                ActivityScenario.launch(ProjectHubActivity::class.java).use {
                    onView(withId(R.id.privacyPolicyButton)).check(matches(withText(title))).perform(click())
                    onView(withId(android.R.id.message)).check(matches(withText(containsString(
                        if (tag == "ru") "Дата редакции: 12 октября 2026 года." else "Revision date: 12 October 2026."
                    ))))
                    onView(withId(android.R.id.message)).check(matches(withText(containsString("wifi.solution.msk@gmail.com"))))
                    onView(withId(android.R.id.button1)).perform(click())
                }
            }
        } finally {
            manager.applicationLocales = previous
        }
    }

    @Test
    fun exportWarnsForInactiveFloorAndCancelAllowsCalibrationWithoutRememberingConsent() {
        suppressWelcome()
        val repository = ProjectRepository(context)
        val project = repository.createNewProject(ByteArrayInputStream(pngBytes()), "map.png", "Scale review", "Needs calibration")
        try {
            val active = repository.addFloor(project.id, ByteArrayInputStream(pngBytes()), "map2.png", "Confirmed floor").activeFloor
            repository.saveProject(project.id, active.id, ProjectSnapshot(active.mapInfo, 0.025, emptyList(), emptyList(), emptyList()))
            val reopened = ProjectRepository(context).snapshotProject(project.id)
            assertEquals(listOf("Needs calibration"), FloorScalePolicy.uncalibratedFloors(reopened).map { it.name })
            assertEquals(0.025, reopened.floors.last().survey.metersPerUnit!!, 0.0)
            ActivityScenario.launch<SurveyActivity>(Intent(context, SurveyActivity::class.java)
                .putExtra(SurveyActivity.EXTRA_PROJECT_ID, project.id)).use { scenario ->
                waitForReady(scenario)
                showWarning()
                onView(withId(android.R.id.message)).check(matches(withText(containsString("Needs calibration"))))
                onView(withId(android.R.id.button2)).inRoot(isDialog()).perform(click())
                scenario.onActivity {
                    assertEquals(SurveyInteraction.Idle, ViewModelProvider(it)[MainViewModel::class.java].screenState.value.interaction)
                }
                onView(withId(R.id.scaleButton)).perform(click())
                scenario.onActivity {
                    val vm = ViewModelProvider(it)[MainViewModel::class.java]
                    assertTrue(vm.screenState.value.interaction is SurveyInteraction.Calibration)
                    vm.clearCalibration()
                }
                showWarning()
                // Explicit continuation opens the document picker, not a silent export.
                onView(withId(android.R.id.button1)).inRoot(isDialog()).perform(click())
                scenario.onActivity {
                    assertEquals(SurveyInteraction.ProjectBusy(ProjectOperation.EXPORT_DESTINATION),
                        ViewModelProvider(it)[MainViewModel::class.java].screenState.value.interaction)
                }
                val pickerDeadline = SystemClock.uptimeMillis() + 10_000
                while (instrumentation.uiAutomation.rootInActiveWindow?.packageName?.toString()
                    ?.contains("documentsui") != true && SystemClock.uptimeMillis() < pickerDeadline
                ) SystemClock.sleep(100)
                assertTrue("System file picker must be visible", instrumentation.uiAutomation.rootInActiveWindow
                    ?.packageName?.toString()?.contains("documentsui") == true)
                SystemClock.sleep(500)
                instrumentation.sendKeyDownUpSync(KeyEvent.KEYCODE_BACK)
                waitForReady(scenario)
                instrumentation.waitForIdleSync()
                SystemClock.sleep(500) // Let the system document-picker exit animation finish.
                showWarning()
                scenario.recreate()
                waitForReady(scenario)
                scenario.onActivity {
                    assertEquals(SurveyInteraction.Idle, ViewModelProvider(it)[MainViewModel::class.java].screenState.value.interaction)
                }
            }
            val after = ProjectRepository(context).snapshotProject(project.id)
            assertEquals(2, after.floors.size)
            assertNull(after.floors.first().survey.metersPerUnit)
            assertEquals(0.025, after.floors.last().survey.metersPerUnit!!, 0.0)
        } finally {
            repository.deleteProject(project.id)
        }
    }

    private fun showWarning() {
        onView(withId(R.id.action_export_esx)).check(matches(isEnabled())).perform(click())
        // Export preflight intentionally performs disk IO off the main thread.
        val deadline = SystemClock.uptimeMillis() + 10_000
        var found = false
        while (!found && SystemClock.uptimeMillis() < deadline) {
            found = runCatching { onView(withId(android.R.id.button2)).inRoot(isDialog()).check(matches(isDisplayed())) }.isSuccess
            if (!found) SystemClock.sleep(100)
        }
        assertTrue("Calibration warning did not appear", found)
    }

    private fun waitForReady(scenario: ActivityScenario<SurveyActivity>) {
        val deadline = SystemClock.uptimeMillis() + 10_000
        var ready = false
        while (!ready && SystemClock.uptimeMillis() < deadline) {
            scenario.onActivity {
                ready = it.findViewById<View>(R.id.projectOperationOverlay).visibility != View.VISIBLE &&
                    ViewModelProvider(it)[MainViewModel::class.java].screenState.value.interaction == SurveyInteraction.Idle
            }
            if (!ready) SystemClock.sleep(100)
        }
        assertTrue("Survey did not become idle", ready)
    }

    private fun suppressWelcome() {
        context.getSharedPreferences("WiProberPrefs", Context.MODE_PRIVATE).edit()
            .putBoolean("has_shown_english_warning", true).commit()
    }

    private fun pngBytes(): ByteArray = ByteArrayOutputStream().use { output ->
        val bitmap = Bitmap.createBitmap(100, 100, Bitmap.Config.ARGB_8888)
        bitmap.eraseColor(android.graphics.Color.WHITE)
        check(bitmap.compress(Bitmap.CompressFormat.PNG, 100, output))
        bitmap.recycle()
        output.toByteArray()
    }
}
