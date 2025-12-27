package com.example.wiprober

import java.util.UUID
import java.util.Date
import java.text.SimpleDateFormat
import java.util.Locale
import java.util.TimeZone

/**
 * Вспомогательный класс для хранения всех сгенерированных файлов и данных
 */
data class EkahauReport(
    val project: EsxProjectWrapper,
    val floorPlans: EsxFloorPlansWrapper,
    val accessPointMeasurements: EsxAccessPointMeasurementsWrapper,
    val surveyLookups: EsxSurveyLookupsWrapper,
    val surveys: Map<String, EsxSurveysWrapper>, // Key: surveyId, Value: survey content
    val binaryData: Map<String, ByteArray>,       // Key: binaryFileId, Value: сам ByteArray
    val projectHistorys: EsxProjectHistorysWrapper,
    val images: EsxImagesWrapper,
    val accessPoints: EsxAccessPointsWrapper,
    val measuredRadios: EsxMeasuredRadiosWrapper,
    val notes: EsxNotesWrapper,
    val pictureNotes: EsxPictureNotesWrapper
)

/**
 * Класс-"фабрика", отвечающий за преобразование "сырых" данных сканирования,
 * собранных в приложении, в сложную, взаимосвязанную структуру данных формата Ekahau.
 */
class EkahauReportBuilder {

