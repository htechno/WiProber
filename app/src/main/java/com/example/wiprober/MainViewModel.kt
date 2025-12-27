package com.example.wiprober

import android.graphics.PointF
import android.net.Uri
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.ViewModel

/**
 * [ViewModel] для [MainActivity], которая хранит все состояние экрана и бизнес-логику.
 */
class MainViewModel : ViewModel() {

    // --- ДАННЫЕ О КАРТЕ ---
    val currentMapInfo = MutableLiveData<MapInfo?>()
    val metersPerUnit = MutableLiveData<Double?>()
    val currentMapUri = MutableLiveData<Uri?>()

    // --- ДАННЫЕ СКАНИРОВАНИЯ (STOP-AND-GO) ---
    val scanPoints = MutableLiveData(mutableListOf<ScanPoint>())

    // --- ДАННЫЕ СКАНИРОВАНИЯ (CONTINUOUS) - НОВОЕ ---
    val continuousScanSessions = MutableLiveData(mutableListOf<ContinuousScanSession>())

    // --- ОБЩИЕ СПИСКИ ---
    val notesList = MutableLiveData<MutableList<AppNote>>(mutableListOf())
    val actionsHistory = MutableLiveData<MutableList<MainActivity.LastAction>>(mutableListOf())
    val recentScanTimestamps = mutableListOf<Long>()

    // --- ФЛАГИ СОСТОЯНИЯ UI ---
    val isMapLoaded = MutableLiveData(false)
    val isInCalibrationMode = MutableLiveData(false)
    val isInNoteCreationMode = MutableLiveData(false)

    // Переключатель режима: False = StopAndGo, True = Continuous
    val isContinuousMode = MutableLiveData(false)

    // Флаг: идет ли сейчас запись трека (пользователь идет)
    val isTracking = MutableLiveData(false)

    // Для отрисовки текущей линии, пока пользователь идет (Live путь)
    val currentTrackPoints = MutableLiveData<List<PointF>>(emptyList())
    val calibrationPoints = mutableListOf<PointF>()
    val lastActionType = MutableLiveData<MainActivity.LastAction?>()


    // --- ВРЕМЕННЫЕ ПЕРЕМЕННЫЕ ДЛЯ АКТИВНОЙ СЕССИИ ---
    private var activeSessionId: String? = null
    private var activeSessionStartTime: Long = 0
    private val activeWaypoints = mutableListOf<RoutePointWrapper>()
    private val activeScanResults = mutableListOf<ScanResultWrapper>()


    // =================================================================================
    // МЕТОДЫ continuous scan
    // =================================================================================

    /**
     * Начало нового трека. Пользователь нажал первую точку.
     */
    fun startContinuousTrack(x: Float, y: Float) {
        isTracking.value = true
        activeSessionId = java.util.UUID.randomUUID().toString()
        activeSessionStartTime = System.currentTimeMillis()

        activeWaypoints.clear()
        activeScanResults.clear()

        // Добавляем первую точку (Старт)
        // Время 0, так как это начало
        activeWaypoints.add(RoutePointWrapper(0, x, y))

        updateCurrentTrackVisuals()
    }

    /**
     * Добавление промежуточной точки (Поворот).
     */
    fun addContinuousWaypoint(x: Float, y: Float) {
        if (isTracking.value != true) return

        val relTime = System.currentTimeMillis() - activeSessionStartTime
        activeWaypoints.add(RoutePointWrapper(relTime, x, y))

        updateCurrentTrackVisuals()
    }

    /**
     * Завершение трека. Пользователь сделал долгий тап или закончил путь.
     */
    fun stopContinuousTrack(x: Float, y: Float) {
        if (isTracking.value != true) return

        // Добавляем финальную точку
        val relTime = System.currentTimeMillis() - activeSessionStartTime
        activeWaypoints.add(RoutePointWrapper(relTime, x, y))

        // Собираем готовую сессию
        val session = ContinuousScanSession(
            id = activeSessionId ?: java.util.UUID.randomUUID().toString(),
            startTime = activeSessionStartTime,
            endTime = System.currentTimeMillis(),
            waypoints = ArrayList(activeWaypoints),
            scanResults = ArrayList(activeScanResults)
        )

        // Сохраняем в общий список
        val list = continuousScanSessions.value ?: mutableListOf()
        list.add(session)
        continuousScanSessions.postValue(list)

        // Пишем в историю
        actionsHistory.value?.add(MainActivity.LastAction.SCAN_SESSION)
        actionsHistory.postValue(actionsHistory.value)

        // Сброс состояния
        isTracking.value = false
        activeWaypoints.clear()
        activeScanResults.clear()
        updateCurrentTrackVisuals()
    }

