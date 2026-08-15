package com.example.wiprober

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

data class EkahauFloorInput(
    val name: String,
    val mapInfo: MapInfo,
    val metersPerUnit: Double?,
    val scanPoints: List<ScanPoint>,
    val continuousSessions: List<ContinuousScanSession>,
    val notes: List<AppNote>,
    val floorPlanId: String? = null,
    val imageId: String? = null
)

/**
 * Класс-"фабрика", отвечающий за преобразование "сырых" данных сканирования,
 * собранных в приложении, в сложную, взаимосвязанную структуру данных формата Ekahau.
 */
class EkahauReportBuilder(
    private val clock: MillisClock = SystemMillisClock,
    private val idSource: IdSource = UuidIdSource
) {

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
    ): EkahauReport = build(
        projectName = "Survey from ${mapInfo.fileName.substringBeforeLast('.')}",
        floors = listOf(
            EkahauFloorInput(
                name = mapInfo.fileName.substringBeforeLast('.'),
                mapInfo = mapInfo,
                metersPerUnit = metersPerUnit,
                scanPoints = scanPoints,
                continuousSessions = continuousSessions,
                notes = notes
            )
        )
    )

    fun build(projectName: String, floors: List<EkahauFloorInput>): EkahauReport {
        require(floors.isNotEmpty()) { "Cannot export a project without floor plans" }
        val floorContexts = floors.map { floor ->
            val imageId = floor.imageId ?: newEsxId()
            val width = floor.mapInfo.width.toDouble()
            val height = floor.mapInfo.height.toDouble()
            FloorContext(
                input = floor,
                floorPlan = EsxFloorPlan(
                    id = floor.floorPlanId ?: newEsxId(),
                    name = floor.name,
                    width = width,
                    height = height,
                    imageId = imageId,
                    metersPerUnit = floor.metersPerUnit ?: 0.025,
                    cropMaxX = width,
                    cropMaxY = height
                ),
                image = EsxImage(
                    id = imageId,
                    imageFormat = floor.mapInfo.fileName.substringAfterLast('.', "JPEG")
                        .uppercase(Locale.ROOT),
                    resolutionWidth = width,
                    resolutionHeight = height
                )
            )
        }
        val floorPlansWrapper = EsxFloorPlansWrapper(floorContexts.map(FloorContext::floorPlan))

        // --- 2. СБОР ВСЕХ УНИКАЛЬНЫХ СЕТЕЙ (из Stop&Go И из Continuous) ---
        val apMap = mutableMapOf<String, EsxAccessPointMeasurement>()

        // Собираем из Stop-and-Go
        floors.forEach { floor ->
            floor.scanPoints.forEach { scanPoint ->
                scanPoint.wifiNetworks.forEach { network -> addNetworkToMap(apMap, network) }
            }
            floor.continuousSessions.forEach { session ->
                session.scanResults.forEach { scanResult ->
                    scanResult.wifiNetworks.forEach { network -> addNetworkToMap(apMap, network) }
                }
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

            val accessPoint = EsxAccessPoint(id = newEsxId(), name = apName)
            accessPointsList.add(accessPoint)

            val measuredRadio = EsxMeasuredRadio(
                id = newEsxId(),
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

        floorContexts.forEach { floorContext ->
            addFloorSurveys(
                floorContext,
                apMap,
                surveyLookups,
                surveysMap,
                binaryDataMap
            )
        }

        val surveyLookupsWrapper = EsxSurveyLookupsWrapper(surveyLookups)

        // --- 5. ПРОЕКТ, ИСТОРИЯ, ЗАМЕТКИ ---
        val thumbnail = EsxThumbnail(dataFloorPlanId = floorContexts.first().floorPlan.id)
        val generatedAt = getCurrentUtcTime()
        val project = EsxProject(
            id = newEsxId(),
            name = projectName,
            title = projectName,
            thumbnail = thumbnail,
            history = EsxHistory(modifiedAt = generatedAt, createdAt = generatedAt)
        )
        val projectWrapper = EsxProjectWrapper(project)

        val projectHistoryEntry = EsxProjectHistoryEntry(
            id = newEsxId(),
            projectId = project.id,
            projectName = project.name,
            timestamp = generatedAt.replace("Z", "+0000")
        )
        val projectHistorysWrapper = EsxProjectHistorysWrapper(listOf(projectHistoryEntry))

        val esxNotes = mutableListOf<EsxNote>()
        val esxPictureNotes = mutableListOf<EsxPictureNote>()
        val esxImagesForNotes = mutableListOf<EsxImage>()
        val usedNoteIds = mutableSetOf<String>()
        val usedPictureNoteIds = mutableSetOf<String>()
        val usedPhotoIds = mutableSetOf<String>()

        floorContexts.forEach { floorContext ->
            floorContext.input.notes.forEach { appNote ->
                require(usedNoteIds.add(appNote.id)) { "Duplicate note ID across floor plans" }
                require(usedPictureNoteIds.add(appNote.pictureNoteId)) {
                    "Duplicate picture-note ID across floor plans"
                }
                val photoId = appNote.photoId
                if (photoId != null) require(usedPhotoIds.add(photoId)) {
                    "Duplicate note image ID across floor plans"
                }
                esxNotes += EsxNote(
                    id = appNote.id,
                    text = appNote.text,
                    imageIds = photoId?.let(::listOf).orEmpty(),
                    history = EsxSurveyHistory(createdAt = getCurrentUtcTime())
                )
                esxPictureNotes += EsxPictureNote(
                    id = appNote.pictureNoteId,
                    location = EsxNoteLocation(
                        floorPlanId = floorContext.floorPlan.id,
                        coord = Location(appNote.x.toDouble(), appNote.y.toDouble())
                    ),
                    noteIds = listOf(appNote.id)
                )
                if (photoId != null) {
                    esxImagesForNotes += EsxImage(
                        id = photoId,
                        imageFormat = appNote.photoFormat ?: "JPEG",
                        resolutionWidth = appNote.photoWidth?.toDouble() ?: 0.0,
                        resolutionHeight = appNote.photoHeight?.toDouble() ?: 0.0
                    )
                }
            }
        }

        return EkahauReport(
            project = projectWrapper,
            floorPlans = floorPlansWrapper,
            accessPointMeasurements = accessPointMeasurementsWrapper,
            surveyLookups = surveyLookupsWrapper,
            surveys = surveysMap,
            binaryData = binaryDataMap,
            projectHistorys = projectHistorysWrapper,
            images = EsxImagesWrapper(floorContexts.map(FloorContext::image) + esxImagesForNotes),
            accessPoints = accessPointsWrapper,
            measuredRadios = measuredRadiosWrapper,
            notes = EsxNotesWrapper(esxNotes),
            pictureNotes = EsxPictureNotesWrapper(esxPictureNotes)
        )
    }

    private fun addFloorSurveys(
        floorContext: FloorContext,
        apMap: Map<String, EsxAccessPointMeasurement>,
        surveyLookups: MutableList<EsxSurveyLookup>,
        surveysMap: MutableMap<String, EsxSurveysWrapper>,
        binaryDataMap: MutableMap<String, ByteArray>
    ) {
        val floorPlan = floorContext.floorPlan
        floorContext.input.scanPoints.forEach { scanPoint ->
            val orderedApIds = scanPoint.wifiNetworks
                .mapNotNull { network -> apMap[networkKey(network.bssid)]?.id }
                .distinct()
            val apIndexById = orderedApIds.withIndex().associate { it.value to it.index }
            val measurementsForBin = mutableListOf<BinaryDataSerializer.MeasurementEntry>()

            scanPoint.wifiNetworks.forEach { network ->
                apMap[networkKey(network.bssid)]?.id?.let { apId ->
                    val index = apIndexById[apId]
                    if (index != null) {
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

            val trackId = newEsxId()
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
                id = newEsxId(),
                floorPlanId = floorPlan.id,
                name = surveyName,
                startTime = surveyStartTime,
                routePoints = routePoints,
                wifiTracks = listOf(wifiTrack),
                history = EsxSurveyHistory(createdAt = surveyStartTime),
                routeType = "STOP_AND_GO"
            )

            surveyLookups.add(
                EsxSurveyLookup(
                    id = newEsxId(),
                    surveyId = survey.id,
                    floorPlanId = floorPlan.id
                )
            )
            surveysMap[survey.id] = EsxSurveysWrapper(listOf(survey))
            binaryDataMap[trackId] = BinaryDataSerializer.serialize(measurementsForBin)
        }

        floorContext.input.continuousSessions.forEach { session ->
            val surveyId = session.id
            val startTimeData = Date(session.startTime)
            val totalDurationMs = if (session.endTime > session.startTime) session.endTime - session.startTime else 1000L

            // 1. Собираем уникальные AP ID для заголовка трека
            val uniqueApIdsInTrack = mutableSetOf<String>()
            session.scanResults.forEach { scan ->
                scan.wifiNetworks.forEach { net -> apMap[networkKey(net.bssid)]?.id?.let { uniqueApIdsInTrack.add(it) } }
            }
            val orderedApIds = uniqueApIdsInTrack.toList()
            val apIndexById = orderedApIds.withIndex().associate { it.value to it.index }

            // 2. Готовим данные для бинарника
            val measurementsForBin = mutableListOf<BinaryDataSerializer.MeasurementEntry>()

            session.scanResults.forEach { scan ->
                // ПРАВКА ВРЕМЕНИ:
                // В бинарный файл пишем время НАЧАЛА сканирования (Start Time), а не конца.
                // scan.timestamp = конец сканирования (относительно старта трека).
                // scan.duration  = длительность.
                val relStartTime = scan.timestamp - scan.duration

                // Защита от отрицательных чисел и перевод в Int (мс)
                val nonNegativeStartTime = relStartTime.coerceAtLeast(0L)
                require(nonNegativeStartTime <= Int.MAX_VALUE) {
                    "Continuous survey is too long for the ESX binary track format"
                }
                val relTimeMillis = nonNegativeStartTime.toInt()

                scan.wifiNetworks.forEach { net ->
                    apMap[networkKey(net.bssid)]?.id?.let { apId ->
                        val idx = apIndexById[apId]
                        if (idx != null) {
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
                    time = millisecondsToNanoseconds(wp.timestamp),
                    location = Location(wp.x.toDouble(), wp.y.toDouble())
                )
            }

            // 4. Формируем Scannings (Интервалы работы радио) - время в наносекундах
            val scanningsList = session.scanResults.map { scan ->
                val endNs = millisecondsToNanoseconds(scan.timestamp)
                val startNs = millisecondsToNanoseconds((scan.timestamp - scan.duration).coerceAtLeast(0L))
                Scanning(
                    startTime = startNs,
                    endTime = endNs
                )
            }

            // 5. Создаем Survey объект
            val trackId = newEsxId()
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
                duration = millisecondsToNanoseconds(totalDurationMs),
                routePoints = listOf(routePointsList),
                wifiTracks = listOf(wifiTrack),
                history = EsxSurveyHistory(createdAt = surveyStartTime),
                routeType = "CONTINUOUS"
            )

            surveyLookups.add(
                EsxSurveyLookup(
                    id = newEsxId(),
                    surveyId = survey.id,
                    floorPlanId = floorPlan.id
                )
            )
            surveysMap[survey.id] = EsxSurveysWrapper(listOf(survey))
            binaryDataMap[trackId] = BinaryDataSerializer.serialize(measurementsForBin)
        }

    }

    // --- Helpers ---

    private fun addNetworkToMap(map: MutableMap<String, EsxAccessPointMeasurement>, network: WifiNetworkInfo) {
        val key = networkKey(network.bssid)
        val existing = map[key]
        if (existing == null) {
            map[key] = EsxAccessPointMeasurement(
                id = newEsxId(),
                mac = key,
                ssid = network.ssid,
                channels = listOf(network.frequency),
                security = network.security,
                technologies = network.technologies,
                informationElements = network.informationElements
            )
        } else {
            map[key] = existing.copy(
                ssid = existing.ssid.takeUnless { it.isBlank() || it == "<unknown ssid>" } ?: network.ssid,
                channels = (existing.channels + network.frequency).distinct().sorted(),
                security = existing.security.takeUnless { it == "Unknown" } ?: network.security,
                technologies = (existing.technologies + network.technologies).distinct().sorted(),
                informationElements = existing.informationElements.ifBlank { network.informationElements }
            )
        }
    }

    private fun networkKey(bssid: String): String {
        val hex = bssid.trim().replace(":", "").replace("-", "").uppercase(Locale.ROOT)
        require(hex.length == 12 && hex.all { it in '0'..'9' || it in 'A'..'F' }) {
            "Invalid Wi-Fi BSSID"
        }
        return hex.chunked(2).joinToString(":")
    }

    private fun newEsxId(): String = idSource.newId().also {
        require(EsxIdFactory.isUuid(it)) { "Generated ESX ID is not a UUID" }
    }

    private fun millisecondsToNanoseconds(milliseconds: Long): Long {
        require(milliseconds >= 0L) { "ESX relative time must be non-negative" }
        return Math.multiplyExact(milliseconds, 1_000_000L)
    }

    private fun getCurrentUtcTime(): String {
        return getCurrentUtcTimeFromDate(Date(clock.now()))
    }

    private fun getCurrentUtcTimeFromDate(date: Date): String {
        val sdf = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'", Locale.US)
        sdf.timeZone = TimeZone.getTimeZone("UTC")
        return sdf.format(date)
    }

    private data class FloorContext(
        val input: EkahauFloorInput,
        val floorPlan: EsxFloorPlan,
        val image: EsxImage
    )
}
