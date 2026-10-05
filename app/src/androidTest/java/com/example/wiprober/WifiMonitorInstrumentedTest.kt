package com.example.wiprober

import android.Manifest
import android.content.Context
import android.net.wifi.WifiManager
import android.os.SystemClock
import android.widget.TextView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.ViewModelProvider
import androidx.test.core.app.ActivityScenario
import androidx.test.espresso.Espresso.onView
import androidx.test.espresso.action.ViewActions.click
import androidx.test.espresso.assertion.ViewAssertions.matches
import androidx.test.espresso.matcher.ViewMatchers.isDisplayed
import androidx.test.espresso.matcher.ViewMatchers.withId
import androidx.test.espresso.matcher.ViewMatchers.withText
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class WifiMonitorInstrumentedTest {
    private val instrumentation get() = InstrumentationRegistry.getInstrumentation()
    private val context get() = instrumentation.targetContext

    @Test fun hubOpensScannerAndThrottleBlocksAutomaticRequests() {
        val wifi = context.getSystemService(WifiManager::class.java)
        assumeTrue("This test requires the device's scan throttling to be enabled", wifi.isScanThrottleEnabled)
        ActivityScenario.launch(ProjectHubActivity::class.java).use {
            onView(withId(R.id.wifiMonitorButton)).perform(click())
            onView(withText(R.string.monitor_throttling_required)).check(matches(isDisplayed()))
            onView(withText(R.string.action_cancel)).perform(click())
            onView(withId(R.id.status)).check(matches(withText(R.string.monitor_throttled)))
            onView(withId(R.id.lastScan)).check(matches(withText(R.string.monitor_no_scan)))
            instrumentation.sendKeyDownUpSync(android.view.KeyEvent.KEYCODE_BACK)
        }
    }

    @Test fun filtersPauseAndExpandedChannelsSurviveRecreationAndBackground() {
        ActivityScenario.launch(WifiMonitorActivity::class.java).use { scenario ->
            val filter = WifiMonitorFilter("office", WifiBand.GHZ_6, 5, -70)
            scenario.onActivity { activity ->
                ViewModelProvider(activity)[WifiMonitorViewModel::class.java].apply {
                    toggleRunning(); setFilter(filter); toggleChannels()
                }
            }
            scenario.recreate()
            scenario.onActivity { activity ->
                val state = ViewModelProvider(activity)[WifiMonitorViewModel::class.java].state.value
                assertFalse(state.running)
                assertEquals(filter, state.filter)
                assertTrue(state.channelsExpanded)
            }
            scenario.moveToState(Lifecycle.State.CREATED)
            scenario.moveToState(Lifecycle.State.RESUMED)
            scenario.onActivity { activity ->
                assertFalse(ViewModelProvider(activity)[WifiMonitorViewModel::class.java].state.value.running)
            }
        }
    }

    @Test fun realScanSnapshotsAndIeFormattingWorkOnDevice() = runBlocking {
        instrumentation.uiAutomation.grantRuntimePermission(context.packageName, Manifest.permission.ACCESS_FINE_LOCATION)
        val source = WifiMonitorSource(context)
        assumeTrue("Wi-Fi and Location Services must be enabled", source.access() == WifiScanAccessRequirement.READY)
        ActivityScenario.launch(ProjectHubActivity::class.java).use {
            val scanner = WifiScanner(context)
            scanner.start()
            try {
                val outcome = withTimeout(20_000) { scanner.scan() }
                assertTrue("Expected fresh real scan results, got $outcome", outcome is WifiScanOutcome.Success)
                val results = (outcome as WifiScanOutcome.Success).results.mapNotNull(WifiMonitorSource::snapshot)
                assertTrue("At least one nearby AP is needed for this test", results.isNotEmpty())
                results.forEach { network ->
                    val detail = WifiMonitorDialogs.detailText(context, network, SystemClock.elapsedRealtime())
                    assertTrue(detail.contains(network.bssid))
                    assertTrue(network.informationElements.all { ie -> ie.bytes.all { value -> value in 0..255 } })
                }
                assertNotNull("Primary connection should be visible on this connected test phone", source.connection())
            } finally { scanner.close() }
        }
    }

    @Test fun automaticScanRefreshesAndPausesWhenThrottleIsDisabled() {
        val source = WifiMonitorSource(context)
        assumeTrue("Disable Wi-Fi scan throttling manually to exercise continuous refresh", !source.throttled())
        instrumentation.uiAutomation.grantRuntimePermission(context.packageName, Manifest.permission.ACCESS_FINE_LOCATION)
        assumeTrue(source.access() == WifiScanAccessRequirement.READY)
        ActivityScenario.launch(WifiMonitorActivity::class.java).use { scenario ->
            val deadline = SystemClock.elapsedRealtime() + 25_000
            var scanned = false
            while (!scanned && SystemClock.elapsedRealtime() < deadline) {
                scenario.onActivity { activity ->
                    scanned = ViewModelProvider(activity)[WifiMonitorViewModel::class.java].state.value.lastScanMillis != null
                }
                if (!scanned) SystemClock.sleep(250)
            }
            assertTrue("Automatic mode must receive fresh results", scanned)
            var lastScan: Long? = null
            scenario.onActivity { activity ->
                activity.findViewById<android.view.View>(R.id.pauseButton).performClick()
                lastScan = ViewModelProvider(activity)[WifiMonitorViewModel::class.java].state.value.lastScanMillis
            }
            SystemClock.sleep(5_000)
            scenario.onActivity { activity ->
                assertEquals(lastScan, ViewModelProvider(activity)[WifiMonitorViewModel::class.java].state.value.lastScanMillis)
                assertEquals(activity.getString(R.string.monitor_paused), activity.findViewById<TextView>(R.id.status).text)
            }
        }
    }
}