    /**
     * Основной метод, который собирает полный отчет в формате Ekahau.
     * @param scanPoints Список точек режима "Stop-and-Go".
     * @param continuousSessions Список сессий режима "Continuous" (PRO mode).
     * @param mapInfo Метаданные загруженной карты.
     * @param metersPerUnit Откалиброванный масштаб.
     * @param notes Заметки.
     */
    fun build(
        scanPoints: List<ScanPoint>,
        continuousSessions: List<ContinuousScanSession>,
        mapInfo: MapInfo,
        metersPerUnit: Double?,
        notes: List<AppNote>
    ): EkahauReport {
        // --- 1. ПЛАН ЭТАЖА И ИЗОБРАЖЕНИЕ ---
        val imageId = UUID.randomUUID().toString()
        val mapWidth = mapInfo.width.toDouble()
        val mapHeight = mapInfo.height.toDouble()

        val floorPlan = EsxFloorPlan(
            name = mapInfo.fileName.substringBeforeLast('.'),
            width = mapWidth, height = mapHeight, imageId = imageId,
            metersPerUnit = metersPerUnit ?: 0.025,
            cropMaxX = mapWidth, cropMaxY = mapHeight
        )
        val floorPlansWrapper = EsxFloorPlansWrapper(listOf(floorPlan))

        val image = EsxImage(
            id = imageId,
            imageFormat = mapInfo.fileName.substringAfterLast('.', "JPEG").uppercase(Locale.ROOT),
            resolutionWidth = mapWidth,
            resolutionHeight = mapHeight
        )

        // --- 2. СБОР ВСЕХ УНИКАЛЬНЫХ СЕТЕЙ (из Stop&Go И из Continuous) ---
        val apMap = mutableMapOf<String, EsxAccessPointMeasurement>()

        // Собираем из Stop-and-Go
        scanPoints.forEach { scanPoint ->
            scanPoint.wifiNetworks.forEach { network -> addNetworkToMap(apMap, network) }
        }

        // Собираем из Continuous
        continuousSessions.forEach { session ->
            session.scanResults.forEach { scanResult ->
                scanResult.wifiNetworks.forEach { network -> addNetworkToMap(apMap, network) }
            }
        }

        val accessPointMeasurements = apMap.values.toList()
        val accessPointMeasurementsWrapper = EsxAccessPointMeasurementsWrapper(accessPointMeasurements)

        // --- 3. ГЕНЕРАЦИЯ СПИСКА AP (accessPoints.json, measuredRadios.json) ---
        val accessPointsList = mutableListOf<EsxAccessPoint>()
        val measuredRadiosList = mutableListOf<EsxMeasuredRadio>()

        accessPointMeasurements.forEach { measurement ->
            val macParts = measurement.mac.split(":")
            val nameSuffix = if (macParts.size >= 6) "${macParts[4]}:${macParts[5]}" else measurement.mac.replace(":", "")
            val apName = "Measured AP-$nameSuffix"

            val accessPoint = EsxAccessPoint(name = apName)
            accessPointsList.add(accessPoint)

            val measuredRadio = EsxMeasuredRadio(
                accessPointId = accessPoint.id,
                accessPointMeasurementIds = listOf(measurement.id)
            )
            measuredRadiosList.add(measuredRadio)
        }
        val accessPointsWrapper = EsxAccessPointsWrapper(accessPointsList)
        val measuredRadiosWrapper = EsxMeasuredRadiosWrapper(measuredRadiosList)

        // --- 4. ГЕНЕРАЦИЯ ОБСЛЕДОВАНИЙ (SURVEYS) ---
        val surveyLookups = mutableListOf<EsxSurveyLookup>()
        val surveysMap = mutableMapOf<String, EsxSurveysWrapper>()
        val binaryDataMap = mutableMapOf<String, ByteArray>()

        // А. Обработка стандартных точек (Stop-and-Go)
        scanPoints.forEach { scanPoint ->
            val orderedApIds = scanPoint.wifiNetworks.mapNotNull { network -> apMap[network.bssid]?.id }
            val measurementsForBin = mutableListOf<BinaryDataSerializer.MeasurementEntry>()

            scanPoint.wifiNetworks.forEach { network ->
                apMap[network.bssid]?.id?.let { apId ->
                    val index = orderedApIds.indexOf(apId)
                    if (index != -1) {
                        // Для Stop-and-Go Timestamp всегда 1
                        measurementsForBin.add(BinaryDataSerializer.MeasurementEntry(1, index, network))
                    }
                }
            }
            measurementsForBin.sortBy { it.apIndex }

            // Стандартная геометрия "Точка стояния"
            val location = Location(scanPoint.x.toDouble(), scanPoint.y.toDouble())
            val routePoints = listOf(listOf(RoutePoint(1000000L, location), RoutePoint(5002000000L, location)))
            val scannings = listOf(Scanning(1000000L, 3971000000L)) // ~4 сек

            val trackId = UUID.randomUUID().toString()
            val wifiTrack = WifiTrack(
                accessPointMeasurementIds = orderedApIds,
                scannings = scannings,
                binaryFileId = trackId,
                primary = true
            )

            val surveyDate = Date(scanPoint.timestamp)
            val nameFormatter = SimpleDateFormat("yyyy-MM-dd-HH:mm", Locale.US)
            val surveyStartTime = getCurrentUtcTimeFromDate(surveyDate)
            // Уникальное имя, чтобы не было конфликтов при быстрой серия сканов
            val surveyName = "${nameFormatter.format(surveyDate)}-SG-${scanPoint.timestamp % 1000}"

            val survey = EsxSurvey(
                floorPlanId = floorPlan.id,
                name = surveyName,
                startTime = surveyStartTime,
                routePoints = routePoints,
                wifiTracks = listOf(wifiTrack),
                history = EsxSurveyHistory(createdAt = surveyStartTime),
                routeType = "STOP_AND_GO"
            )

            surveyLookups.add(EsxSurveyLookup(surveyId = survey.id, floorPlanId = floorPlan.id))
            surveysMap[survey.id] = EsxSurveysWrapper(listOf(survey))
            binaryDataMap[trackId] = BinaryDataSerializer.serialize(measurementsForBin)
        }

        // Б. Обработка Continuous сессий
        continuousSessions.forEach { session ->
            val surveyId = session.id
            val startTimeData = Date(session.startTime)
            val totalDurationMs = if (session.endTime > session.startTime) session.endTime - session.startTime else 1000L

            // 1. Собираем уникальные AP ID для заголовка трека
            val uniqueApIdsInTrack = mutableSetOf<String>()
            session.scanResults.forEach { scan ->
                scan.wifiNetworks.forEach { net -> apMap[net.bssid]?.id?.let { uniqueApIdsInTrack.add(it) } }
            }
            val orderedApIds = uniqueApIdsInTrack.toList()

            // 2. Готовим данные для бинарника
            val measurementsForBin = mutableListOf<BinaryDataSerializer.MeasurementEntry>()

            session.scanResults.forEach { scan ->
                // ПРАВКА ВРЕМЕНИ:
                // В бинарный файл пишем время НАЧАЛА сканирования (Start Time), а не конца.
                // scan.timestamp = конец сканирования (относительно старта трека).
                // scan.duration  = длительность.
                val relStartTime = scan.timestamp - scan.duration

                // Защита от отрицательных чисел и перевод в Int (мс)
                val relTimeMillis = if (relStartTime < 0) 0 else relStartTime.toInt()

                scan.wifiNetworks.forEach { net ->
                    apMap[net.bssid]?.id?.let { apId ->
                        val idx = orderedApIds.indexOf(apId)
                        if (idx != -1) {
                            measurementsForBin.add(BinaryDataSerializer.MeasurementEntry(relTimeMillis, idx, net))
                        }
                    }
                }
            }

            // Сортировка: сначала по времени, потом по ID AP. Это важно для правильной группировки в Serializer.
            measurementsForBin.sortWith(compareBy({ it.relTimestamp }, { it.apIndex }))

            // 3. Формируем RoutePoints (Путь) - время в наносекундах!
            val routePointsList = session.waypoints.map { wp ->
                RoutePoint(
                    time = wp.timestamp * 1000000L, // ms -> ns
                    location = Location(wp.x.toDouble(), wp.y.toDouble())
                )
            }

            // 4. Формируем Scannings (Интервалы работы радио) - время в наносекундах
            val scanningsList = session.scanResults.map { scan ->
                val endNs = scan.timestamp * 1000000L
                val startNs = (scan.timestamp - scan.duration) * 1000000L
                Scanning(
                    startTime = if (startNs < 0) 0 else startNs,
                    endTime = endNs
                )
            }

            // 5. Создаем Survey объект
            val trackId = UUID.randomUUID().toString()
            val wifiTrack = WifiTrack(
                accessPointMeasurementIds = orderedApIds,
                scannings = scanningsList,
                binaryFileId = trackId,
                primary = true
            )

            val nameFormatter = SimpleDateFormat("yyyy-MM-dd-HH:mm", Locale.US)
            val surveyStartTime = getCurrentUtcTimeFromDate(startTimeData)
            val surveyName = "${nameFormatter.format(startTimeData)}-Walk"

            val survey = EsxSurvey(
                id = surveyId,
                floorPlanId = floorPlan.id,
                name = surveyName,
                startTime = surveyStartTime,
                duration = totalDurationMs * 1000000L,
                routePoints = listOf(routePointsList),
                wifiTracks = listOf(wifiTrack),
                history = EsxSurveyHistory(createdAt = surveyStartTime),
                routeType = "CONTINUOUS"
            )

            surveyLookups.add(EsxSurveyLookup(surveyId = survey.id, floorPlanId = floorPlan.id))
            surveysMap[survey.id] = EsxSurveysWrapper(listOf(survey))
            binaryDataMap[trackId] = BinaryDataSerializer.serialize(measurementsForBin)
        }

        val surveyLookupsWrapper = EsxSurveyLookupsWrapper(surveyLookups)

        // --- 5. ПРОЕКТ, ИСТОРИЯ, ЗАМЕТКИ ---
        val projectName = "Survey from ${mapInfo.fileName.substringBeforeLast('.')}"
        val thumbnail = EsxThumbnail(dataFloorPlanId = floorPlan.id)
        val project = EsxProject(name = projectName, title = projectName, thumbnail = thumbnail)
        val projectWrapper = EsxProjectWrapper(project)

        val projectHistoryEntry = EsxProjectHistoryEntry(projectId = project.id, projectName = project.name)
        val projectHistorysWrapper = EsxProjectHistorysWrapper(listOf(projectHistoryEntry))

        // Генерация заметок
        val esxNotes = mutableListOf<EsxNote>()
        val esxPictureNotes = mutableListOf<EsxPictureNote>()
        val esxImagesForNotes = mutableListOf<EsxImage>()

        notes.forEach { appNote ->
            val imageIdList = if (appNote.photoId != null) listOf(appNote.photoId) else emptyList()
            val esxNote = EsxNote(
                id = appNote.id,
                text = appNote.text,
                imageIds = imageIdList,
                history = EsxSurveyHistory(createdAt = getCurrentUtcTime())
            )
            esxNotes.add(esxNote)

            val esxPictureNote = EsxPictureNote(
                id = appNote.pictureNoteId,
                location = EsxNoteLocation(
                    floorPlanId = floorPlan.id,
                    coord = Location(x = appNote.x.toDouble(), y = appNote.y.toDouble())
                ),
                noteIds = listOf(appNote.id)
            )
            esxPictureNotes.add(esxPictureNote)

            if (appNote.photoId != null) {
                val noteImage = EsxImage(
                    id = appNote.photoId,
                    imageFormat = "JPEG",
                    resolutionWidth = appNote.photoWidth?.toDouble() ?: 0.0,
                    resolutionHeight = appNote.photoHeight?.toDouble() ?: 0.0
                )
                esxImagesForNotes.add(noteImage)
            }
        }

        val allImages = mutableListOf(image)
        allImages.addAll(esxImagesForNotes)
        val finalImagesWrapper = EsxImagesWrapper(allImages)

        val notesWrapper = EsxNotesWrapper(esxNotes)
        val pictureNotesWrapper = EsxPictureNotesWrapper(esxPictureNotes)

        return EkahauReport(
            project = projectWrapper,
            floorPlans = floorPlansWrapper,
            accessPointMeasurements = accessPointMeasurementsWrapper,
            surveyLookups = surveyLookupsWrapper,
            surveys = surveysMap,
            binaryData = binaryDataMap,
            projectHistorys = projectHistorysWrapper,
            images = finalImagesWrapper,
            accessPoints = accessPointsWrapper,
            measuredRadios = measuredRadiosWrapper,
            notes = notesWrapper,
            pictureNotes = pictureNotesWrapper
        )
    }

    // --- Helpers ---

    private fun addNetworkToMap(map: MutableMap<String, EsxAccessPointMeasurement>, network: WifiNetworkInfo) {
        if (!map.containsKey(network.bssid)) {
            map[network.bssid] = EsxAccessPointMeasurement(
                mac = network.bssid,
                ssid = network.ssid,
                channels = listOf(network.frequency),
                security = network.security,
                technologies = network.technologies,
                informationElements = network.informationElements
            )
        }
    }

    private fun getCurrentUtcTime(): String {
        return getCurrentUtcTimeFromDate(Date())
    }

    private fun getCurrentUtcTimeFromDate(date: Date): String {
        val sdf = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'", Locale.US)
        sdf.timeZone = TimeZone.getTimeZone("UTC")
        return sdf.format(date)
    }
}
