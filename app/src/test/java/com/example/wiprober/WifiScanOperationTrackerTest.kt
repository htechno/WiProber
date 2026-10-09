package com.example.wiprober

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class WifiScanOperationTrackerTest {

    @Test
    fun onlyOneOperationCanOwnPlatformScan() {
        val tracker = WifiScanOperationTracker()

        val first = tracker.begin(baselineNewestResultMicros = 1_000L)!!

        assertNull(tracker.begin(baselineNewestResultMicros = 2_000L))
        assertEquals(first, tracker.activeOperationId())
    }

    @Test
    fun staleSuccessfulBroadcastIsIgnoredAndFreshResultIsAccepted() {
        val tracker = WifiScanOperationTracker()
        val operationId = tracker.begin(baselineNewestResultMicros = 1_000L)!!
        assertTrue(tracker.markSystemScanRequested(operationId, requestedAtMicros = 2_000L))

        assertEquals(
            WifiScanBroadcastDecision.Ignored,
            tracker.acceptSuccessfulBroadcast(
                newestResultMicros = 1_000L,
                receivedAtMicros = 2_500L
            )
        )
        assertEquals(
            WifiScanBroadcastDecision.Ignored,
            tracker.acceptSuccessfulBroadcast(
                newestResultMicros = 1_999L,
                receivedAtMicros = 2_500L
            )
        )
        assertEquals(
            WifiScanBroadcastDecision.Accepted(operationId),
            tracker.acceptSuccessfulBroadcast(
                newestResultMicros = 2_100L,
                receivedAtMicros = 2_500L
            )
        )
    }

    @Test
    fun emptyResultMustArriveAfterMinimumScanAge() {
        val tracker = WifiScanOperationTracker(emptyResultMinimumAgeMicros = 750_000L)
        val operationId = tracker.begin(baselineNewestResultMicros = null)!!
        tracker.markSystemScanRequested(operationId, requestedAtMicros = 1_000_000L)

        assertEquals(
            WifiScanBroadcastDecision.Ignored,
            tracker.acceptSuccessfulBroadcast(
                newestResultMicros = null,
                receivedAtMicros = 1_749_999L
            )
        )
        assertEquals(
            WifiScanBroadcastDecision.Accepted(operationId),
            tracker.acceptSuccessfulBroadcast(
                newestResultMicros = null,
                receivedAtMicros = 1_750_000L
            )
        )
    }

    @Test
    fun oldTimeoutTokenCannotFinishNewOperation() {
        val tracker = WifiScanOperationTracker()
        val first = tracker.begin(null)!!
        assertTrue(tracker.finish(first))
        val second = tracker.begin(null)!!

        assertNotEquals(first, second)
        assertFalse(tracker.finish(first))
        assertEquals(second, tracker.activeOperationId())
    }
}

class WifiScanAccessPolicyTest {

    @Test
    fun accessRequirementsAreResolvedInStableOrder() {
        assertEquals(
            WifiScanAccessRequirement.ENABLE_WIFI,
            requirement(wifi = false, permissions = false, location = false)
        )
        assertEquals(
            WifiScanAccessRequirement.GRANT_PERMISSIONS,
            requirement(wifi = true, permissions = false, location = false)
        )
        assertEquals(
            WifiScanAccessRequirement.ENABLE_LOCATION,
            requirement(wifi = true, permissions = true, location = false)
        )
        assertEquals(
            WifiScanAccessRequirement.READY,
            requirement(wifi = true, permissions = true, location = true)
        )
    }

    private fun requirement(
        wifi: Boolean,
        permissions: Boolean,
        location: Boolean
    ): WifiScanAccessRequirement = WifiScanAccessPolicy.requirement(
        WifiScanAccessSnapshot(
            wifiEnabled = wifi,
            permissionsGranted = permissions,
            locationEnabled = location
        )
    )
}
