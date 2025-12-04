package com.example.wiprober

import com.google.gson.annotations.SerializedName
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone
import java.util.UUID

private const val PROJECT_CONFIG_ID = "ac617491-9beb-4f3a-b600-f0037669c1a9"
private const val WIFI_ADAPTER_ID = "3fb11fa8-4a44-4be9-b588-15d767bd768a"

// ----------- Файл: project.json -----------
data class EsxProjectWrapper(val project: EsxProject)
data class EsxProject(
    @SerializedName("id") val id: String = UUID.randomUUID().toString(),
    @SerializedName("status") val status: String = "CREATED",
    @SerializedName("name") val name: String,
    @SerializedName("title") val title: String,
    @SerializedName("schemaVersion") val schemaVersion: String = "1.9.0",
    @SerializedName("projectConfigurationId") val projectConfigurationId: String = PROJECT_CONFIG_ID,
    @SerializedName("customer") val customer: String = "",
    @SerializedName("location") val location: String = "",
    @SerializedName("responsiblePerson") val responsiblePerson: String = "",
    @SerializedName("noteIds") val noteIds: List<String> = emptyList(),
    @SerializedName("projectAncestors") val projectAncestors: List<String> = emptyList(),
    @SerializedName("tags") val tags: List<String> = emptyList(),
    @SerializedName("thumbnail") val thumbnail: EsxThumbnail,
    @SerializedName("history") val history: EsxHistory = EsxHistory()
)

data class EsxThumbnail(
    @SerializedName("dataFloorPlanId") val dataFloorPlanId: String,
    @SerializedName("mimeType") val mimeType: String = "image/png",
    @SerializedName("data") val data: String = ""
)

data class EsxHistory(
    @SerializedName("modifiedAt") val modifiedAt: String = getCurrentUtcTime(),
    @SerializedName("modifiedBy") val modifiedBy: String = "WiProber",
    @SerializedName("createdAt") val createdAt: String = getCurrentUtcTime(),
    @SerializedName("createdBy") val createdBy: String = "WiProber"
)

// ----------- Файл: projectHistorys.json -----------
data class EsxProjectHistorysWrapper(val projectHistorys: List<EsxProjectHistoryEntry>)
data class EsxProjectHistoryEntry(
    @SerializedName("id") val id: String = UUID.randomUUID().toString(),
    @SerializedName("status") val status: String = "CREATED",
    @SerializedName("projectId") val projectId: String,
    @SerializedName("projectName") val projectName: String,
    @SerializedName("timestamp") val timestamp: String = getCurrentUtcTimeForHistory(),
    @SerializedName("parentIds") val parentIds: List<String> = emptyList(),
    @SerializedName("productName") val productName: String = "Ekahau AI Pro",
    @SerializedName("productVersion") val productVersion: String = "11.8.5.1",
    @SerializedName("schemaVersion") val schemaVersion: String = "1.9.0",
    @SerializedName("operation") val operation: String = "LOCAL_SAVE",
    @SerializedName("platform") val platform: String = "Windows 10 64-bit"
)

// ----------- Файл: floorPlans.json -----------
data class EsxFloorPlansWrapper(val floorPlans: List<EsxFloorPlan>)
data class EsxFloorPlan(
    @SerializedName("id") val id: String = UUID.randomUUID().toString(),
    @SerializedName("status") val status: String = "CREATED",
    @SerializedName("name") val name: String,
    @SerializedName("width") val width: Double,
    @SerializedName("height") val height: Double,
    @SerializedName("imageId") val imageId: String,
    @SerializedName("metersPerUnit") val metersPerUnit: Double = 0.025,
    @SerializedName("gpsReferencePoints") val gpsReferencePoints: List<String> = emptyList(),
    @SerializedName("floorPlanType") val floorPlanType: String = "FSPL",
    @SerializedName("cropMinX") val cropMinX: Double = 0.0,
    @SerializedName("cropMinY") val cropMinY: Double = 0.0,
    @SerializedName("cropMaxX") val cropMaxX: Double,
    @SerializedName("cropMaxY") val cropMaxY: Double,
    @SerializedName("rotateUpDirection") val rotateUpDirection: String = "UP",
    @SerializedName("tags") val tags: List<String> = emptyList()
)

// ----------- Файл: accessPointMeasurements.json -----------
data class EsxAccessPointMeasurementsWrapper(val accessPointMeasurements: List<EsxAccessPointMeasurement>)
data class EsxAccessPointMeasurement(
    val id: String = UUID.randomUUID().toString(),
    val status: String = "CREATED",
    val mac: String,
    val ssid: String,
    @SerializedName("channelByCenterFrequencyDefinedNarrowChannels") val channels: List<Int>,
    val security: String,
    val technologies: List<String>,
    val informationElements: String
)

// ----------- Файл: surveyLookups.json -----------
data class EsxSurveyLookupsWrapper(val surveyLookups: List<EsxSurveyLookup>)
data class EsxSurveyLookup(
    val id: String = UUID.randomUUID().toString(),
    val surveyId: String,
    val floorPlanId: String
)

