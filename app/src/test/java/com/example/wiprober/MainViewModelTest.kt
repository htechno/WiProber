package com.example.wiprober

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MainViewModelTest {
    @Test
    fun pendingStopAndGoAndContinuousRequestsCannotReplaceEachOther() {
        val viewModel = viewModel()

        assertTrue(viewModel.requestStopAndGoScan(10f, 20f))
        assertFalse(viewModel.setScanMode(SurveyScanMode.CONTINUOUS))
        assertFalse(viewModel.requestContinuousStart(30f, 40f))
        assertEquals(
            PendingScanRequest.StopAndGo(SurveyCoordinate(10f, 20f)),
            viewModel.pendingScanRequest()
        )

        viewModel.cancelPendingScanRequest()
        assertTrue(viewModel.setScanMode(SurveyScanMode.CONTINUOUS))
        assertTrue(viewModel.armContinuousStart())
        assertTrue(viewModel.requestContinuousStart(30f, 40f))
        assertEquals(
            PendingScanRequest.ContinuousStart(SurveyCoordinate(30f, 40f)),
            viewModel.pendingScanRequest()
        )
    }

    @Test
    fun continuousSessionUsesInjectedClockAndIdSource() {
        val clock = FakeClock(1_000L)
        val ids = SequenceIdSource("session-id")
        val viewModel = viewModel(clock, ids)
        viewModel.setScanMode(SurveyScanMode.CONTINUOUS)
        viewModel.armContinuousStart()
        viewModel.requestContinuousStart(1f, 2f)

        assertEquals(StartedScanAction.Continuous, viewModel.grantPendingScanAccess())
        clock.now = 1_250L
        viewModel.addContinuousWaypoint(3f, 4f)
        val attempt = viewModel.beginContinuousScanAttempt()!!
        clock.now = 1_400L
        viewModel.completeContinuousScanAttempt(attempt, listOf(network()))
        clock.now = 1_500L
        val session = viewModel.stopContinuousTrack(3f, 4f)!!

        assertEquals("session-id", session.id)
        assertEquals(1_000L, session.startTime)
        assertEquals(1_500L, session.endTime)
        assertEquals(listOf(0L, 250L, 500L), session.waypoints.map { it.timestamp })
        assertEquals(400L, session.scanResults.single().timestamp)
        assertEquals(150L, session.scanResults.single().duration)
        assertTrue(viewModel.screenState.value.interaction is SurveyInteraction.Idle)
    }

    @Test
    fun noteIdsAndStopAndGoTimestampAreDeterministic() {
        val clock = FakeClock(9_000L)
        val ids = SequenceIdSource("note-id", "photo-id", "picture-id")
        val viewModel = viewModel(clock, ids)

        viewModel.requestStopAndGoScan(5f, 6f)
        assertEquals(
            StartedScanAction.StopAndGo(SurveyCoordinate(5f, 6f)),
            viewModel.grantPendingScanAccess()
        )
        val point = viewModel.completeStopAndGoScan(listOf(network()))!!
        assertEquals(9_000L, point.timestamp)

        val note = viewModel.addNote(
            text = "Photo",
            photoUri = "content://photo",
            photoWidth = 10,
            photoHeight = 20,
            x = 7f,
            y = 8f,
            photoFormat = "JPEG"
        )!!
        assertEquals("note-id", note.id)
        assertEquals("photo-id", note.photoId)
        assertEquals("picture-id", note.pictureNoteId)
        assertEquals(2, viewModel.screenState.value.actionHistory.size)
    }

    @Test
    fun stopAndGoThrottleWindowExpiresDeterministically() {
        val clock = FakeClock(0L)
        val viewModel = viewModel(clock)

        repeat(4) { index ->
            clock.now = index * 1_000L
            assertTrue(viewModel.requestStopAndGoScan(index.toFloat(), index.toFloat()))
            assertTrue(viewModel.grantPendingScanAccess() is StartedScanAction.StopAndGo)
            viewModel.completeStopAndGoScan(listOf(network()))
        }

        assertEquals(117_000L, viewModel.stopAndGoThrottleDelayMillis(true))
        assertEquals(0L, viewModel.stopAndGoThrottleDelayMillis(false))
        clock.now = 120_000L
        assertEquals(0L, viewModel.stopAndGoThrottleDelayMillis(true))
    }

    @Test
    fun projectOperationIsExclusiveAndRecoverableAfterRecreation() {
        val viewModel = viewModel()

        assertTrue(viewModel.beginProjectOperation(ProjectOperation.FLOOR_SWITCH))
        assertFalse(viewModel.beginNotePlacement())
        assertFalse(viewModel.requestStopAndGoScan(1f, 2f))
        assertEquals(ProjectOperation.FLOOR_SWITCH, viewModel.recoverAfterActivityRecreation())
        assertTrue(viewModel.screenState.value.interaction is SurveyInteraction.Idle)

        assertTrue(viewModel.beginProjectOperation(ProjectOperation.EXPORT_DESTINATION))
        assertEquals(null, viewModel.recoverAfterActivityRecreation())
        assertEquals(
            SurveyInteraction.ProjectBusy(ProjectOperation.EXPORT_DESTINATION),
            viewModel.screenState.value.interaction
        )
    }

    private fun viewModel(
        clock: FakeClock = FakeClock(1L),
        ids: IdSource = SequenceIdSource("id")
    ) = MainViewModel(clock, ids).apply {
        loadProject(
            mapInfo = MapInfo("map.png", 100, 200),
            scale = null,
            scanPoints = emptyList(),
            continuousSessions = emptyList(),
            notes = emptyList()
        )
    }

    private fun network() = WifiNetworkInfo(
        ssid = "test",
        bssid = "00:11:22:33:44:55",
        level = -50,
        frequency = 5_180,
        security = "WPA2",
        technologies = listOf("AC"),
        informationElements = ""
    )

    private class FakeClock(var now: Long) : MillisClock {
        override fun now(): Long = now
    }

    private class SequenceIdSource(vararg ids: String) : IdSource {
        private val values = ArrayDeque(ids.toList())
        override fun newId(): String = values.removeFirst()
    }
}
