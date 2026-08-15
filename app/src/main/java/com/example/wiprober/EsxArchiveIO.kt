package com.example.wiprober

import com.google.gson.JsonArray
import com.google.gson.JsonElement
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import com.google.gson.stream.JsonReader
import com.google.gson.stream.JsonToken
import java.io.Closeable
import java.io.File
import java.io.InputStream
import java.io.OutputStream
import kotlin.math.min
import java.util.zip.ZipEntry
import java.util.zip.ZipFile
import java.util.zip.ZipOutputStream

internal data class EsxArchiveLimits(
    val maxSourceBytes: Long = 350L * 1024L * 1024L,
    val maxEntries: Int = 4_096,
    val maxEntryNameChars: Int = 512,
    val maxUncompressedBytes: Long = 1_024L * 1024L * 1024L,
    val maxEntryBytes: Long = 350L * 1024L * 1024L,
    val maxJsonBytes: Long = 64L * 1024L * 1024L,
    val maxMapBytes: Long = 150L * 1024L * 1024L,
    val maxPhotoBytes: Long = 50L * 1024L * 1024L,
    val maxJsonDepth: Int = 128,
    val maxJsonNodes: Int = 2_000_000
)

internal val DEFAULT_ESX_LIMITS = EsxArchiveLimits()

internal object BoundedJson {
    fun parse(
        input: InputStream,
        declaredSize: Long,
        limits: EsxArchiveLimits = DEFAULT_ESX_LIMITS
    ): JsonElement {
        val bytes = BoundedIo.readBytes(
            input,
            declaredSize,
            limits.maxJsonBytes,
            "The ESX JSON payload is too large"
        )
        preflight(bytes, limits)
        val root = bytes.inputStream().bufferedReader(Charsets.UTF_8).use(JsonParser::parseReader)
        return root
    }

    private fun preflight(bytes: ByteArray, limits: EsxArchiveLimits) {
        bytes.inputStream().bufferedReader(Charsets.UTF_8).use { characterInput ->
            JsonReader(characterInput).use { reader ->
                var depth = 0
                var nodes = 0
                fun addNode() {
                    nodes++
                    require(nodes <= limits.maxJsonNodes) {
                        "The ESX JSON payload contains too many values"
                    }
                }
                while (true) {
                    when (reader.peek()) {
                        JsonToken.BEGIN_ARRAY -> {
                            addNode()
                            depth++
                            require(depth <= limits.maxJsonDepth) { "The ESX JSON nesting is too deep" }
                            reader.beginArray()
                        }
                        JsonToken.BEGIN_OBJECT -> {
                            addNode()
                            depth++
                            require(depth <= limits.maxJsonDepth) { "The ESX JSON nesting is too deep" }
                            reader.beginObject()
                        }
                        JsonToken.END_ARRAY -> {
                            reader.endArray()
                            depth--
                        }
                        JsonToken.END_OBJECT -> {
                            reader.endObject()
                            depth--
                        }
                        JsonToken.NAME -> reader.nextName()
                        JsonToken.STRING, JsonToken.NUMBER -> {
                            addNode()
                            reader.nextString()
                        }
                        JsonToken.BOOLEAN -> {
                            addNode()
                            reader.nextBoolean()
                        }
                        JsonToken.NULL -> {
                            addNode()
                            reader.nextNull()
                        }
                        JsonToken.END_DOCUMENT -> return
                    }
                }
            }
        }
    }

}

internal data class EsxArchiveEntry(
    val name: String,
    val isDirectory: Boolean,
    val size: Long
)

enum class PreservedEsxEntityKind {
    OPAQUE_WIFI_TRACK,
    OPAQUE_SPECTRUM,
    UNINDEXED_JSON_CATALOGUE,
    UNKNOWN_PAYLOAD
}

data class PreservedEsxEntity(
    val entryName: String,
    val kind: PreservedEsxEntityKind
)

data class EsxSourceCompatibility(
    val contractVersion: Int = 1,
    val sourceEntryCount: Int,
    val sourceUncompressedBytes: Long,
    val recordedUnsupportedEntities: List<PreservedEsxEntity>,
    val additionalUnsupportedEntityCount: Int,
    val opaqueWifiTrackCount: Int,
    val opaqueSpectrumCount: Int,
    val unindexedJsonCatalogueCount: Int,
    val unknownPayloadCount: Int,
    val archiveFormatVersion: String? = null,
    val projectSchemaVersion: String? = null
)

