package com.example.wiprober

import android.Manifest
import android.content.Context
import android.location.LocationManager
import android.net.wifi.WifiManager
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class WifiScannerInstrumentedTest {

    @Test
    fun lifecycleStopCancelsTheOwnedScanExactlyOnce() {
        val context = preparedContext()
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        lateinit var outcome: WifiScanOutcome

        instrumentation.runOnMainSync {
            runBlocking {
                val scanner = WifiScanner(context)
                scanner.start()
                try {
                    val pending = async(start = CoroutineStart.UNDISPATCHED) {
                        scanner.scan(WifiScanRequest(WifiScanKind.CONTINUOUS))
                    }
                    assertFalse(pending.isCompleted)

                    scanner.stop(WifiScanCancellationReason.LIFECYCLE_STOPPED)
                    outcome = pending.await()

                    scanner.start()
                    val next = async(start = CoroutineStart.UNDISPATCHED) {
                        scanner.scan(WifiScanRequest(WifiScanKind.STOP_AND_GO))
                    }
                    assertFalse(next.isCompleted)
                    next.cancelAndJoin()
                } finally {
                    scanner.close()
                }
            }
        }

        assertTrue(
            outcome is WifiScanOutcome.Cancelled &&
                (outcome as WifiScanOutcome.Cancelled).reason ==
                WifiScanCancellationReason.LIFECYCLE_STOPPED
        )
    }

    @Test
    fun realDeviceScanReturnsOneTerminalOutcome() = runBlocking {
        val context = preparedContext()
        ActivityScenario.launch(ProjectHubActivity::class.java).use {
            val scanner = WifiScanner(context)
            scanner.start()
            try {
                val outcome = withTimeout(REAL_SCAN_TEST_TIMEOUT_MILLIS) {
                    scanner.scan(WifiScanRequest(WifiScanKind.STOP_AND_GO))
                }
                assertTrue(
                    "Expected an owned terminal result, got $outcome",
                    outcome is WifiScanOutcome.Success || outcome is WifiScanOutcome.Failure
                )
                assertNotNull(outcome.operationId)
                if (outcome is WifiScanOutcome.Failure) {
                    assertTrue(
                        outcome.reason != WifiScanFailureReason.SCANNER_NOT_STARTED &&
                            outcome.reason != WifiScanFailureReason.SCANNER_CLOSED &&
                            outcome.reason != WifiScanFailureReason.ALREADY_IN_PROGRESS
                    )
                }
            } finally {
                scanner.close()
            }
        }
    }

    private fun preparedContext(): Context {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        instrumentation.uiAutomation.grantRuntimePermission(
            context.packageName,
            Manifest.permission.ACCESS_FINE_LOCATION
        )
        val wifiManager = context.applicationContext
            .getSystemService(Context.WIFI_SERVICE) as WifiManager
        val locationManager = context.getSystemService(Context.LOCATION_SERVICE) as LocationManager
        assumeTrue("Wi-Fi must be enabled for this device test", wifiManager.isWifiEnabled)
        assumeTrue("Location Services must be enabled for this device test", locationManager.isLocationEnabled)
        return context
    }

    private companion object {
        const val REAL_SCAN_TEST_TIMEOUT_MILLIS = 20_000L
    }
}
