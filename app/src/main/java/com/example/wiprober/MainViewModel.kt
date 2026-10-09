package com.example.wiprober

import androidx.lifecycle.ViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlin.math.sqrt

/** Owns immutable survey data and the single active interaction on the survey screen. */
class MainViewModel @JvmOverloads constructor(
    private val clock: MillisClock = SystemMillisClock,
    private val idSource: IdSource = UuidIdSource
) : ViewModel() {

    private val _screenState = MutableStateFlow(SurveyScreenState())
    val screenState: StateFlow<SurveyScreenState> = _screenState.asStateFlow()

    private val recentScanTimestamps = ArrayDeque<Long>()
    private var activeSessionId: String? = null
    private var activeSessionStartTime: Long = 0L
    private val activeWaypoints = mutableListOf<RoutePointWrapper>()
    private val activeScanResults = mutableListOf<ScanResultWrapper>()

    fun loadProject(
        mapInfo: MapInfo,
        scale: Double?,
        scanPoints: List<ScanPoint>,
        continuousSessions: List<ContinuousScanSession>,
        notes: List<AppNote>
    ) {
        val selectedScanMode = _screenState.value.scanMode
        clearActiveSession()
        recentScanTimestamps.clear()
        _screenState.value = SurveyScreenState(
            mapInfo = mapInfo,
            metersPerUnit = scale,
            scanPoints = scanPoints.map { it.copy(wifiNetworks = it.wifiNetworks.toList()) },
            continuousScanSessions = continuousSessions.map { it.immutableCopy() },
            notes = notes.toList(),
            scanMode = selectedScanMode,
            actionHistory = buildList {
                repeat(scanPoints.count { !it.importedFromEsx }) { add(LastAction.SCAN) }
                repeat(continuousSessions.count { !it.importedFromEsx }) { add(LastAction.SCAN_SESSION) }
                repeat(notes.count { !it.importedFromEsx }) { add(LastAction.NOTE) }
            }
        )
    }

    fun setScanMode(mode: SurveyScanMode): Boolean {
        val state = _screenState.value
        if (!state.hasMap || state.interaction is SurveyInteraction.ContinuousTracking ||
            state.interaction is SurveyInteraction.StopAndGoScanning ||
            state.interaction is SurveyInteraction.AwaitingScanAccess ||
            state.interaction is SurveyInteraction.ProjectBusy
        ) return false
        _screenState.value = state.copy(
            scanMode = mode,
            interaction = when {
                mode == SurveyScanMode.CONTINUOUS &&
                    state.interaction is SurveyInteraction.AwaitingContinuousStart -> state.interaction
                else -> SurveyInteraction.Idle
            }
        )
        return true
    }

    fun beginNotePlacement(): Boolean = transitionFromIdle(SurveyInteraction.NotePlacement)

    fun finishNotePlacement() {
        if (_screenState.value.interaction is SurveyInteraction.NotePlacement) setInteraction(SurveyInteraction.Idle)
    }

    fun enterCalibrationMode(): Boolean {
        val state = _screenState.value
        if (!state.hasMap || state.interaction !is SurveyInteraction.Idle) return false
        _screenState.value = state.copy(
            scanMode = SurveyScanMode.STOP_AND_GO,
            interaction = SurveyInteraction.Calibration(emptyList())
        )
        return true
    }

    fun addCalibrationPoint(point: SurveyCoordinate): List<SurveyCoordinate> {
        val calibration = _screenState.value.interaction as? SurveyInteraction.Calibration
            ?: return emptyList()
        val points = (calibration.points + point).take(2)
        setInteraction(SurveyInteraction.Calibration(points))
        return points
    }

    fun applyCalibration(distanceMeters: Double): Boolean {
        val calibration = _screenState.value.interaction as? SurveyInteraction.Calibration ?: return false
        if (distanceMeters <= 0 || calibration.points.size != 2) return false
        val (first, second) = calibration.points
        val dx = (first.x - second.x).toDouble()
        val dy = (first.y - second.y).toDouble()
        val distancePixels = sqrt(dx * dx + dy * dy)
        if (!distancePixels.isFinite() || distancePixels <= 0.0) return false
        _screenState.update {
            it.copy(metersPerUnit = distanceMeters / distancePixels, interaction = SurveyInteraction.Idle)
        }
        return true
    }

    fun clearCalibration() {
        if (_screenState.value.interaction is SurveyInteraction.Calibration) setInteraction(SurveyInteraction.Idle)
    }

    fun armContinuousStart(): Boolean {
        val state = _screenState.value
        if (!state.hasMap || state.scanMode != SurveyScanMode.CONTINUOUS ||
            state.interaction !is SurveyInteraction.Idle
        ) return false
        setInteraction(SurveyInteraction.AwaitingContinuousStart)
        return true
    }

    fun cancelContinuousStart() {
        if (_screenState.value.interaction is SurveyInteraction.AwaitingContinuousStart) {
            setInteraction(SurveyInteraction.Idle)
        }
    }

    fun requestStopAndGoScan(x: Float, y: Float): Boolean {
        val state = _screenState.value
        if (!state.hasMap || state.scanMode != SurveyScanMode.STOP_AND_GO ||
            state.interaction !is SurveyInteraction.Idle
        ) return false
        setInteraction(
            SurveyInteraction.AwaitingScanAccess(PendingScanRequest.StopAndGo(SurveyCoordinate(x, y)))
        )
        return true
    }

    fun requestContinuousStart(x: Float, y: Float): Boolean {
        val state = _screenState.value
        if (state.scanMode != SurveyScanMode.CONTINUOUS ||
            state.interaction !is SurveyInteraction.AwaitingContinuousStart
        ) return false
        setInteraction(
            SurveyInteraction.AwaitingScanAccess(PendingScanRequest.ContinuousStart(SurveyCoordinate(x, y)))
        )
        return true
    }

    fun pendingScanRequest(): PendingScanRequest? =
        (_screenState.value.interaction as? SurveyInteraction.AwaitingScanAccess)?.request

    fun grantPendingScanAccess(): StartedScanAction? {
        val request = pendingScanRequest() ?: return null
        return when (request) {
            is PendingScanRequest.StopAndGo -> {
                setInteraction(SurveyInteraction.StopAndGoScanning(request.coordinate))
                StartedScanAction.StopAndGo(request.coordinate)
            }
            is PendingScanRequest.ContinuousStart -> {
                startContinuousTrack(request.coordinate)
                StartedScanAction.Continuous
            }
        }
    }

    fun cancelPendingScanRequest() {
        if (_screenState.value.interaction is SurveyInteraction.AwaitingScanAccess) {
            setInteraction(SurveyInteraction.Idle)
        }
    }

    fun failStopAndGoScan() {
        if (_screenState.value.interaction is SurveyInteraction.StopAndGoScanning) {
            setInteraction(SurveyInteraction.Idle)
        }
    }

    fun completeStopAndGoScan(wifiNetworks: List<WifiNetworkInfo>): ScanPoint? {
        val scan = _screenState.value.interaction as? SurveyInteraction.StopAndGoScanning ?: return null
        val timestamp = clock.now()
        val point = ScanPoint(
            timestamp = timestamp,
            x = scan.coordinate.x,
            y = scan.coordinate.y,
            wifiNetworks = wifiNetworks.toList()
        )
        recentScanTimestamps.addLast(timestamp)
        _screenState.update {
            it.copy(
                scanPoints = it.scanPoints + point,
                actionHistory = it.actionHistory + LastAction.SCAN,
                interaction = SurveyInteraction.Idle
            )
        }
        return point
    }

    fun stopAndGoThrottleDelayMillis(systemThrottlingEnabled: Boolean): Long {
        if (!systemThrottlingEnabled) return 0L
        val now = clock.now()
        while (recentScanTimestamps.firstOrNull()?.let { now - it >= THROTTLE_WINDOW_MILLIS } == true) {
            recentScanTimestamps.removeFirst()
        }
        if (recentScanTimestamps.size < MAX_SCANS_PER_WINDOW) return 0L
        return (recentScanTimestamps.first() + THROTTLE_WINDOW_MILLIS - now).coerceAtLeast(1L)
    }

    fun addContinuousWaypoint(x: Float, y: Float): Boolean {
        if (_screenState.value.interaction !is SurveyInteraction.ContinuousTracking) return false
        activeWaypoints += RoutePointWrapper(clock.now() - activeSessionStartTime, x, y)
        publishTrackingState()
        return true
    }

    fun beginContinuousScanAttempt(): Long? =
        if (_screenState.value.interaction is SurveyInteraction.ContinuousTracking) clock.now() else null

    fun completeContinuousScanAttempt(
        startedAt: Long,
        wifiNetworks: List<WifiNetworkInfo>
    ): Boolean {
        if (_screenState.value.interaction !is SurveyInteraction.ContinuousTracking) return false
        val now = clock.now()
        activeScanResults += ScanResultWrapper(
            timestamp = now - activeSessionStartTime,
            duration = (now - startedAt).coerceAtLeast(0L),
            wifiNetworks = wifiNetworks.toList()
        )
        publishTrackingState()
        return true
    }

    fun stopContinuousTrack(x: Float, y: Float): ContinuousScanSession? {
        if (_screenState.value.interaction !is SurveyInteraction.ContinuousTracking) return null
        val now = clock.now()
        activeWaypoints += RoutePointWrapper(now - activeSessionStartTime, x, y)
        val session = ContinuousScanSession(
            id = activeSessionId ?: idSource.newId(),
            startTime = activeSessionStartTime,
            endTime = now,
            waypoints = activeWaypoints.toList(),
            scanResults = activeScanResults.toList()
        )
        _screenState.update {
            it.copy(
                continuousScanSessions = it.continuousScanSessions + session,
                actionHistory = it.actionHistory + LastAction.SCAN_SESSION,
                interaction = SurveyInteraction.Idle
            )
        }
        clearActiveSession()
        return session
    }

    fun activeContinuousElapsedMillis(now: Long = clock.now()): Long =
        if (_screenState.value.interaction is SurveyInteraction.ContinuousTracking) {
            (now - activeSessionStartTime).coerceAtLeast(0L)
        } else {
            0L
        }

    fun addNote(
        text: String,
        photoUri: String?,
        photoWidth: Int?,
        photoHeight: Int?,
        x: Float,
        y: Float,
        photoFormat: String?
    ): AppNote? {
        val state = _screenState.value
        if (!state.hasMap || state.interaction !is SurveyInteraction.Idle) return null
        val note = AppNote(
            id = idSource.newId(),
            text = text,
            photoUri = photoUri,
            photoWidth = photoWidth,
            photoHeight = photoHeight,
            photoId = photoUri?.let { idSource.newId() },
            x = x,
            y = y,
            pictureNoteId = idSource.newId(),
            photoFormat = photoFormat
        )
        _screenState.value = state.copy(
            notes = state.notes + note,
            actionHistory = state.actionHistory + LastAction.NOTE
        )
        return note
    }

    fun undoLastAction(): LastAction? {
        val state = _screenState.value
        if (state.interaction !is SurveyInteraction.Idle) return null
        val action = state.actionHistory.lastOrNull()
        if (action == null) {
            _screenState.value = state.copy(lastUndoneAction = null)
            return null
        }
        _screenState.value = when (action) {
            LastAction.SCAN -> state.copy(
                scanPoints = state.scanPoints.dropLastLocal(),
                actionHistory = state.actionHistory.dropLast(1),
                lastUndoneAction = action
            )
            LastAction.NOTE -> state.copy(
                notes = state.notes.dropLastLocal(),
                actionHistory = state.actionHistory.dropLast(1),
                lastUndoneAction = action
            )
            LastAction.SCAN_SESSION -> state.copy(
                continuousScanSessions = state.continuousScanSessions.dropLastLocal(),
                actionHistory = state.actionHistory.dropLast(1),
                lastUndoneAction = action
            )
        }
        return action
    }

    fun consumeLastUndoneAction() {
        if (_screenState.value.lastUndoneAction != null) {
            _screenState.update { it.copy(lastUndoneAction = null) }
        }
    }

    fun beginProjectOperation(operation: ProjectOperation): Boolean {
        val state = _screenState.value
        val mayReplacePicker = state.interaction is SurveyInteraction.ProjectBusy &&
            state.interaction.operation in setOf(
                ProjectOperation.FLOOR_MAP_SELECTION,
                ProjectOperation.EXPORT_DESTINATION
            )
        if (!mayReplacePicker && state.interaction !is SurveyInteraction.Idle) return false
        _screenState.value = state.copy(interaction = SurveyInteraction.ProjectBusy(operation))
        return true
    }

    fun finishProjectOperation(operation: ProjectOperation? = null) {
        val busy = _screenState.value.interaction as? SurveyInteraction.ProjectBusy ?: return
        if (operation == null || busy.operation == operation) setInteraction(SurveyInteraction.Idle)
    }

    /** Returns an Activity-owned project operation that must be safely restarted. */
    fun recoverAfterActivityRecreation(): ProjectOperation? {
        return when (val interaction = _screenState.value.interaction) {
            is SurveyInteraction.StopAndGoScanning -> {
                setInteraction(SurveyInteraction.Idle)
                null
            }
            is SurveyInteraction.ProjectBusy -> {
                if (interaction.operation == ProjectOperation.FLOOR_MAP_SELECTION ||
                    interaction.operation == ProjectOperation.EXPORT_DESTINATION
                ) {
                    null // Activity ResultRegistry will deliver the outstanding result.
                } else {
                    setInteraction(SurveyInteraction.Idle)
                    interaction.operation
                }
            }
            else -> null
        }
    }

    fun projectSnapshot(): ProjectSnapshot? {
        val state = _screenState.value
        val mapInfo = state.mapInfo ?: return null
        return ProjectSnapshot(
            mapInfo = mapInfo,
            metersPerUnit = state.metersPerUnit,
            scanPoints = state.scanPoints.toList(),
            continuousScanSessions = state.continuousScanSessions.map { it.immutableCopy() },
            notes = state.notes.toList()
        )
    }

    private fun startContinuousTrack(coordinate: SurveyCoordinate) {
        activeSessionId = idSource.newId()
        activeSessionStartTime = clock.now()
        activeWaypoints.clear()
        activeScanResults.clear()
        activeWaypoints += RoutePointWrapper(0L, coordinate.x, coordinate.y)
        publishTrackingState()
    }

    private fun publishTrackingState() {
        _screenState.update {
            it.copy(
                interaction = SurveyInteraction.ContinuousTracking(
                    startedAt = activeSessionStartTime,
                    waypoints = activeWaypoints.map { point -> SurveyCoordinate(point.x, point.y) },
                    scanCount = activeScanResults.size
                )
            )
        }
    }

    private fun clearActiveSession() {
        activeSessionId = null
        activeSessionStartTime = 0L
        activeWaypoints.clear()
        activeScanResults.clear()
    }

    private fun transitionFromIdle(interaction: SurveyInteraction): Boolean {
        val state = _screenState.value
        if (!state.hasMap || state.interaction !is SurveyInteraction.Idle) return false
        _screenState.value = state.copy(interaction = interaction)
        return true
    }

    private fun setInteraction(interaction: SurveyInteraction) {
        _screenState.update { it.copy(interaction = interaction) }
    }

    private fun ContinuousScanSession.immutableCopy(): ContinuousScanSession = copy(
        waypoints = waypoints.toList(),
        scanResults = scanResults.map { it.copy(wifiNetworks = it.wifiNetworks.toList()) }
    )

    private fun <T> List<T>.dropLastLocal(): List<T> {
        val index = indexOfLast { item ->
            when (item) {
                is ScanPoint -> !item.importedFromEsx
                is ContinuousScanSession -> !item.importedFromEsx
                is AppNote -> !item.importedFromEsx
                else -> true
            }
        }
        return if (index < 0) this else filterIndexed { itemIndex, _ -> itemIndex != index }
    }

    companion object {
        private const val THROTTLE_WINDOW_MILLIS = 2 * 60 * 1_000L
        private const val MAX_SCANS_PER_WINDOW = 4
    }
}
