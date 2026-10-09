package com.example.wiprober

import com.google.gson.Gson
import com.google.gson.JsonArray
import com.google.gson.JsonElement
import com.google.gson.JsonObject
import java.io.File

/** Merges WiProber additions over an immutable source ESX without decoding opaque payloads. */
internal class EsxArchiveOverlayWriter(
    private val gson: Gson
) {
    fun write(
        sourceArchive: File,
        destinationArchive: File,
        snapshot: ProjectExportSnapshot,
        report: EkahauReport,
        adapterInformation: EsxWifiAdapterInformation,
        additionalPayloads: Map<String, File>
    ) {
        require(sourceArchive.isFile) { "The imported ESX source archive is missing" }
        EsxReportValidator.validate(report, adapterInformation)
        try {
            EsxArchiveReader(sourceArchive).use { source ->
                snapshot.sourceCompatibility?.let { expected ->
                    require(source.compatibilityInventory() == expected) {
                        "The imported ESX source archive no longer matches its stored inventory"
                    }
                }
                val originalNames = source.entries.map(EsxArchiveEntry::name).toSet()
                val replacements = buildJsonReplacements(source, snapshot, report, adapterInformation)
                val additions = buildGeneratedEntries(report, additionalPayloads)
                require(additions.keys.none(originalNames::contains)) {
                    "A generated ESX payload collides with the source archive"
                }
                require(additions.keys.intersect(replacements.keys).isEmpty()) {
                    "A generated ESX payload collides with a patched catalogue"
                }
                writeMergedArchive(source, destinationArchive, replacements, additions)
            }
        } catch (error: Throwable) {
            destinationArchive.delete()
            throw error
        }
    }

    private fun buildJsonReplacements(
        source: EsxArchiveReader,
        snapshot: ProjectExportSnapshot,
        report: EkahauReport,
        adapterInformation: EsxWifiAdapterInformation
    ): Map<String, ByteArray> {
        val replacements = linkedMapOf<String, ByteArray>()

        val generatedFloorPlans = report.floorPlans.floorPlans.associateBy(EsxFloorPlan::id)
        val floorRoot = readJsonRoot(source, FLOOR_PLANS_FILE, FLOOR_PLANS_KEY)
        val floorArray = EsxJsonCatalog.wrapperArray(floorRoot, FLOOR_PLANS_KEY)
        var floorPlansChanged = false
        snapshot.floors.forEach { floor ->
            val generated = requireNotNull(generatedFloorPlans[floor.esxFloorPlanId]) {
                "Generated floor-plan mapping is inconsistent"
            }
            if (floor.importedFromSource) {
                val original = floorArray.objects().firstOrNull { it.string("id") == floor.esxFloorPlanId }
                    ?: throw IllegalArgumentException("A source floor plan is missing from floorPlans.json")
                val currentScale = floor.survey.metersPerUnit ?: generated.metersPerUnit
                if (!sameScale(currentScale, floor.sourceMetersPerUnit)) {
                    original.addProperty("metersPerUnit", currentScale)
                    floorPlansChanged = true
                }
            } else {
                appendUnique(floorArray, gson.toJsonTree(generated).asJsonObject, FLOOR_PLANS_KEY)
                floorPlansChanged = true
            }
        }
        if (floorPlansChanged) replacements[FLOOR_PLANS_FILE] = jsonBytes(floorRoot)

        val newImageIds = snapshot.floors.filterNot(FloorExportSnapshot::importedFromSource)
            .map(FloorExportSnapshot::esxImageId)
            .toSet()
        val localPhotoIds = snapshot.floors.flatMap { floor ->
            floor.survey.notes.filterNot(AppNote::importedFromEsx).mapNotNull(AppNote::photoId)
        }.toSet()
        val images = report.images.images.filter { it.id in newImageIds || it.id in localPhotoIds }
        appendWrapperIfNotEmpty(source, replacements, IMAGES_FILE, IMAGES_KEY, images)
        appendWrapperIfNotEmpty(
            source,
            replacements,
            ACCESS_POINT_MEASUREMENTS_FILE,
            ACCESS_POINT_MEASUREMENTS_KEY,
            report.accessPointMeasurements.accessPointMeasurements
        )
        appendWrapperIfNotEmpty(
            source,
            replacements,
            SURVEY_LOOKUPS_FILE,
            SURVEY_LOOKUPS_KEY,
            report.surveyLookups.surveyLookups
        )
        appendWrapperIfNotEmpty(
            source,
            replacements,
            ACCESS_POINTS_FILE,
            ACCESS_POINTS_KEY,
            report.accessPoints.accessPoints
        )
        appendWrapperIfNotEmpty(
            source,
            replacements,
            MEASURED_RADIOS_FILE,
            MEASURED_RADIOS_KEY,
            report.measuredRadios.measuredRadios
        )
        appendWrapperIfNotEmpty(source, replacements, NOTES_FILE, NOTES_KEY, report.notes.notes)
        appendWrapperIfNotEmpty(
            source,
            replacements,
            PICTURE_NOTES_FILE,
            PICTURE_NOTES_KEY,
            report.pictureNotes.pictureNotes
        )

        if (report.binaryData.isNotEmpty()) {
            val root = replacements[ADAPTERS_FILE]?.let(::parseJsonBytes)
                ?: readJsonRoot(source, ADAPTERS_FILE, ADAPTERS_KEY)
            val array = EsxJsonCatalog.wrapperArray(root, ADAPTERS_KEY)
            if (array.objects().none { it.string("id") == adapterInformation.id }) {
                appendUnique(array, gson.toJsonTree(adapterInformation).asJsonObject, ADAPTERS_KEY)
                replacements[ADAPTERS_FILE] = jsonBytes(root)
            }
        }
        return replacements
    }

    private fun <T> appendWrapperIfNotEmpty(
        source: EsxArchiveReader,
        replacements: MutableMap<String, ByteArray>,
        fileName: String,
        key: String,
        values: List<T>
    ) {
        if (values.isEmpty()) return
        val root = replacements[fileName]?.let(::parseJsonBytes) ?: readJsonRoot(source, fileName, key)
        val array = EsxJsonCatalog.wrapperArray(root, key)
        values.forEach { value -> appendUnique(array, gson.toJsonTree(value).asJsonObject, key) }
        replacements[fileName] = jsonBytes(root)
    }

    private fun buildGeneratedEntries(
        report: EkahauReport,
        additionalPayloads: Map<String, File>
    ): Map<String, EntryContent> {
        val entries = linkedMapOf<String, EntryContent>()
        report.surveys.forEach { (surveyId, survey) ->
            putUnique(entries, "survey-$surveyId.json", EntryContent.Bytes(jsonBytes(gson.toJsonTree(survey))))
        }
        report.binaryData.forEach { (binaryId, bytes) ->
            putUnique(entries, "track-$binaryId.bin", EntryContent.Bytes(bytes))
        }
        additionalPayloads.forEach { (name, file) ->
            require(file.isFile) { "A generated ESX payload is missing" }
            putUnique(entries, name, EntryContent.FilePayload(file))
        }
        return entries
    }

    private fun writeMergedArchive(
        source: EsxArchiveReader,
        destination: File,
        replacements: Map<String, ByteArray>,
        additions: Map<String, EntryContent>
    ) {
        EsxArchiveWriter(destination).use { output ->
            source.entries.forEach { original ->
                if (original.isDirectory) {
                    output.putDirectory(original.name)
                } else {
                    val replacement = replacements[original.name]
                    if (replacement != null) {
                        output.putBytes(original.name, replacement)
                    } else {
                        output.putCopied(original.name) { destinationStream ->
                            source.copy(original, destinationStream)
                        }
                    }
                }
            }
            val originalNames = source.entries.map(EsxArchiveEntry::name).toSet()
            replacements.filterKeys { it !in originalNames }.forEach { (name, bytes) ->
                output.putBytes(name, bytes)
            }
            additions.forEach { (name, content) ->
                when (content) {
                    is EntryContent.Bytes -> output.putBytes(name, content.bytes)
                    is EntryContent.FilePayload -> output.putFile(name, content.file)
                }
            }
        }
    }

    private fun readJsonRoot(source: EsxArchiveReader, fileName: String, key: String): JsonElement =
        source.jsonRoot(fileName) ?: JsonObject().apply { add(key, JsonArray()) }

    private fun appendUnique(array: JsonArray, value: JsonObject, catalogue: String) {
        val id = value.string("id")
        if (id != null) {
            require(array.objects().none { it.string("id") == id }) {
                "Generated ID collides with source catalogue $catalogue"
            }
        }
        array.add(value)
    }

    private fun sameScale(first: Double?, second: Double?): Boolean {
        if (first == null || second == null) return first == second
        return kotlin.math.abs(first - second) <= SCALE_EPSILON
    }

    private fun parseJsonBytes(bytes: ByteArray): JsonElement = BoundedJson.parse(
        bytes.inputStream(),
        bytes.size.toLong()
    )

    private fun jsonBytes(value: JsonElement): ByteArray = gson.toJson(value).toByteArray(Charsets.UTF_8).also {
        require(it.size.toLong() <= DEFAULT_ESX_LIMITS.maxJsonBytes) { "The ESX JSON payload is too large" }
    }

    private fun <T : EntryContent> putUnique(
        target: MutableMap<String, EntryContent>,
        name: String,
        content: T
    ) {
        require(target.put(name, content) == null) { "The generated ESX contains duplicate entries" }
    }

    private fun JsonArray.objects(): List<JsonObject> =
        mapNotNull { it.takeIf(JsonElement::isJsonObject)?.asJsonObject }
    private fun JsonObject.string(name: String): String? =
        get(name)?.takeUnless(JsonElement::isJsonNull)?.runCatching { asString }?.getOrNull()

    private sealed interface EntryContent {
        data class Bytes(val bytes: ByteArray) : EntryContent
        data class FilePayload(val file: File) : EntryContent
    }

    private companion object {
        private const val FLOOR_PLANS_FILE = "floorPlans.json"
        private const val FLOOR_PLANS_KEY = "floorPlans"
        private const val IMAGES_FILE = "images.json"
        private const val IMAGES_KEY = "images"
        private const val ACCESS_POINT_MEASUREMENTS_FILE = "accessPointMeasurements.json"
        private const val ACCESS_POINT_MEASUREMENTS_KEY = "accessPointMeasurements"
        private const val SURVEY_LOOKUPS_FILE = "surveyLookups.json"
        private const val SURVEY_LOOKUPS_KEY = "surveyLookups"
        private const val ACCESS_POINTS_FILE = "accessPoints.json"
        private const val ACCESS_POINTS_KEY = "accessPoints"
        private const val MEASURED_RADIOS_FILE = "measuredRadios.json"
        private const val MEASURED_RADIOS_KEY = "measuredRadios"
        private const val NOTES_FILE = "notes.json"
        private const val NOTES_KEY = "notes"
        private const val PICTURE_NOTES_FILE = "pictureNotes.json"
        private const val PICTURE_NOTES_KEY = "pictureNotes"
        private const val ADAPTERS_FILE = "wifiAdapterInformations.json"
        private const val ADAPTERS_KEY = "wifiAdapterInformations"
        private const val SCALE_EPSILON = 1e-12
    }
}
