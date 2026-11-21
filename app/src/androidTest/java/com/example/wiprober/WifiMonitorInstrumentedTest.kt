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
                    val detail = WifiNetworkDetailsText.format(context, network, SystemClock.elapsedRealtime())
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

    @Test fun decodedDetailsHaveNamedSuitesSeparateFieldsAndNoByteDumps() {
        val ie = WifiInformationElement(48, 0,
            "01 00 00 0f ac 04 01 00 00 0f ac 04 01 00 00 0f ac 08 c0 00".split(" ").map { it.toInt(16) })
        val network = WifiNetworkSnapshot("10:20:30:40:50:60", "Office", -50, 5180, 0,
            ssidBytes = listOf(65, 66), informationElements = listOf(ie,
                WifiInformationElement(221, 0, listOf(0, 80, 242, 2)),
                WifiInformationElement(254, 0, listOf(222, 173, 190, 239))))
        val detail = WifiNetworkDetailsText.format(context, network, 1_000)
        assertTrue(detail.contains("CCMP-128"))
        assertTrue(detail.contains("SAE"))
        assertTrue(detail.contains("PMF capable: Yes\nPMF required: Yes"))
        listOf("Hex:", "SSID bytes", "00:0F:AC", "00:50:F2", "DE AD BE EF").forEach {
            assertFalse("Raw payload leaked into details: $it", detail.contains(it))
        }
        val text = WifiMonitorText(context)
        assertEquals("SSID: true", text.fact(WifiIeFact(WifiIeFactKind.SSID, listOf("true"))))
        val unknown = text.fact(WifiIeFact(WifiIeFactKind.RSN,
            listOf("1", "AA:BB:CC:04", "00:0F:AC:FF", "AA:BB:CC:08", "false", "false")))
        assertFalse(unknown.contains("CCMP"))
        assertFalse(unknown.contains("SAE"))
        assertFalse(unknown.contains("AA:BB"))
    }

    @Test fun fullScreenDetailsSurviveRecreationAndBackPreservesListState() {
        instrumentation.uiAutomation.grantRuntimePermission(context.packageName, Manifest.permission.ACCESS_FINE_LOCATION)
        val source = WifiMonitorSource(context)
        assumeTrue("Scanner access must already be configured", !source.throttled() && source.access() == WifiScanAccessRequirement.READY)
        val network = WifiNetworkSnapshot("10:20:30:40:50:60", "Office", -50, 5180, 0)
        val filter = WifiMonitorFilter("Office")
        ActivityScenario.launch(WifiMonitorActivity::class.java).use { scenario ->
            scenario.onActivity { activity ->
                ViewModelProvider(activity)[WifiMonitorViewModel::class.java].apply {
                    toggleRunning()
                    setFilter(filter)
                    scanFinished(listOf(network), WifiMonitorStatus.UPDATED, 1_000, 2_000)
                }
            }
            onView(withText(network.bssid)).perform(click())
            onView(withId(R.id.detailsContent)).check(matches(isDisplayed()))
            scenario.recreate()
            scenario.onActivity { activity ->
                val state = ViewModelProvider(activity)[WifiMonitorViewModel::class.java].state.value
                assertEquals(network, state.selectedNetwork?.network)
                assertFalse(state.running)
                val root = activity.findViewById<android.view.View>(R.id.monitorRoot)
                val details = activity.findViewById<android.view.View>(R.id.detailsContent)
                assertEquals(root.height - root.paddingTop - root.paddingBottom, details.height)
            }
            instrumentation.sendKeyDownUpSync(android.view.KeyEvent.KEYCODE_BACK)
            onView(withId(R.id.scannerContent)).check(matches(isDisplayed()))
            onView(withText(network.bssid)).check(matches(isDisplayed()))
            scenario.onActivity { activity ->
                val state = ViewModelProvider(activity)[WifiMonitorViewModel::class.java].state.value
                assertNull(state.selectedNetwork)
                assertEquals(filter, state.filter)
                assertFalse(state.running)
            }
        }
    }
}