// ----------- Файл: survey-*.json -----------
data class EsxSurveysWrapper(val surveys: List<EsxSurvey>)
data class EsxSurvey(
    @SerializedName("id") val id: String = UUID.randomUUID().toString(),
    @SerializedName("status") val status: String = "CREATED",
    @SerializedName("name") val name: String,
    @SerializedName("startTime") val startTime: String,
    @SerializedName("duration") val duration: Long = 5002000000L - 1000000L,
    @SerializedName("floorPlanId") val floorPlanId: String,
    @SerializedName("routeType") val routeType: String = "STOP_AND_GO",
    @SerializedName("noteIds") val noteIds: List<String> = emptyList(),
    @SerializedName("routePoints") val routePoints: List<List<RoutePoint>>,
    @SerializedName("wifiTracks") val wifiTracks: List<WifiTrack>,
    @SerializedName("activeSurveyAdapterInformationIds") val activeSurveyAdapterInformationIds: List<String> = emptyList(),
    @SerializedName("associationIntervals") val associationIntervals: List<String> = emptyList(),
    @SerializedName("pingSessions") val pingSessions: List<PingSession> = listOf(PingSession()),
    @SerializedName("throughputSessions") val throughputSessions: List<String> = emptyList(),
    @SerializedName("spectrumSessions") val spectrumSessions: List<String> = emptyList(),
    @SerializedName("interferenceDetectionEvents") val interferenceDetectionEvents: List<String> = emptyList(),
    @SerializedName("history") val history: EsxSurveyHistory // Использует EsxSurveyHistory
)

// Класс для поля "history" в survey-*.json
data class EsxSurveyHistory(
    @SerializedName("createdAt") val createdAt: String,
    @SerializedName("createdBy") val createdBy: String = "WiProber"
)

data class RoutePoint(val time: Long, val location: Location)
data class Location(val x: Double, val y: Double)

data class WifiTrack(
    @SerializedName("wifiAdapterInformationId") val wifiAdapterInformationId: String = WIFI_ADAPTER_ID,
    @SerializedName("binaryFileId") val binaryFileId: String = UUID.randomUUID().toString(),
    @SerializedName("primary") val primary: Boolean = true,
    @SerializedName("secondary") val secondary: Boolean = false,
    @SerializedName("scannings") val scannings: List<Scanning>,
    @SerializedName("accessPointMeasurementIds") val accessPointMeasurementIds: List<String>,
    @SerializedName("channelWaitTime") val channelWaitTime: Int = 0,
    @SerializedName("technologies") val technologies: List<String> = emptyList()
)

data class Scanning(val startTime: Long, val endTime: Long)
data class PingSession(
    val requestedAddress: String = "localhost",
    val resolvedAddressVersion: String = "IPV4",
    val resolvedAddress: String = "127.0.0.1",
    val pings: List<String> = emptyList()
)

// ----------- Файл: images.json -----------
data class EsxImagesWrapper(val images: List<EsxImage>)
data class EsxImage(
    @SerializedName("id") val id: String,
    @SerializedName("status") val status: String = "CREATED",
    @SerializedName("imageFormat") val imageFormat: String,
    @SerializedName("resolutionWidth") val resolutionWidth: Double,
    @SerializedName("resolutionHeight") val resolutionHeight: Double
)

// ----------- Вспомогательные функции для времени -----------
private fun getCurrentUtcTime(): String {
    val sdf = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'", Locale.US)
    sdf.timeZone = TimeZone.getTimeZone("UTC")
    return sdf.format(Date())
}

private fun getCurrentUtcTimeForHistory(): String {
    val sdf = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSSZ", Locale.US)
    return sdf.format(Date())
}

// ----------- Файл: accessPoints.json -----------
data class EsxAccessPointsWrapper(val accessPoints: List<EsxAccessPoint>)

data class EsxAccessPoint(
    @SerializedName("id") val id: String = UUID.randomUUID().toString(),
    @SerializedName("status") val status: String = "CREATED",
    @SerializedName("name") val name: String,
    @SerializedName("mine") val mine: Boolean = false,
    @SerializedName("hidden") val hidden: Boolean = false,
    @SerializedName("userDefinedPosition") val userDefinedPosition: Boolean = false,
    @SerializedName("noteIds") val noteIds: List<String> = emptyList(),
    @SerializedName("tags") val tags: List<String> = emptyList()
)

// ----------- Файл: measuredRadios.json -----------
data class EsxMeasuredRadiosWrapper(val measuredRadios: List<EsxMeasuredRadio>)

data class EsxMeasuredRadio(
    @SerializedName("id") val id: String = UUID.randomUUID().toString(),
    @SerializedName("status") val status: String = "CREATED",
    @SerializedName("accessPointId") val accessPointId: String, // Ссылка на ID из accessPoints.json
    @SerializedName("accessPointMeasurementIds") val accessPointMeasurementIds: List<String>
)

// ----------- Файл: notes.json -----------
data class EsxNotesWrapper(val notes: List<EsxNote>)

data class EsxNote(
    @SerializedName("id") val id: String = UUID.randomUUID().toString(),
    @SerializedName("status") val status: String = "CREATED",
    @SerializedName("text") val text: String,
    @SerializedName("imageIds") val imageIds: List<String>, // Список ID картинок из images.json
    @SerializedName("history") val history: EsxSurveyHistory
)

// ----------- Файл: pictureNotes.json -----------
data class EsxPictureNotesWrapper(val pictureNotes: List<EsxPictureNote>)

data class EsxPictureNote(
    @SerializedName("id") val id: String = UUID.randomUUID().toString(),
    @SerializedName("status") val status: String = "CREATED",
    @SerializedName("location") val location: EsxNoteLocation,
    @SerializedName("noteIds") val noteIds: List<String> // Ссылка на ID из notes.json
)

data class EsxNoteLocation(
    @SerializedName("floorPlanId") val floorPlanId: String,
    @SerializedName("coord") val coord: Location
)