internal fun EsxSourceCompatibility.validate(
    limits: EsxArchiveLimits = DEFAULT_ESX_LIMITS
): EsxSourceCompatibility {
    require(contractVersion == 1) { "Unsupported ESX compatibility inventory version" }
    require(sourceEntryCount in 1..limits.maxEntries) { "Invalid ESX source entry count" }
    require(sourceUncompressedBytes in 0..limits.maxUncompressedBytes) {
        "Invalid ESX source size"
    }
    val categoryCounts = listOf(
        opaqueWifiTrackCount,
        opaqueSpectrumCount,
        unindexedJsonCatalogueCount,
        unknownPayloadCount
    )
    require(additionalUnsupportedEntityCount in 0..limits.maxEntries &&
        categoryCounts.all { it in 0..limits.maxEntries }) {
        "Invalid ESX compatibility inventory"
    }
    require(categoryCounts.sum() == recordedUnsupportedEntities.size + additionalUnsupportedEntityCount) {
        "Inconsistent ESX compatibility inventory"
    }
    require(recordedUnsupportedEntities.map(PreservedEsxEntity::entryName).distinct().size ==
        recordedUnsupportedEntities.size) { "Duplicate ESX compatibility entries" }
    require(recordedUnsupportedEntities.size <= 128 &&
        recordedUnsupportedEntities.sumOf { it.entryName.length } <= 32_768) {
        "ESX compatibility inventory is too large"
    }
    recordedUnsupportedEntities.forEach { validateEsxEntryName(it.entryName, limits) }
    require(archiveFormatVersion == null || archiveFormatVersion.length <= 64) {
        "Invalid ESX archive format version"
    }
    require(projectSchemaVersion == null || projectSchemaVersion.length <= 64) {
        "Invalid ESX project schema version"
    }
    return this
}

