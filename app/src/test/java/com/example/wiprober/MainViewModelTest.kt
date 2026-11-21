package com.example.wiprober

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MainViewModelTest {
    @Test
    fun permissionAndSettingsRoundTripsRetainBothModesAndConsumeOnlyOnce() {
        SurveyScanMode.entries.forEach { mode ->
            val vm = viewModel()
            vm.setScanMode(mode)
            if (mode == SurveyScanMode.CONTINUOUS) {
                vm.armContinuousStart()
                vm.requestContinuousStart(10f, 20f)
            } else vm.requestStopAndGoScan(10f, 20f)
            val request = vm.pendingScanRequest()
            assertTrue(vm.transitionScanAccess(ScanAccessStep.CHECKING, ScanAccessStep.PERMISSION_REQUEST))
            assertEquals(null, vm.grantPendingScanAccess())
            assertFalse(vm.transitionScanAccess(ScanAccessStep.CHECKING, ScanAccessStep.PERMISSION_REQUEST))
            assertTrue(vm.transitionScanAccess(ScanAccessStep.PERMISSION_REQUEST, ScanAccessStep.CHECKING))
            assertTrue(vm.transitionScanAccess(ScanAccessStep.CHECKING, ScanAccessStep.PERMISSION_EXPLANATION))
            repeat(2) {
                assertTrue(vm.transitionScanAccess(ScanAccessStep.PERMISSION_EXPLANATION, ScanAccessStep.APP_SETTINGS))
                assertEquals(null, vm.recoverAfterActivityRecreation())
                assertEquals(request, vm.pendingScanRequest())
                assertEquals(null, vm.grantPendingScanAccess())
                assertTrue(vm.transitionScanAccess(ScanAccessStep.APP_SETTINGS, ScanAccessStep.CHECKING))
                if (it == 0) vm.transitionScanAccess(ScanAccessStep.CHECKING, ScanAccessStep.PERMISSION_EXPLANATION)
            }
            val expected = if (mode == SurveyScanMode.CONTINUOUS) StartedScanAction.Continuous
                else StartedScanAction.StopAndGo(SurveyCoordinate(10f, 20f))
            assertEquals(expected, vm.grantPendingScanAccess())
            assertEquals(null, vm.grantPendingScanAccess())
            assertFalse(vm.transitionScanAccess(ScanAccessStep.APP_SETTINGS, ScanAccessStep.CHECKING))
        }
    }

    @Test
    fun cancelledPermissionRequestCannotBeResumedByLateSettingsResult() {
        val vm = viewModel()
        vm.requestStopAndGoScan(1f, 2f)
        vm.transitionScanAccess(ScanAccessStep.CHECKING, ScanAccessStep.PERMISSION_EXPLANATION)
        vm.cancelPendingScanRequest()
        assertFalse(vm.transitionScanAccess(ScanAccessStep.APP_SETTINGS, ScanAccessStep.CHECKING))
        assertEquals(null, vm.grantPendingScanAccess())
        assertEquals(SurveyInteraction.Idle, vm.screenState.value.interaction)
    }

    @Test
    fun calibrationRejectsNonFiniteValuesAndAcceptsRealScaleEqualToFallback() {
        val vm = viewModel()
        vm.enterCalibrationMode()
        vm.addCalibrationPoint(SurveyCoordinate(0f, 0f))
        vm.addCalibrationPoint(SurveyCoordinate(100f, 0f))
        listOf(Double.NaN, Double.POSITIVE_INFINITY, 0.0, -1.0).forEach {
            assertFalse(vm.applyCalibration(it))
            assertEquals(null, vm.screenState.value.metersPerUnit)
        }
        assertTrue(vm.applyCalibration(2.5))
        assertEquals(0.025, vm.screenState.value.metersPerUnit!!, 0.0)
    }

    @Test
    fun exportPreflightBlocksSurveyAndRecreationCancelsUnconfirmedExport() {
        val vm = viewModel()
        assertTrue(vm.beginProjectOperation(ProjectOperation.EXPORT_PREFLIGHT))
        assertFalse(vm.requestStopAndGoScan(1f, 2f))
        assertFalse(vm.beginProjectOperation(ProjectOperation.EXPORT))
        assertEquals(ProjectOperation.EXPORT_PREFLIGHT, vm.recoverAfterActivityRecreation())
        assertTrue(vm.beginProjectOperation(ProjectOperation.EXPORT_PREFLIGHT))
        assertTrue(vm.beginProjectOperation(ProjectOperation.EXPORT_DESTINATION))
        assertEquals(null, vm.recoverAfterActivityRecreation())
    }

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
