package com.example.wiprober

import android.net.Uri
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.ViewModel

/**
 * [ViewModel] для [MainActivity], которая хранит все состояние экрана и бизнес-логику,
 * чтобы пережить пересоздание Activity (например, при повороте экрана или нехватке памяти).
 *
 * Использует [MutableLiveData] для предоставления данных в UI (Activity),
 * позволяя UI реагировать на их изменения.
 */
class MainViewModel : ViewModel() {

    // ДАННЫЕ, КОТОРЫЕ НУЖНО СОХРАНЯТЬ

    // Информация о карте и масштабе
    val currentMapInfo = MutableLiveData<MapInfo?>()
    val metersPerUnit = MutableLiveData<Double?>()
    val currentMapUri = MutableLiveData<Uri?>()

    // Списки наших данных
    val scanPoints = MutableLiveData(mutableListOf<ScanPoint>())
    val notesList = MutableLiveData<MutableList<AppNote>>(mutableListOf())

    // Списки для управления состоянием
    val actionsHistory = MutableLiveData<MutableList<MainActivity.LastAction>>(mutableListOf())
    val recentScanTimestamps = mutableListOf<Long>()

    // Флаги состояния UI
    val isMapLoaded = MutableLiveData(false)
    val isInCalibrationMode = MutableLiveData(false)
    val isInNoteCreationMode = MutableLiveData(false)

    val calibrationPoints = mutableListOf<android.graphics.PointF>()

    // МЕТОДЫ ДЛЯ ИЗМЕНЕНИЯ ДАННЫХ
    /**
     * Добавляет новую точку сканирования в список и регистрирует это действие в истории.
     */
    fun addScanPoint(point: ScanPoint) {
        val list = scanPoints.value ?: mutableListOf()
        list.add(point)
        scanPoints.postValue(list)
        actionsHistory.value?.add(MainActivity.LastAction.SCAN)
        actionsHistory.postValue(actionsHistory.value)
    }

    /**
     * Добавляет новую заметку в список и регистрирует это действие в истории.
     */
    fun addNote(note: AppNote) {
        val list = notesList.value ?: mutableListOf()
        list.add(note)
        notesList.postValue(list)
        actionsHistory.value?.add(MainActivity.LastAction.NOTE)
        actionsHistory.postValue(actionsHistory.value)
    }

    /**
     * Отменяет последнее совершенное действие (скан или заметку),
     * удаляя соответствующие данные из списков.
     */
    fun undoLastAction() {
        val history = actionsHistory.value
        if (history?.isNotEmpty() == true) {
            val lastAction = history.removeAt(history.lastIndex)
            lastActionType.postValue(lastAction) // Сообщаем UI, что именно мы отменили

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
            }
            actionsHistory.postValue(history)
        } else {
            lastActionType.postValue(null) // Сбрасываем тип, если отменять нечего
        }
    }

    /**
     * Сбрасывает состояние ViewModel при загрузке новой карты.
     * Очищает все списки данных и устанавливает новые данные о карте.
     */
    fun resetStateForNewMap(mapInfo: MapInfo, mapUri: Uri) {
        // Устанавливаем новые значения
        currentMapInfo.postValue(mapInfo)
        currentMapUri.postValue(mapUri)
        isMapLoaded.postValue(true)

        // Сбрасываем все остальное
        metersPerUnit.postValue(null)
        scanPoints.postValue(mutableListOf())
        notesList.postValue(mutableListOf())
        actionsHistory.postValue(mutableListOf())
        recentScanTimestamps.clear()
    }

    fun clearCalibration() {
        isInCalibrationMode.postValue(false)
        calibrationPoints.clear()
    }

    // Поле для отслеживания типа последнего отмененного действия (для Toast'а в UI)
    val lastActionType = MutableLiveData<MainActivity.LastAction?>()

    // Метод для входа в режим калибровки
    fun enterCalibrationMode(enable: Boolean) {
        if (enable) {
            isInNoteCreationMode.postValue(false) // Выходим из других режимов
        }
        isInCalibrationMode.postValue(enable)
    }

    // Метод для добавления точки калибровки
    fun addCalibrationPoint(point: android.graphics.PointF) {
        if (isInCalibrationMode.value == true) {
            calibrationPoints.add(point)
        }
    }
}