/** Validated, bounded read-only view of one ESX ZIP archive. */
internal class EsxArchiveReader(
    sourceArchive: File,
    private val limits: EsxArchiveLimits = DEFAULT_ESX_LIMITS
) : Closeable {
    private val zip: ZipFile
    private val zipEntries: List<ZipEntry>
    val entries: List<EsxArchiveEntry>

    init {
        require(sourceArchive.isFile && sourceArchive.length() <= limits.maxSourceBytes) {
            "The selected ESX project is missing or too large"
        }
        zip = ZipFile(sourceArchive)
        try {
            val collected = ArrayList<ZipEntry>()
            val enumeration = zip.entries()
            while (enumeration.hasMoreElements()) {
                require(collected.size < limits.maxEntries) { "The ESX project contains too many files" }
                collected += enumeration.nextElement()
            }
            zipEntries = collected
            validateEntries(zipEntries)
            entries = zipEntries.map { EsxArchiveEntry(it.name, it.isDirectory, it.size) }
        } catch (error: Throwable) {
            zip.close()
            throw error
        }
    }

    fun entry(name: String): EsxArchiveEntry? = entries.firstOrNull { it.name == name }

    fun jsonRoot(fileName: String): JsonElement? {
        val entry = requireZipEntry(fileName) ?: return null
        require(!entry.isDirectory) { "Invalid ESX JSON entry" }
        return zip.getInputStream(entry).use { BoundedJson.parse(it, entry.size, limits) }
    }

    fun objects(fileName: String, wrapperKey: String): List<JsonObject> {
        val root = jsonRoot(fileName) ?: return emptyList()
        return EsxJsonCatalog.objects(root, wrapperKey)
    }

    fun objects(entry: EsxArchiveEntry, wrapperKey: String): List<JsonObject> {
        val zipEntry = requireNotNull(requireZipEntry(entry.name))
        val root = zip.getInputStream(zipEntry).use { BoundedJson.parse(it, zipEntry.size, limits) }
        return EsxJsonCatalog.objects(root, wrapperKey)
    }

    fun findPayload(payloadName: String): EsxArchiveEntry? {
        val matches = entries.filterNot(EsxArchiveEntry::isDirectory).filter { entry ->
            val baseName = File(entry.name).name
            baseName == payloadName || baseName.substringBeforeLast('.') == payloadName
        }
        require(matches.size <= 1) { "The ESX project contains ambiguous payload names" }
        return matches.singleOrNull()
    }

    fun extract(entry: EsxArchiveEntry, destination: File, maxBytes: Long, errorMessage: String) {
        require(entry.size in 0..maxBytes) { errorMessage }
        val zipEntry = requireNotNull(requireZipEntry(entry.name))
        zip.getInputStream(zipEntry).use {
            BoundedIo.copyToFile(it, destination, maxBytes, errorMessage)
        }
    }

    fun copy(entry: EsxArchiveEntry, output: OutputStream, maxBytes: Long = limits.maxEntryBytes): Long {
        require(!entry.isDirectory && entry.size in 0..maxBytes) { "An ESX project file is too large" }
        val zipEntry = requireNotNull(requireZipEntry(entry.name))
        return zip.getInputStream(zipEntry).use {
            BoundedIo.copy(it, output, maxBytes, "An ESX project file is too large")
        }
    }

    fun compatibilityInventory(): EsxSourceCompatibility {
        val unsupported = entries.asSequence()
            .filterNot(EsxArchiveEntry::isDirectory)
            .filterNot { isUiIndexedEntry(it.name) }
            .map { PreservedEsxEntity(it.name, classifyUnsupported(it.name)) }
            .toList()
        val recorded = mutableListOf<PreservedEsxEntity>()
        var recordedNameChars = 0
        unsupported.forEach { entity ->
            if (recorded.size < MAX_RECORDED_UNSUPPORTED_ENTITIES &&
                recordedNameChars + entity.entryName.length <= MAX_RECORDED_ENTRY_NAME_CHARS
            ) {
                recorded += entity
                recordedNameChars += entity.entryName.length
            }
        }
        return EsxSourceCompatibility(
            sourceEntryCount = entries.count { !it.isDirectory },
            sourceUncompressedBytes = entries.filterNot(EsxArchiveEntry::isDirectory).sumOf(EsxArchiveEntry::size),
            recordedUnsupportedEntities = recorded,
            additionalUnsupportedEntityCount = unsupported.size - recorded.size,
            opaqueWifiTrackCount = unsupported.count { it.kind == PreservedEsxEntityKind.OPAQUE_WIFI_TRACK },
            opaqueSpectrumCount = unsupported.count { it.kind == PreservedEsxEntityKind.OPAQUE_SPECTRUM },
            unindexedJsonCatalogueCount = unsupported.count {
                it.kind == PreservedEsxEntityKind.UNINDEXED_JSON_CATALOGUE
            },
            unknownPayloadCount = unsupported.count { it.kind == PreservedEsxEntityKind.UNKNOWN_PAYLOAD },
            archiveFormatVersion = readSmallText("version"),
            projectSchemaVersion = objects("project.json", "project").firstOrNull()
                ?.get("schemaVersion")
                ?.takeUnless(JsonElement::isJsonNull)
                ?.runCatching { asString }
                ?.getOrNull()
        )
    }

    override fun close() = zip.close()

    private fun validateEntries(sourceEntries: List<ZipEntry>) {
        var fileCount = 0
        var totalBytes = 0L
        val names = mutableSetOf<String>()
        sourceEntries.forEach { entry ->
            validateEsxEntryName(entry.name, limits)
            require(names.add(entry.name)) { "The ESX project contains duplicate file names" }
            if (!entry.isDirectory) {
                fileCount++
                require(fileCount <= limits.maxEntries) { "The ESX project contains too many files" }
                require(entry.method == ZipEntry.STORED || entry.method == ZipEntry.DEFLATED) {
                    "The ESX project uses an unsupported ZIP compression method"
                }
                require(entry.size in 0..limits.maxEntryBytes) { "An ESX project file is too large" }
                totalBytes += entry.size
                require(totalBytes <= limits.maxUncompressedBytes) { "The ESX project is too large" }
                if (entry.name.endsWith(".json", true)) {
                    require(entry.size <= limits.maxJsonBytes) { "The ESX JSON payload is too large" }
                }
            }
        }
        require(requireZipEntry(FLOOR_PLANS_FILE) != null) { "The selected file is not a valid ESX project" }
    }

    private fun requireZipEntry(name: String): ZipEntry? = zip.getEntry(name)

    private fun readSmallText(name: String): String? {
        val entry = requireZipEntry(name) ?: return null
        if (entry.isDirectory) return null
        val bytes = zip.getInputStream(entry).use {
            BoundedIo.readBytes(it, entry.size, MAX_VERSION_BYTES, "Invalid ESX archive version")
        }
        return bytes.toString(Charsets.UTF_8).trim().takeIf(String::isNotBlank)
    }

    private fun isUiIndexedEntry(name: String): Boolean = name in UI_INDEXED_CATALOGUES ||
        (File(name).name.startsWith("survey-") && name.endsWith(".json", true)) ||
        File(name).name.startsWith("image-")

    private fun classifyUnsupported(name: String): PreservedEsxEntityKind {
        val leaf = File(name).name.lowercase()
        return when {
            leaf.startsWith("track-") && leaf.endsWith(".bin") ->
                PreservedEsxEntityKind.OPAQUE_WIFI_TRACK
            leaf.contains("spectrum") || leaf.startsWith("spectral-") ->
                PreservedEsxEntityKind.OPAQUE_SPECTRUM
            leaf.endsWith(".json") -> PreservedEsxEntityKind.UNINDEXED_JSON_CATALOGUE
            else -> PreservedEsxEntityKind.UNKNOWN_PAYLOAD
        }
    }

    companion object {
        private const val FLOOR_PLANS_FILE = "floorPlans.json"
        private const val MAX_RECORDED_UNSUPPORTED_ENTITIES = 128
        private const val MAX_RECORDED_ENTRY_NAME_CHARS = 32_768
        private const val MAX_VERSION_BYTES = 256L
        private val UI_INDEXED_CATALOGUES = setOf(
            "project.json",
            FLOOR_PLANS_FILE,
            "surveyLookups.json",
            "notes.json",
            "pictureNotes.json",
            "images.json",
            "version"
        )
    }
}

