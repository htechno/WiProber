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
 *
 * Основной метод - [build], который на вход принимает списки точек сканирования,
 * заметки и информацию о карте, а на выходе отдает готовый объект [EkahauReport].
 */
class EkahauReportBuilder {
    /**
     * Основной метод, который собирает полный отчет в формате Ekahau.
     * @param scanPoints Список "сырых" точек сканирования, полученных от пользователя.
     * @param mapInfo Метаданные загруженной карты (имя, размеры).
     * @param metersPerUnit Откалиброванный пользователем масштаб (метров в пикселе), или null.
     * @param notes Список заметок, созданных пользователем.
     * @return Готовый объект [EkahauReport], содержащий все компоненты для генерации .esx файла.
     */
    fun build(
        scanPoints: List<ScanPoint>,
        mapInfo: MapInfo,
        metersPerUnit: Double?,
        notes: List<AppNote>
    ): EkahauReport {
        // ПЛАН ЭТАЖА И ИЗОБРАЖЕНИЕ
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

        // УНИКАЛЬНЫЕ РАДИОИЗМЕРЕНИЯ
        val apMap = mutableMapOf<String, EsxAccessPointMeasurement>()
        scanPoints.forEach { scanPoint ->
            scanPoint.wifiNetworks.forEach { network ->
                if (!apMap.containsKey(network.bssid)) {
                    apMap[network.bssid] = EsxAccessPointMeasurement(
                        mac = network.bssid,
                        ssid = network.ssid,
                        channels = listOf(network.frequency),
                        security = network.security,
                        technologies = network.technologies,
                        informationElements = network.informationElements
                    )
                }
            }
        }
        val accessPointMeasurements = apMap.values.toList()
        val accessPointMeasurementsWrapper =
            EsxAccessPointMeasurementsWrapper(accessPointMeasurements)

        // ТОЧКИ ДОСТУПА И СВЯЗИ С РАДИО
        val accessPointsList = mutableListOf<EsxAccessPoint>()
        val measuredRadiosList = mutableListOf<EsxMeasuredRadio>()

        accessPointMeasurements.forEach { measurement ->
            val macParts = measurement.mac.split(":")
            val nameSuffix =
                if (macParts.size >= 6) "${macParts[4]}:${macParts[5]}" else measurement.mac.replace(
                    ":",
                    ""
                )
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

        // ОПРОСЫ, LOOKUPS И БИНАРНЫЕ ДАННЫЕ
        val surveyLookups = mutableListOf<EsxSurveyLookup>()
        val surveysMap = mutableMapOf<String, EsxSurveysWrapper>()
        val binaryDataMap = mutableMapOf<String, ByteArray>()

        scanPoints.forEach { scanPoint ->
            val orderedApIds =
                scanPoint.wifiNetworks.mapNotNull { network -> apMap[network.bssid]?.id }
            val measurementsForSerialization = mutableListOf<Pair<Int, WifiNetworkInfo>>()
            scanPoint.wifiNetworks.forEach { network ->
                apMap[network.bssid]?.id?.let { apId ->
                    val index = orderedApIds.indexOf(apId)
                    if (index != -1) measurementsForSerialization.add(Pair(index, network))
                }
            }
            measurementsForSerialization.sortBy { it.first }

            val location = Location(scanPoint.x.toDouble(), scanPoint.y.toDouble())
            val routePoints =
                listOf(listOf(RoutePoint(1000000L, location), RoutePoint(5002000000L, location)))
            val scannings = listOf(Scanning(1000000L, 3971000000L))
            val wifiTrack =
                WifiTrack(accessPointMeasurementIds = orderedApIds, scannings = scannings)

            val surveyDate = Date(scanPoint.timestamp)
            val nameFormatter = SimpleDateFormat("yyyy-MM-dd-HH:mm", Locale.US)
            val startTimeFormatter =
                SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'", Locale.US).apply {
                    timeZone = TimeZone.getTimeZone("UTC")
                }
            val surveyStartTime = startTimeFormatter.format(surveyDate)

            val survey = EsxSurvey(
                floorPlanId = floorPlan.id,
                name = nameFormatter.format(surveyDate),
                startTime = surveyStartTime,
                routePoints = routePoints,
                wifiTracks = listOf(wifiTrack),
                history = EsxSurveyHistory(createdAt = surveyStartTime)
            )

            surveyLookups.add(EsxSurveyLookup(surveyId = survey.id, floorPlanId = floorPlan.id))
            surveysMap[survey.id] = EsxSurveysWrapper(listOf(survey))

            val binaryBytes = BinaryDataSerializer.serialize(measurementsForSerialization)
            binaryDataMap[wifiTrack.binaryFileId] = binaryBytes
        }
        val surveyLookupsWrapper = EsxSurveyLookupsWrapper(surveyLookups)

        // PROJECT, HISTORY, REQUIREMENTS...
        val projectName = "Survey from ${mapInfo.fileName.substringBeforeLast('.')}"
        val thumbnail = EsxThumbnail(dataFloorPlanId = floorPlan.id)
        val project = EsxProject(name = projectName, title = projectName, thumbnail = thumbnail)
        val projectWrapper = EsxProjectWrapper(project)

        val projectHistoryEntry =
            EsxProjectHistoryEntry(projectId = project.id, projectName = project.name)
        val projectHistorysWrapper = EsxProjectHistorysWrapper(listOf(projectHistoryEntry))

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
                val imageFormat = "JPEG"
                val noteImage = EsxImage(
                    id = appNote.photoId,
                    imageFormat = imageFormat,
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
}

private fun getCurrentUtcTime(): String {
    val sdf = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'", Locale.US)
    sdf.timeZone = TimeZone.getTimeZone("UTC")
    return sdf.format(Date())
}