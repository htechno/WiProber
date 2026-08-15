package com.example.wiprober

data class SurveyCoordinate(val x: Float, val y: Float)

enum class SurveyScanMode { STOP_AND_GO, CONTINUOUS }

enum class ProjectOperation {
    INITIAL_LOAD,
    FLOOR_SWITCH,
    FLOOR_MAP_SELECTION,
    FLOOR_ADD,
    EXPORT_DESTINATION,
    EXPORT
}

sealed interface PendingScanRequest {
    val coordinate: SurveyCoordinate

    data class StopAndGo(override val coordinate: SurveyCoordinate) : PendingScanRequest
    data class ContinuousStart(override val coordinate: SurveyCoordinate) : PendingScanRequest
}

sealed interface SurveyInteraction {
    data object Idle : SurveyInteraction
    data object NotePlacement : SurveyInteraction
    data class Calibration(val points: List<SurveyCoordinate>) : SurveyInteraction
    data object AwaitingContinuousStart : SurveyInteraction
    data class AwaitingScanAccess(val request: PendingScanRequest) : SurveyInteraction
    data class StopAndGoScanning(val coordinate: SurveyCoordinate) : SurveyInteraction
    data class ContinuousTracking(
        val startedAt: Long,
        val waypoints: List<SurveyCoordinate>,
        val scanCount: Int
    ) : SurveyInteraction
    data class ProjectBusy(val operation: ProjectOperation) : SurveyInteraction
}

sealed interface StartedScanAction {
    data class StopAndGo(val coordinate: SurveyCoordinate) : StartedScanAction
    data object Continuous : StartedScanAction
}

data class SurveyScreenState(
    val mapInfo: MapInfo? = null,
    val metersPerUnit: Double? = null,
    val scanPoints: List<ScanPoint> = emptyList(),
    val continuousScanSessions: List<ContinuousScanSession> = emptyList(),
    val notes: List<AppNote> = emptyList(),
    val actionHistory: List<LastAction> = emptyList(),
    val scanMode: SurveyScanMode = SurveyScanMode.STOP_AND_GO,
    val interaction: SurveyInteraction = SurveyInteraction.Idle,
    val lastUndoneAction: LastAction? = null
) {
    val hasMap: Boolean get() = mapInfo != null
    val isTracking: Boolean get() = interaction is SurveyInteraction.ContinuousTracking
    val isBusy: Boolean get() = interaction !is SurveyInteraction.Idle
    val activeTrackPoints: List<SurveyCoordinate>
        get() = (interaction as? SurveyInteraction.ContinuousTracking)?.waypoints.orEmpty()
    val activeContinuousScanCount: Int
        get() = (interaction as? SurveyInteraction.ContinuousTracking)?.scanCount ?: 0
}
