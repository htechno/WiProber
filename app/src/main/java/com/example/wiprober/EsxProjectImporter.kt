package com.example.wiprober

import com.google.gson.JsonArray
import com.google.gson.JsonElement
import com.google.gson.JsonObject
import java.io.File
import java.io.InputStream
import java.time.Instant
import java.time.OffsetDateTime

data class ImportedEsxProject(
    val projectName: String,
    val floorName: String,
    val sourceFloorPlanId: String,
    val sourceImageId: String,
    val mapFile: File,
    val mapInfo: MapInfo,
    val metersPerUnit: Double,
    val scanPoints: List<ScanPoint>,
    val continuousScanSessions: List<ContinuousScanSession>,
    val notes: List<ImportedEsxNote>,
    val sourceCompatibility: EsxSourceCompatibility? = null
)

data class ImportedEsxNote(
    val id: String,
    val text: String,
    val photoFile: File?,
    val photoWidth: Int?,
    val photoHeight: Int?,
    val photoId: String?,
    val x: Float,
    val y: Float,
    val pictureNoteId: String,
    val photoFormat: String?
)

/**
 * Builds the lightweight UI index of an ESX archive.
 *
 * Imported Wi-Fi and spectrum binaries are deliberately opaque. The importer never opens them:
 * the complete source archive is retained by [ProjectRepository] and merged back during export.
 */
