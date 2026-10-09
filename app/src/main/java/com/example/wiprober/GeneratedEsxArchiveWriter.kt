package com.example.wiprober

import com.google.gson.Gson
import java.io.File
import java.io.InputStream

/** Writes the supported WiProber subset as a complete ESX archive created from scratch. */
internal class GeneratedEsxArchiveWriter(
    private val gson: Gson
) {
    fun write(
        destinationArchive: File,
        report: EkahauReport,
        adapterInformation: EsxWifiAdapterInformation,
        staticPayloads: Map<String, () -> InputStream>,
        filePayloads: Map<String, File>
    ) {
        EsxReportValidator.validate(report, adapterInformation)
        try {
            writeArchive(destinationArchive, report, adapterInformation, staticPayloads, filePayloads)
        } catch (error: Throwable) {
            destinationArchive.delete()
            throw error
        }
    }

    private fun writeArchive(
        destinationArchive: File,
        report: EkahauReport,
        adapterInformation: EsxWifiAdapterInformation,
        staticPayloads: Map<String, () -> InputStream>,
        filePayloads: Map<String, File>
    ) {
        EsxArchiveWriter(destinationArchive).use { archive ->
            archive.putJson("project.json", report.project)
            archive.putJson("floorPlans.json", report.floorPlans)
            archive.putJson("accessPointMeasurements.json", report.accessPointMeasurements)
            archive.putJson("surveyLookups.json", report.surveyLookups)
            archive.putJson("projectHistorys.json", report.projectHistorys)
            archive.putJson("images.json", report.images)
            archive.putJson("accessPoints.json", report.accessPoints)
            archive.putJson("measuredRadios.json", report.measuredRadios)
            archive.putJson("notes.json", report.notes)
            archive.putJson("pictureNotes.json", report.pictureNotes)
            archive.putJson(
                "wifiAdapterInformations.json",
                EsxWifiAdapterInformationsWrapper(listOf(adapterInformation))
            )
            report.surveys.forEach { (surveyId, survey) ->
                archive.putJson("survey-$surveyId.json", survey)
            }
            report.binaryData.forEach { (binaryId, bytes) ->
                archive.putBytes("track-$binaryId.bin", bytes, MAX_BINARY_TRACK_BYTES)
            }
            staticPayloads.forEach { (name, source) ->
                archive.putStream(name, DEFAULT_ESX_LIMITS.maxEntryBytes, source)
            }
            filePayloads.forEach { (name, file) ->
                archive.putFile(name, file, payloadLimit(name))
            }
        }
    }

    private fun EsxArchiveWriter.putJson(name: String, value: Any) {
        val bytes = gson.toJson(value).toByteArray(Charsets.UTF_8)
        require(bytes.size.toLong() <= DEFAULT_ESX_LIMITS.maxJsonBytes) {
            "The generated ESX JSON payload is too large"
        }
        putBytes(name, bytes, DEFAULT_ESX_LIMITS.maxJsonBytes)
    }

    private fun payloadLimit(name: String): Long = when {
        name.startsWith("image-") -> DEFAULT_ESX_LIMITS.maxMapBytes
        else -> DEFAULT_ESX_LIMITS.maxEntryBytes
    }

    private companion object {
        const val MAX_BINARY_TRACK_BYTES = 64L * 1024L * 1024L
    }
}
