package com.example.wiprober

import android.content.Context
import android.net.Uri
import android.os.Build
import android.provider.Settings
import com.google.gson.Gson
import com.google.gson.GsonBuilder
import java.io.File

/** Writes an immutable, whole-project snapshot to an ESX archive. */
class EsxExportService(
    context: Context,
    private val reportBuilder: EkahauReportBuilder = EkahauReportBuilder(),
    private val gson: Gson = GsonBuilder().setPrettyPrinting().create()
) {
    private val appContext = context.applicationContext
    private val contentResolver = appContext.contentResolver

    fun export(destinationUri: Uri, snapshot: ProjectExportSnapshot) {
        val exportDirectory = File(appContext.cacheDir, "esx-export-${System.nanoTime()}")
        val completeArchive = File(appContext.cacheDir, "${exportDirectory.name}.esx")
        require(exportDirectory.mkdirs()) { "Cannot create the ESX export workspace" }

        try {
            val sourceArchive = snapshot.sourceArchive
            when {
                sourceArchive != null && !hasSourceOverlayChanges(snapshot) -> {
                    validateSourceInventory(sourceArchive, snapshot.sourceCompatibility)
                    sourceArchive.inputStream().use { input ->
                        BoundedIo.copyToFile(
                            input,
                            completeArchive,
                            DEFAULT_ESX_LIMITS.maxSourceBytes,
                            "The ESX project is too large"
                        )
                    }
                }
                sourceArchive != null -> writeOverlayExport(
                    sourceArchive,
                    snapshot,
                    exportDirectory,
                    completeArchive
                )
                else -> writeCompleteExport(snapshot, exportDirectory, completeArchive)
            }

            require(completeArchive.isFile && completeArchive.length() in 1..DEFAULT_ESX_LIMITS.maxSourceBytes) {
                "The ESX archive could not be created"
            }
            requireNotNull(contentResolver.openOutputStream(destinationUri, "wt")) {
                "Cannot open the selected export destination"
            }.use { output ->
                completeArchive.inputStream().use {
                    BoundedIo.copy(
                        it,
                        output,
                        DEFAULT_ESX_LIMITS.maxSourceBytes,
                        "The ESX project is too large"
                    )
                }
            }
        } finally {
            exportDirectory.deleteRecursively()
            completeArchive.delete()
        }
    }

    private fun writeCompleteExport(
        snapshot: ProjectExportSnapshot,
        exportDirectory: File,
        completeArchive: File
    ) {
        val report = buildReport(snapshot, localOnly = false)
        val filePayloads = linkedMapOf<String, File>()
        require(snapshot.floors.size == report.floorPlans.floorPlans.size) {
            "Export floor-plan mapping is inconsistent"
        }
        snapshot.floors.zip(report.floorPlans.floorPlans).forEach { (floor, floorPlan) ->
            require(floor.esxImageId == floorPlan.imageId) { "Export image mapping is inconsistent" }
            require(floor.mapFile.isFile) { "Project map payload is missing" }
            require(filePayloads.put("image-${floorPlan.imageId}", floor.mapFile) == null) {
                "Duplicate generated floor-plan image ID"
            }
        }
        stageNotePhotos(exportDirectory, snapshot, filePayloads, localOnly = false)
        val staticPayloads = STATIC_PAYLOADS.associateWith { assetName ->
            { appContext.assets.open(assetName) }
        }
        GeneratedEsxArchiveWriter(gson).write(
            destinationArchive = completeArchive,
            report = report,
            adapterInformation = createAdapterInformation(),
            staticPayloads = staticPayloads,
            filePayloads = filePayloads
        )
    }

    private fun writeOverlayExport(
        sourceArchive: File,
        snapshot: ProjectExportSnapshot,
        exportDirectory: File,
        completeArchive: File
    ) {
        val report = buildReport(snapshot, localOnly = true)
        val additionalPayloads = linkedMapOf<String, File>()
        snapshot.floors.filterNot(FloorExportSnapshot::importedFromSource).forEach { floor ->
            require(floor.mapFile.isFile) { "Project map payload is missing" }
            require(additionalPayloads.put("image-${floor.esxImageId}", floor.mapFile) == null) {
                "Duplicate generated floor-plan image ID"
            }
        }
        stageNotePhotos(exportDirectory, snapshot, additionalPayloads, localOnly = true)
        EsxArchiveOverlayWriter(gson).write(
            sourceArchive = sourceArchive,
            destinationArchive = completeArchive,
            snapshot = snapshot,
            report = report,
            adapterInformation = createAdapterInformation(),
            additionalPayloads = additionalPayloads
        )
    }

    private fun buildReport(snapshot: ProjectExportSnapshot, localOnly: Boolean): EkahauReport =
        reportBuilder.build(
            projectName = snapshot.title,
            floors = snapshot.floors.map { floor ->
                require(EsxIdFactory.isUuid(floor.esxFloorPlanId)) { "Floor-plan ESX ID is not a UUID" }
                require(EsxIdFactory.isUuid(floor.esxImageId)) { "Floor-plan image ESX ID is not a UUID" }
                EkahauFloorInput(
                    name = floor.name,
                    mapInfo = floor.survey.mapInfo,
                    metersPerUnit = floor.survey.metersPerUnit,
                    scanPoints = floor.survey.scanPoints.filter { !localOnly || !it.importedFromEsx },
                    continuousSessions = floor.survey.continuousScanSessions
                        .filter { !localOnly || !it.importedFromEsx },
                    notes = floor.survey.notes.filter { !localOnly || !it.importedFromEsx },
                    floorPlanId = floor.esxFloorPlanId,
                    imageId = floor.esxImageId
                )
            }
        )

    private fun hasSourceOverlayChanges(snapshot: ProjectExportSnapshot): Boolean =
        snapshot.floors.any { floor ->
            !floor.importedFromSource ||
                !sameScale(floor.sourceMetersPerUnit, floor.survey.metersPerUnit) ||
                floor.survey.scanPoints.any { !it.importedFromEsx } ||
                floor.survey.continuousScanSessions.any { !it.importedFromEsx } ||
                floor.survey.notes.any { !it.importedFromEsx }
        }

    private fun sameScale(first: Double?, second: Double?): Boolean {
        if (first == null || second == null) return first == second
        return kotlin.math.abs(first - second) <= SCALE_EPSILON
    }

    private fun validateSourceInventory(
        sourceArchive: File,
        expected: EsxSourceCompatibility?
    ) {
        EsxArchiveReader(sourceArchive).use { source ->
            if (expected != null) {
                require(source.compatibilityInventory() == expected) {
                    "The imported ESX source archive no longer matches its stored inventory"
                }
            }
        }
    }

    private fun createAdapterInformation(): EsxWifiAdapterInformation {
        val manufacturer = Build.MANUFACTURER.replaceFirstChar { it.uppercase() }
        val model = Build.MODEL
        val userDefinedName = runCatching {
            Settings.Global.getString(contentResolver, Settings.Global.DEVICE_NAME)
        }.getOrNull()
        val adapterName = if (!userDefinedName.isNullOrBlank() && userDefinedName != model) {
            "$userDefinedName ($manufacturer $model)"
        } else {
            "$manufacturer $model"
        }
        return EsxWifiAdapterInformation(name = adapterName)
    }

    private fun stageNotePhotos(
        exportDirectory: File,
        snapshot: ProjectExportSnapshot,
        additionalPayloads: MutableMap<String, File>,
        localOnly: Boolean
    ) {
        snapshot.floors.flatMap { it.survey.notes }
            .filter { !localOnly || !it.importedFromEsx }
            .forEach { note ->
                if (note.photoUri != null && note.photoId != null) {
                    val entryName = "image-${note.photoId}"
                    val destination = File(exportDirectory, entryName)
                    requireNotNull(contentResolver.openInputStream(Uri.parse(note.photoUri))) {
                        "A note photo payload is missing"
                    }.use { input ->
                        BoundedIo.copyToFile(
                            input,
                            destination,
                            DEFAULT_ESX_LIMITS.maxPhotoBytes,
                            "A note photo is too large"
                        )
                    }
                    require(additionalPayloads.put(entryName, destination) == null) {
                        "Duplicate generated note image ID"
                    }
                }
            }
    }

    private companion object {
        private const val SCALE_EPSILON = 1e-12
        val STATIC_PAYLOADS = listOf(
            "projectConfiguration.json",
            "requirements.json",
            "usageProfiles.json",
            "version",
            "wallTypes.json",
            "applicationProfiles.json",
            "attenuationAreaTypes.json",
            "deviceProfiles.json",
            "floorTypes.json",
            "networkCapacitySettings.json"
        )
    }
}