/** Bounded ZIP writer shared by full generation and imported-project overlay. */
internal class EsxArchiveWriter(
    destination: File,
    private val limits: EsxArchiveLimits = DEFAULT_ESX_LIMITS
) : Closeable {
    private val zip = ZipOutputStream(destination.outputStream().buffered())
    private val written = mutableSetOf<String>()
    private var fileCount = 0
    private var totalBytes = 0L

    fun putDirectory(name: String) {
        validateNewName(name)
        fileCount++
        require(fileCount <= limits.maxEntries) { "The generated ESX contains too many files" }
        zip.putNextEntry(ZipEntry(name))
        zip.closeEntry()
    }

    fun putBytes(name: String, bytes: ByteArray, maxBytes: Long = limits.maxEntryBytes) {
        val limit = contentLimit(name, maxBytes)
        require(bytes.size.toLong() <= limit) { "A generated ESX payload is too large" }
        putStream(name, limit) { bytes.inputStream() }
    }

    fun putFile(name: String, file: File, maxBytes: Long = limits.maxEntryBytes) {
        val limit = contentLimit(name, maxBytes)
        require(file.isFile && file.length() <= limit) { "A generated ESX payload is missing or too large" }
        putStream(name, limit, file::inputStream)
    }

    fun putStream(name: String, maxBytes: Long, input: () -> InputStream) {
        val limit = contentLimit(name, maxBytes)
        putCopied(name, limit) { output ->
            input().use { BoundedIo.copy(it, output, limit, "A generated ESX payload is too large") }
        }
    }

    fun putCopied(name: String, maxBytes: Long = limits.maxEntryBytes, copy: (OutputStream) -> Long) {
        val limit = contentLimit(name, maxBytes)
        validateNewName(name)
        fileCount++
        require(fileCount <= limits.maxEntries) { "The generated ESX contains too many files" }
        zip.putNextEntry(ZipEntry(name))
        val copied = try {
            copy(zip)
        } finally {
            zip.closeEntry()
        }
        require(copied in 0..limit) { "A generated ESX payload is too large" }
        totalBytes += copied
        require(totalBytes <= limits.maxUncompressedBytes) { "The generated ESX project is too large" }
    }

    override fun close() = zip.close()

    private fun validateNewName(name: String) {
        validateEsxEntryName(name, limits)
        require(written.add(name)) { "The generated ESX contains duplicate entries" }
    }

    private fun contentLimit(name: String, requested: Long): Long =
        if (name.endsWith(".json", true)) min(requested, limits.maxJsonBytes) else requested
}

internal object EsxJsonCatalog {
    fun objects(root: JsonElement, wrapperKey: String): List<JsonObject> = when {
        root.isJsonArray -> root.asJsonArray.objects()
        root.isJsonObject -> {
            val value = root.asJsonObject.get(wrapperKey)
            when {
                value?.isJsonArray == true -> value.asJsonArray.objects()
                value?.isJsonObject == true -> listOf(value.asJsonObject)
                else -> listOf(root.asJsonObject)
            }
        }
        else -> emptyList()
    }

    fun wrapperArray(root: JsonElement, key: String): JsonArray = when {
        root.isJsonArray -> root.asJsonArray
        root.isJsonObject -> {
            val objectRoot = root.asJsonObject
            when (val existing = objectRoot.get(key)) {
                null -> JsonArray().also { objectRoot.add(key, it) }
                else -> when {
                    existing.isJsonNull -> JsonArray().also { objectRoot.add(key, it) }
                    existing.isJsonArray -> existing.asJsonArray
                    existing.isJsonObject -> JsonArray().also {
                        it.add(existing.asJsonObject)
                        objectRoot.add(key, it)
                    }
                    else -> throw IllegalArgumentException("Invalid ESX catalogue: $key")
                }
            }
        }
        else -> throw IllegalArgumentException("Invalid ESX catalogue: $key")
    }

    private fun JsonArray.objects(): List<JsonObject> =
        mapNotNull { it.takeIf(JsonElement::isJsonObject)?.asJsonObject }
}

internal fun validateEsxEntryName(name: String, limits: EsxArchiveLimits = DEFAULT_ESX_LIMITS) {
    require(name.isNotBlank() && name.length <= limits.maxEntryNameChars) { "Invalid ESX archive entry" }
    require(!name.startsWith('/') && !name.startsWith('\\')) { "Invalid ESX archive entry" }
    require(name.split('/', '\\').none { it == ".." }) { "Invalid ESX archive entry" }
    require(!name.contains('\u0000')) { "Invalid ESX archive entry" }
}