class EsxProjectImporter(
    private val clock: MillisClock = SystemMillisClock,
    private val idSource: IdSource = UuidIdSource
) {
    fun import(sourceArchive: File, destinationDir: File): List<ImportedEsxProject> {
        require(sourceArchive.isFile) { "The selected ESX project is missing" }
        prepareDestination(destinationDir)

        EsxArchiveReader(sourceArchive).use { archive ->
            val sourceCompatibility = archive.compatibilityInventory()
            val floorPlans = archive.objects("floorPlans.json", "floorPlans")
            require(floorPlans.isNotEmpty()) { "The ESX project does not contain floor plans" }

            val project = archive.objects("project.json", "project").firstOrNull()
            val projectName = project?.string("title") ?: project?.string("name") ?: "Ekahau project"
            val lookups = archive.objects("surveyLookups.json", "surveyLookups")
            val notesById = archive.objects("notes.json", "notes")
                .associateBy { it.string("id").orEmpty() }
            val pictures = archive.objects("pictureNotes.json", "pictureNotes")
            val images = archive.objects("images.json", "images")
                .associateBy { it.string("id").orEmpty() }
            val surveyEntries = archive.entries.asSequence()
                .filterNot(EsxArchiveEntry::isDirectory)
                .filter { File(it.name).name.startsWith("survey-") && it.name.endsWith(".json", true) }
                .toList()
            val surveyGeometry = surveyEntries.asSequence()
                .flatMap { entry -> archive.objects(entry, "surveys").asSequence() }
                .map { survey ->
                    IndexedSurveyGeometry(
                        id = survey.string("id"),
                        floorPlanId = survey.string("floorPlanId"),
                        geometry = parseSurveyGeometry(survey)
                    )
                }
                .toList()

            return floorPlans.mapIndexed { floorIndex, floor ->
                val floorId = requireNotNull(floor.string("id")?.takeIf(String::isNotBlank)) {
                    "Floor plan ID is missing"
                }
                val width = floor.double("width") ?: floor.double("cropMaxX") ?: 0.0
                val height = floor.double("height") ?: floor.double("cropMaxY") ?: 0.0
                require(width.isFinite() && height.isFinite() && width > 0 && height > 0) {
                    "Invalid floor plan dimensions"
                }
                require(width <= MAX_MAP_DIMENSION && height <= MAX_MAP_DIMENSION) {
                    "The floor plan dimensions are too large"
                }
                val imageId = requireNotNull(floor.string("imageId")?.takeIf(String::isNotBlank)) {
                    "Floor plan image is missing"
                }
                val imageEntry = requireNotNull(archive.findPayload("image-$imageId")) {
                    "Floor plan image data is missing"
                }
                val imageFormat = images[imageId]?.string("imageFormat")?.lowercase() ?: "png"
                val mapFile = File(destinationDir, "floor-$floorIndex-map.${safeExtension(imageFormat)}")
                archive.extract(
                    imageEntry,
                    mapFile,
                    DEFAULT_ESX_LIMITS.maxMapBytes,
                    "The floor plan is too large"
                )
                val floorName = floor.string("name") ?: "Floor plan"
                val surveyIds = lookups
                    .filter { it.string("floorPlanId") == floorId }
                    .mapNotNull { it.string("surveyId") }
                    .toSet()
                val importedSurveys = surveyGeometry.asSequence()
                    .filter { survey ->
                        if (surveyIds.isNotEmpty()) survey.id in surveyIds else survey.floorPlanId == floorId
                    }
                    .map(IndexedSurveyGeometry::geometry)
                    .toList()
                val importedNotes = pictures
                    .filter { it.obj("location")?.string("floorPlanId") == floorId }
                    .mapNotNull { picture ->
                        parseNote(picture, notesById, images, archive, destinationDir)
                    }

                ImportedEsxProject(
                    projectName = projectName,
                    floorName = floorName,
                    sourceFloorPlanId = floorId,
                    sourceImageId = imageId,
                    mapFile = mapFile,
                    mapInfo = MapInfo(
                        "$floorName.${safeExtension(imageFormat)}",
                        width.toInt(),
                        height.toInt()
                    ),
                    metersPerUnit = floor.double("metersPerUnit")
                        ?.takeIf { it.isFinite() && it > 0 }
                        ?: DEFAULT_METERS_PER_UNIT,
                    scanPoints = importedSurveys.mapNotNull(ImportedSurveyGeometry::scanPoint),
                    continuousScanSessions = importedSurveys.mapNotNull(ImportedSurveyGeometry::session),
                    notes = importedNotes,
                    sourceCompatibility = sourceCompatibility
                )
            }
        }
    }

    /** Convenience overload retained for small in-memory tests. Production uses the File API. */
    fun import(input: InputStream, destinationDir: File): List<ImportedEsxProject> {
        require(!destinationDir.exists() || destinationDir.list().isNullOrEmpty()) {
            "The ESX extraction directory is not empty"
        }
        require(destinationDir.mkdirs() || destinationDir.isDirectory) {
            "Cannot create the ESX extraction directory"
        }
        val sourceArchive = File(destinationDir, TEST_SOURCE_FILE)
        BoundedIo.copyToFile(
            input,
            sourceArchive,
            DEFAULT_ESX_LIMITS.maxSourceBytes,
            "The ESX project is too large"
        )
        return import(sourceArchive, destinationDir)
    }

    private fun parseSurveyGeometry(survey: JsonObject): ImportedSurveyGeometry {
        val timestamp = parseTime(survey.string("startTime"))
        val routePoints = survey.array("routePoints").flattenObjects().mapNotNull { point ->
            val location = point.obj("location") ?: return@mapNotNull null
            RoutePointWrapper(
                timestamp = (point.long("time") ?: 0L) / 1_000_000L,
                x = (location.double("x") ?: return@mapNotNull null).toFloat(),
                y = (location.double("y") ?: return@mapNotNull null).toFloat()
            )
        }
        val routeType = survey.string("routeType").orEmpty()
        val isContinuous = routeType.equals("CONTINUOUS", true) ||
            routePoints.distinctBy { it.x to it.y }.size > 1

        if (!isContinuous) {
            val point = routePoints.firstOrNull() ?: return ImportedSurveyGeometry(null, null)
            return ImportedSurveyGeometry(
                scanPoint = ScanPoint(
                    timestamp = timestamp,
                    x = point.x,
                    y = point.y,
                    wifiNetworks = emptyList(),
                    importedFromEsx = true
                ),
                session = null
            )
        }

        val duration = survey.long("duration")?.div(1_000_000L)
            ?: routePoints.maxOfOrNull(RoutePointWrapper::timestamp)
            ?: 0L
        return ImportedSurveyGeometry(
            scanPoint = null,
            session = ContinuousScanSession(
                id = survey.string("id") ?: idSource.newId(),
                startTime = timestamp,
                endTime = timestamp + duration,
                waypoints = routePoints.toList(),
                scanResults = emptyList(),
                importedFromEsx = true
            )
        )
    }

    private fun parseNote(
        picture: JsonObject,
        notes: Map<String, JsonObject>,
        images: Map<String, JsonObject>,
        archive: EsxArchiveReader,
        destinationDir: File
    ): ImportedEsxNote? {
        val noteId = picture.array("noteIds").firstOrNull()?.asStringOrNull() ?: return null
        val note = notes[noteId] ?: return null
        val coord = picture.obj("location")?.obj("coord") ?: return null
        val photoId = note.array("imageIds").firstOrNull()?.asStringOrNull()
        val image = photoId?.let(images::get)
        val photoFile = photoId?.let { id ->
            archive.findPayload("image-$id")?.let { entry ->
                File(destinationDir, "note-image-$id").also { destination ->
                    archive.extract(
                        entry,
                        destination,
                        DEFAULT_ESX_LIMITS.maxPhotoBytes,
                        "A note photo is too large"
                    )
                }
            }
        }
        return ImportedEsxNote(
            id = noteId,
            text = note.string("text").orEmpty(),
            photoFile = photoFile,
            photoWidth = image?.double("resolutionWidth")?.toInt(),
            photoHeight = image?.double("resolutionHeight")?.toInt(),
            photoId = photoId,
            x = (coord.double("x") ?: return null).toFloat(),
            y = (coord.double("y") ?: return null).toFloat(),
            pictureNoteId = picture.string("id") ?: idSource.newId(),
            photoFormat = image?.string("imageFormat")?.uppercase()
        )
    }

    private fun prepareDestination(destinationDir: File) {
        val unexpected = destinationDir.listFiles().orEmpty().filter { it.name != TEST_SOURCE_FILE }
        require(unexpected.isEmpty()) { "The ESX extraction directory is not empty" }
        require(destinationDir.mkdirs() || destinationDir.isDirectory) {
            "Cannot create the ESX extraction directory"
        }
    }

    private fun safeExtension(value: String): String = value
        .lowercase()
        .filter(Char::isLetterOrDigit)
        .take(8)
        .ifBlank { "png" }

    private fun parseTime(value: String?): Long {
        if (value == null) return clock.now()
        return runCatching { Instant.parse(value).toEpochMilli() }
            .recoverCatching { OffsetDateTime.parse(value).toInstant().toEpochMilli() }
            .getOrElse { clock.now() }
    }

    private data class ImportedSurveyGeometry(
        val scanPoint: ScanPoint?,
        val session: ContinuousScanSession?
    )

    private data class IndexedSurveyGeometry(
        val id: String?,
        val floorPlanId: String?,
        val geometry: ImportedSurveyGeometry
    )

    private fun JsonObject.string(name: String): String? = get(name)?.asStringOrNull()
    private fun JsonObject.double(name: String): Double? =
        get(name)?.takeUnless(JsonElement::isJsonNull)?.runCatching { asDouble }?.getOrNull()
    private fun JsonObject.long(name: String): Long? =
        get(name)?.takeUnless(JsonElement::isJsonNull)?.runCatching { asLong }?.getOrNull()
    private fun JsonObject.obj(name: String): JsonObject? = get(name)?.takeIf(JsonElement::isJsonObject)?.asJsonObject
    private fun JsonObject.array(name: String): JsonArray =
        get(name)?.takeIf(JsonElement::isJsonArray)?.asJsonArray ?: JsonArray()
    private fun JsonElement.asStringOrNull(): String? =
        takeUnless(JsonElement::isJsonNull)?.runCatching { asString }?.getOrNull()
    private fun JsonArray.flattenObjects(): List<JsonObject> = flatMap {
        when {
            it.isJsonObject -> listOf(it.asJsonObject)
            it.isJsonArray -> it.asJsonArray.flattenObjects()
            else -> emptyList()
        }
    }

    companion object {
        private const val TEST_SOURCE_FILE = ".source.esx"
        private const val DEFAULT_METERS_PER_UNIT = 0.025
        private const val MAX_MAP_DIMENSION = 100_000.0
    }
}
