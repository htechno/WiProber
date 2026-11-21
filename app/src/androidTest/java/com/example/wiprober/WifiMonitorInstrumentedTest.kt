package com.example.wiprober

import android.Manifest
import android.content.Context
import android.net.wifi.WifiManager
import android.os.SystemClock
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.ViewModelProvider
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import androidx.test.core.app.ActivityScenario
import androidx.test.espresso.Espresso.onView
import androidx.test.espresso.action.ViewActions.click
import androidx.test.espresso.assertion.ViewAssertions.doesNotExist
import androidx.test.espresso.assertion.ViewAssertions.matches
import androidx.test.espresso.matcher.ViewMatchers.isDisplayed
import androidx.test.espresso.matcher.ViewMatchers.isChecked
import androidx.test.espresso.matcher.ViewMatchers.withId
import androidx.test.espresso.matcher.ViewMatchers.withText
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.example.wiprober.databinding.ItemWifiNetworkBinding
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

    @Test fun offlineRegistryResolvesAssignmentsAndKeepsUnassignedAddressesRaw() {
        val vendors = MacVendorRegistry.read(context.resources.openRawResource(R.raw.mac_vendors))
        assertEquals(MacVendor("Ruckus", "Ruckus Wireless"), vendors.lookup("1C:3A:60:09:24:4C"))
        assertEquals("Qivivo", vendors.lookup("1C:87:76:DF:AB:CD")?.fullName)
        assertEquals("Converging Systems Inc.", vendors.lookup("00:1B:C5:00:0F:FF")?.fullName)
        assertEquals("T.L.S. Corp.", vendors.lookup("00:50:C2:00:0F:FF")?.fullName)
        for (bssid in listOf("02:00:0C:09:24:4C", "01:00:0C:09:24:4C", "00:01:01:11:22:33", "10:20:30:40:50:60")) {
            assertNull(bssid, vendors.lookup(bssid))
        }
    }

    @Test fun compactSecurityLabelsDistinguishEveryModeAndOrderMixedNetworks() {
        val text = WifiMonitorText(context)
        val labels = mapOf(
            WifiSecurity.OPEN to "Open", WifiSecurity.WEP to "WEP",
            WifiSecurity.WPA_PERSONAL to "1P", WifiSecurity.WPA_ENTERPRISE to "1E",
            WifiSecurity.WPA2_PERSONAL to "2P", WifiSecurity.WPA2_ENTERPRISE to "2E",
            WifiSecurity.WPA3_PERSONAL to "3P", WifiSecurity.WPA3_ENTERPRISE to "3E",
            WifiSecurity.OWE to "OWE", WifiSecurity.OWE_TRANSITION to "OWE-T", WifiSecurity.UNKNOWN to "?"
        )
        assertEquals(WifiSecurity.entries.toSet(), labels.keys)
        labels.forEach { (mode, expected) -> assertEquals(expected, text.security(setOf(mode), compact = true)) }
        assertEquals("2E/3E", text.security(setOf(WifiSecurity.WPA3_ENTERPRISE, WifiSecurity.WPA2_ENTERPRISE), compact = true))
    }

    @Test fun connectedRowFollowsBssidWithinSharedSsidAndClearsOnDisconnect() {
        val first = WifiNetworkSnapshot("10:20:30:40:50:60", "Office", -50, 5180, 0)
        val second = first.copy(bssid = "10:20:30:40:50:61")
        val adapter = WifiNetworkAdapter {}
        fun checkRow(network: WifiNetworkSnapshot, connected: Boolean) {
            onView(withText(network.bssid)).check { view, error ->
                if (error != null) throw error
                val row = view.parent as ViewGroup
                assertEquals(connected, row.isActivated)
                assertEquals(if (connected) context.getString(R.string.monitor_current_connection) else null, row.stateDescription)
                for (i in 0 until row.childCount) {
                    val cell = row.getChildAt(i) as TextView
                    assertEquals(cell.text.toString(), connected || cell.id == R.id.rssi, cell.typeface.isBold)
                }
            }
        }
        ActivityScenario.launch(ProjectHubActivity::class.java).use { scenario ->
            scenario.onActivity { activity ->
                activity.setContentView(RecyclerView(activity).also { rows ->
                    rows.layoutManager = LinearLayoutManager(activity)
                    rows.itemAnimator = null
                    rows.adapter = adapter
                })
                adapter.setConnectedBssid(first.bssid)
                adapter.submitList(wifiTableRows(listOf(first, second), split = true))
            }
            checkRow(first, true)
            checkRow(second, false)
            scenario.onActivity { adapter.setConnectedBssid(second.bssid) }
            checkRow(first, false)
            checkRow(second, true)
            scenario.onActivity { adapter.setConnectedBssid(null) }
            checkRow(second, false)
        }
    }

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
            val filter = WifiMonitorFilter("office", setOf(WifiBand.GHZ_5, WifiBand.GHZ_6), 5, -70)
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

    @Test fun splitAndMultiBandFilterHideEmptySectionsAndReset() {
        val source = WifiMonitorSource(context)
        assumeTrue("Configure scan access first", !source.throttled() && source.access() == WifiScanAccessRequirement.READY)
        val office = WifiNetworkSnapshot("10:20:30:40:50:60", "Office", -55, 5180, 0, capabilities = "[RSN-PSK+SAE-CCMP][ESS]")
        val guest = office.copy(bssid = "10:20:30:40:50:61", ssid = "Guest", frequencyMhz = 2412, capabilities = "[ESS]")
        fun heading(band: Int, count: Int) = onView(withText(context.getString(
            R.string.monitor_band_section, context.getString(band), count)))
        fun columnWidths(showBand: Boolean): Pair<Int, Int> {
            var widths = 0 to 0
            onView(withText(office.bssid)).check { view, error ->
                if (error != null) throw error
                val row = ItemWifiNetworkBinding.bind(view.parent as View)
                val header = ItemWifiNetworkBinding.bind(view.rootView.findViewById(R.id.tableHeader))
                val visibility = if (showBand) View.VISIBLE else View.GONE
                assertEquals(visibility, header.band.visibility)
                assertEquals(visibility, row.band.visibility)
                assertEquals(header.ssid.width, row.ssid.width)
                widths = row.ssid.width to if (showBand) row.band.width else 0
            }
            return widths
        }
        ActivityScenario.launch(WifiMonitorActivity::class.java).use { scenario ->
            scenario.onActivity { activity ->
                activity.findViewById<android.view.View>(R.id.pauseButton).performClick()
                ViewModelProvider(activity)[WifiMonitorViewModel::class.java].scanFinished(
                    listOf(office, guest), WifiMonitorStatus.UPDATED, 1_000, 2_000)
            }
            val (ssidWidth, bandWidth) = columnWidths(showBand = true)
            assertTrue(bandWidth > 0)
            onView(withId(R.id.splitBands)).perform(click())
            assertEquals((ssidWidth + bandWidth) to 0, columnWidths(showBand = false))
            heading(R.string.monitor_band_6, 0).check(doesNotExist())
            onView(withText("Office")).check(matches(isDisplayed()))
            onView(withText("2P/3P")).check(matches(isDisplayed()))
            heading(R.string.monitor_band_5, 1).check(matches(isDisplayed()))
            heading(R.string.monitor_band_24, 1).check(matches(isDisplayed()))
            onView(withId(R.id.filterButton)).perform(click())
            onView(withText(R.string.monitor_band_24)).perform(click())
            onView(withText(R.string.monitor_apply)).perform(click())
            heading(R.string.monitor_band_24, 1).check(doesNotExist())
            onView(withText(guest.bssid)).check(doesNotExist())
            onView(withText(office.bssid)).perform(click())
            onView(withId(R.id.detailsText)).check(matches(androidx.test.espresso.matcher.ViewMatchers.withSubstring(
                "WPA2 Personal / WPA3 Personal")))
            instrumentation.sendKeyDownUpSync(android.view.KeyEvent.KEYCODE_BACK)
            scenario.recreate()
            onView(withId(R.id.splitBands)).check(matches(isChecked()))
            heading(R.string.monitor_band_5, 1).check(matches(isDisplayed()))
            heading(R.string.monitor_band_6, 0).check(doesNotExist())
            onView(withId(R.id.filterButton)).perform(click())
            onView(withText(R.string.monitor_reset)).perform(click())
            onView(withText(guest.bssid)).check(matches(isDisplayed()))
            onView(withId(R.id.splitBands)).perform(click())
            assertEquals(ssidWidth to bandWidth, columnWidths(showBand = true))
            heading(R.string.monitor_band_6, 0).check(doesNotExist())
            onView(withText(office.bssid)).check(matches(isDisplayed()))
            val many = (0..31).map { i -> office.copy(
                bssid = "10:20:30:40:50:" + i.toString(16).padStart(2, '0'), rssi = -40 - i) }
            scenario.onActivity { activity ->
                ViewModelProvider(activity)[WifiMonitorViewModel::class.java].scanFinished(
                    many, WifiMonitorStatus.UPDATED, 3_000, 4_000)
            }
            onView(withText(many.first().bssid)).check(matches(isDisplayed()))
            scenario.onActivity { it.findViewById<RecyclerView>(R.id.networks).scrollToPosition(many.lastIndex) }
            instrumentation.waitForIdleSync()
            scenario.onActivity { assertTrue(it.findViewById<RecyclerView>(R.id.networks).canScrollVertically(-1)) }
            onView(withId(R.id.splitBands)).perform(click())
            heading(R.string.monitor_band_5, many.size).check(matches(isDisplayed()))
            scenario.onActivity { activity ->
                ViewModelProvider(activity)[WifiMonitorViewModel::class.java].scanFinished(
                    emptyList(), WifiMonitorStatus.UPDATED, 5_000, 6_000)
            }
            onView(withId(R.id.empty)).check(matches(isDisplayed()))
            onView(withId(R.id.tableScroll)).check(matches(androidx.test.espresso.matcher.ViewMatchers.withEffectiveVisibility(
                androidx.test.espresso.matcher.ViewMatchers.Visibility.GONE)))
        }
    }

    @Test fun logPanesReplaceTableAndShareHandoverWithChartAcrossRecreation() {
        val source = WifiMonitorSource(context)
        assumeTrue("Configure scan access first", !source.throttled() && source.access() == WifiScanAccessRequirement.READY)
        val a = WifiConnectionSnapshot("Office", "10:20:30:40:50:60", -75, 5180, 5, 144, 144, 144)
        val b = a.copy(bssid = "10:20:30:40:50:61", frequencyMhz = 5500, rssi = -51)
        ActivityScenario.launch(WifiMonitorActivity::class.java).use { scenario ->
            scenario.onActivity { activity ->
                val vm = ViewModelProvider(activity)[WifiMonitorViewModel::class.java]
                val t = SystemClock.elapsedRealtime()
                vm.endObservation(t)
                vm.tick(t, a, WifiScanAccessRequirement.READY, false)
                vm.observeProbe(ProbeSample(t + 10, "192.0.2.1", ProbeResult.REPLY))
                vm.observeRadio(t + 20, MonitorRadioReading(a, "COMPLETED"))
                vm.observeProbe(ProbeSample(t + 260, "192.0.2.1", ProbeResult.TIMEOUT))
                vm.tick(t + 300, b, WifiScanAccessRequirement.READY, false)
                vm.observeProbe(ProbeSample(t + 510, "192.0.2.1", ProbeResult.REPLY))
                vm.observeProbe(ProbeSample(t + 760, "192.0.2.1", ProbeResult.REPLY))
                vm.scanFinished(listOf(WifiNetworkSnapshot(a.bssid, a.ssid, a.rssi, a.frequencyMhz, 0)),
                    WifiMonitorStatus.UPDATED, t, t + 760)
                activity.findViewById<android.view.View>(R.id.pauseButton).performClick()
            }
            onView(withText(R.string.monitor_pane_events)).perform(click())
            onView(withId(R.id.logRows)).check(matches(isDisplayed()))
            onView(withId(R.id.bssidPane)).check(matches(androidx.test.espresso.matcher.ViewMatchers.withEffectiveVisibility(
                androidx.test.espresso.matcher.ViewMatchers.Visibility.GONE)))
            onView(withText(R.string.monitor_pane_handovers)).perform(click())
            val gapText = context.getString(R.string.monitor_gap_measured, 500L, 1)
            onView(androidx.test.espresso.matcher.ViewMatchers.withSubstring(gapText)).check(matches(isDisplayed()))
            scenario.recreate()
            onView(androidx.test.espresso.matcher.ViewMatchers.withSubstring(gapText)).check(matches(isDisplayed()))
            scenario.onActivity { activity ->
                val chart = activity.findViewById<WifiSignalChartView>(R.id.signalChart)
                assertTrue(chart.contentDescription.contains(activity.getString(R.string.monitor_signal_markers, 1)))
            }
            onView(withText(R.string.monitor_pane_bssids)).perform(click())
            onView(withId(R.id.bssidPane)).check(matches(isDisplayed()))
            onView(withText(a.bssid)).check(matches(isDisplayed()))
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
                val results = (outcome as WifiScanOutcome.Success).results.mapNotNull(source::snapshot)
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
        val network = WifiNetworkSnapshot("1C:3A:60:09:24:4C", "Office", -50, 5180, 0,
            vendor = MacVendor("Ruckus", "Ruckus Wireless"))
        val filter = WifiMonitorFilter("Office")
        ActivityScenario.launch(WifiMonitorActivity::class.java).use { scenario ->
            scenario.onActivity { activity ->
                activity.findViewById<android.view.View>(R.id.pauseButton).performClick()
                ViewModelProvider(activity)[WifiMonitorViewModel::class.java].apply {
                    setFilter(filter)
                    scanFinished(listOf(network), WifiMonitorStatus.UPDATED, 1_000, 2_000)
                }
            }
            onView(withText("Ruckus:09:24:4C")).perform(click())
            onView(withId(R.id.detailsContent)).check(matches(isDisplayed()))
            onView(withId(R.id.detailsText)).check(matches(androidx.test.espresso.matcher.ViewMatchers.withSubstring(network.bssid)))
            onView(withId(R.id.detailsText)).check(matches(androidx.test.espresso.matcher.ViewMatchers.withSubstring("Ruckus Wireless")))
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
            onView(withText("Ruckus:09:24:4C")).check(matches(isDisplayed()))
            scenario.onActivity { activity ->
                val state = ViewModelProvider(activity)[WifiMonitorViewModel::class.java].state.value
                assertNull(state.selectedNetwork)
                assertEquals(filter, state.filter)
                assertFalse(state.running)
            }
        }
    }
}