    /**
     * Добавление результатов скрытого сканирования в активную сессию.
     * @param duration - сколько длился скан в мс (нужно замерять в Activity)
     */
    fun addContinuousScanResult(wifiList: List<WifiNetworkInfo>, duration: Long) {
        if (isTracking.value != true) return

        // Timestamp - момент завершения скана относительно старта ходьбы
        val relTime = System.currentTimeMillis() - activeSessionStartTime

        val wrapper = ScanResultWrapper(
            timestamp = relTime,
            duration = duration,
            wifiNetworks = wifiList
        )
        activeScanResults.add(wrapper)
    }

    private fun updateCurrentTrackVisuals() {
        val visualPoints = activeWaypoints.map { PointF(it.x, it.y) }
        currentTrackPoints.postValue(visualPoints)
    }


    // =================================================================================
    // ОБЩИЕ МЕТОДЫ (StopAndGo + Notes)
    // =================================================================================

    fun addScanPoint(point: ScanPoint) {
        val list = scanPoints.value ?: mutableListOf()
        list.add(point)
        scanPoints.postValue(list)
        actionsHistory.value?.add(MainActivity.LastAction.SCAN)
        actionsHistory.postValue(actionsHistory.value)
    }

    fun addNote(note: AppNote) {
        val list = notesList.value ?: mutableListOf()
        list.add(note)
        notesList.postValue(list)
        actionsHistory.value?.add(MainActivity.LastAction.NOTE)
        actionsHistory.postValue(actionsHistory.value)
    }

    fun undoLastAction() {
        val history = actionsHistory.value
        if (history?.isNotEmpty() == true) {
            val lastAction = history.removeAt(history.lastIndex)
            lastActionType.postValue(lastAction)

            when (lastAction) {
                MainActivity.LastAction.SCAN -> {
                    val currentScanPoints = scanPoints.value
                    if (currentScanPoints?.isNotEmpty() == true) {
                        currentScanPoints.removeAt(currentScanPoints.lastIndex)
                        scanPoints.postValue(currentScanPoints)
                    }
                }
                MainActivity.LastAction.NOTE -> {
                    val currentNotes = notesList.value
                    if (currentNotes?.isNotEmpty() == true) {
                        currentNotes.removeAt(currentNotes.lastIndex)
                        notesList.postValue(currentNotes)
                    }
                }
                MainActivity.LastAction.SCAN_SESSION -> {
                    // Удаление последней записанной continuous сессии
                    val currentSessions = continuousScanSessions.value
                    if (currentSessions?.isNotEmpty() == true) {
                        currentSessions.removeAt(currentSessions.lastIndex)
                        continuousScanSessions.postValue(currentSessions)
                    }
                }
            }
            actionsHistory.postValue(history)
        } else {
            lastActionType.postValue(null)
        }
    }

    fun resetStateForNewMap(mapInfo: MapInfo, mapUri: Uri) {
        currentMapInfo.postValue(mapInfo)
        currentMapUri.postValue(mapUri)
        isMapLoaded.postValue(true)

        metersPerUnit.postValue(null)
        scanPoints.postValue(mutableListOf())
        continuousScanSessions.postValue(mutableListOf()) // сброс сессий
        notesList.postValue(mutableListOf())
        actionsHistory.postValue(mutableListOf())
        recentScanTimestamps.clear()

        // Сброс state continuous
        isTracking.postValue(false)
        activeWaypoints.clear()
        activeScanResults.clear()
        updateCurrentTrackVisuals()
    }

    fun enterCalibrationMode(enable: Boolean) {
        if (enable) {
            isInNoteCreationMode.postValue(false)
            // Если включили калибровку, лучше сбросить Continuous режим, чтобы не мешался
            isContinuousMode.postValue(false)
        }
        isInCalibrationMode.postValue(enable)
    }

    fun addCalibrationPoint(point: PointF) {
        if (isInCalibrationMode.value == true) {
            calibrationPoints.add(point)
        }
    }

    fun clearCalibration() {
        isInCalibrationMode.postValue(false)
        calibrationPoints.clear()
    }
}
