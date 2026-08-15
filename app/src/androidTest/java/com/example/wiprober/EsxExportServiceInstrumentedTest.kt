package com.example.wiprober

import android.content.Context
import android.graphics.Bitmap
import androidx.core.content.FileProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.google.gson.JsonParser
import org.junit.Assert.assertEquals
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.security.MessageDigest
import java.util.zip.ZipOutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipFile

@RunWith(AndroidJUnit4::class)
class EsxExportServiceInstrumentedTest {
    @Test
    fun exportsAndReimportsEveryFloorFromRepositorySnapshot() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val repository = ProjectRepository(context)
        val exportedFile = File(context.cacheDir, "stage-zero-round-trip.esx")
        val extractionDirectory = File(context.cacheDir, "stage-zero-round-trip-extracted")
        var projectId: String? = null

        try {
            exportedFile.delete()
            extractionDirectory.deleteRecursively()
            val summary = repository.createNewProject(
                ByteArrayInputStream(pngBytes()),
                "ground.png",
                projectName = "Stage zero",
                floorName = "Ground"
            )
            projectId = summary.id
            assertEquals(null, repository.snapshotProject(summary.id).sourceArchive)
            val ground = repository.openProject(summary.id).activeFloor
            repository.saveProject(
                summary.id,
                ground.id,
                ground.toSnapshot(
                    listOf(ScanPoint(1_000L, 4f, 5f, listOf(network("00:11:22:33:44:55"))))
                )
            )
            val first = repository.addFloor(
                summary.id,
                ByteArrayInputStream(pngBytes()),
                "first.png",
                "First"
            ).activeFloor
            repository.saveProject(
                summary.id,
                first.id,
                first.toSnapshot(
                    listOf(ScanPoint(2_000L, 8f, 9f, listOf(network("00:11:22:33:44:66"))))
                )
            )

            check(exportedFile.createNewFile())
            val destinationUri = FileProvider.getUriForFile(
                context,
                "${context.packageName}.provider",
                exportedFile
            )
            EsxExportService(context).export(destinationUri, repository.snapshotProject(summary.id))

            assertTrue(exportedFile.length() > 0L)
            ZipFile(exportedFile).use { zip ->
                val entries = zip.entries().asSequence().filterNot(ZipEntry::isDirectory).toList()
                val trackEntries = entries.filter { it.name.startsWith("track-") && it.name.endsWith(".bin") }
                assertEquals(2, trackEntries.size)
                trackEntries.forEach { entry ->
                    assertTrue(BinaryTrackReader.readStrict(zip.getInputStream(entry).readBytes()).isNotEmpty())
                }
                assertTrue(entries.any { it.name == "projectConfiguration.json" })
                val measurements = zip.getInputStream(zip.getEntry("accessPointMeasurements.json"))
                    .bufferedReader().use(JsonParser::parseReader)
                    .asJsonObject.getAsJsonArray("accessPointMeasurements")
                assertEquals(
                    setOf("00:11:22:33:44:55", "00:11:22:33:44:66"),
                    measurements.map { it.asJsonObject.get("mac").asString }.toSet()
                )
            }
            val imported = exportedFile.inputStream().use { input ->
                EsxProjectImporter().import(input, extractionDirectory)
            }
            assertEquals(listOf("Ground", "First"), imported.map(ImportedEsxProject::floorName))
            assertEquals(listOf(1, 1), imported.map { it.scanPoints.size })
            assertTrue(imported.all { it.scanPoints.single().wifiNetworks.isEmpty() })
        } finally {
            projectId?.let { File(context.filesDir, "projects/$it").deleteRecursively() }
            exportedFile.delete()
            extractionDirectory.deleteRecursively()
        }
    }

    @Test
    fun importedProjectKeepsSourceArchiveAndOpaquePayloadsWhileAddingLocalSurvey() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val sourceFile = File(context.cacheDir, "stage-zero-source.esx")
        val unchangedFile = File(context.cacheDir, "stage-zero-unchanged.esx")
        val changedFile = File(context.cacheDir, "stage-zero-changed.esx")
        val extractionDirectory = File(context.cacheDir, "stage-zero-source-extracted")
        val projectsDirectory = File(context.cacheDir, "stage-zero-overlay-projects")

        try {
            listOf(sourceFile, unchangedFile, changedFile).forEach(File::delete)
            extractionDirectory.deleteRecursively()
            projectsDirectory.deleteRecursively()
            writeSourceArchive(sourceFile)
            val sourceEntries = readZip(sourceFile)
            val imported = EsxProjectImporter().import(sourceFile, extractionDirectory)
            val repository = ProjectRepository(context, projectsRoot = projectsDirectory)
            val summary = repository.importProject(imported, sourceFile)
            val sourceSnapshot = repository.snapshotProject(summary.id)

            assertTrue(sourceSnapshot.sourceArchive?.isFile == true)
            assertEquals(1, sourceSnapshot.sourceCompatibility?.opaqueWifiTrackCount)
            assertEquals(1, sourceSnapshot.sourceCompatibility?.opaqueSpectrumCount)
            assertEquals(1, sourceSnapshot.sourceCompatibility?.unknownPayloadCount)
            unchangedFile.createNewFile()
            EsxExportService(context).export(fileUri(context, unchangedFile), sourceSnapshot)
            assertArrayEquals(sourceFile.readBytes(), unchangedFile.readBytes())

            val opened = repository.openProject(summary.id)
            assertTrue(opened.activeFloor.scanPoints.single().importedFromEsx)
            repository.saveProject(
                summary.id,
                opened.activeFloor.id,
                opened.activeFloor.toSnapshot(
                    opened.activeFloor.scanPoints +
                        ScanPoint(2_000L, 8f, 9f, listOf(network("00:11:22:33:44:77")))
                )
            )
            changedFile.createNewFile()
            EsxExportService(context).export(
                fileUri(context, changedFile),
                repository.snapshotProject(summary.id)
            )

            val changedEntries = readZip(changedFile)
            assertTrue(changedEntries.keys.containsAll(sourceEntries.keys))
            listOf(
                "project.json",
                "survey-source-survey.json",
                "track-source-track.bin",
                "spectrum-source.bin",
                "future/opaque.payload"
            ).forEach { name ->
                assertArrayEquals(sha256(sourceEntries.getValue(name)), sha256(changedEntries.getValue(name)))
            }
            assertTrue(changedEntries.keys.any {
                it.startsWith("survey-") && it != "survey-source-survey.json"
            })
            assertTrue(changedEntries.keys.any {
                it.startsWith("track-") && it != "track-source-track.bin"
            })
            val reimportDirectory = File(context.cacheDir, "stage-zero-overlay-reimport")
            reimportDirectory.deleteRecursively()
            val reimported = EsxProjectImporter().import(changedFile, reimportDirectory).single()
            assertEquals(2, reimported.scanPoints.size)
            reimportDirectory.deleteRecursively()
        } finally {
            listOf(sourceFile, unchangedFile, changedFile).forEach(File::delete)
            extractionDirectory.deleteRecursively()
            projectsDirectory.deleteRecursively()
        }
    }

    private fun LocalSurveyFloor.toSnapshot(scanPoints: List<ScanPoint>) = ProjectSnapshot(
        mapInfo = mapInfo,
        metersPerUnit = metersPerUnit,
        scanPoints = scanPoints,
        continuousScanSessions = emptyList(),
        notes = emptyList()
    )

    private fun fileUri(context: Context, file: File) = FileProvider.getUriForFile(
        context,
        "${context.packageName}.provider",
        file
    )

    private fun writeSourceArchive(destination: File) {
        val entries = linkedMapOf(
            "project.json" to """{"project":{"id":"source-project","title":"Source","future":"keep"}}""".toByteArray(),
            "floorPlans.json" to """
                {"floorPlans":[{"id":"$SOURCE_FLOOR_ID","name":"Source floor","width":20,"height":12,
                "imageId":"$SOURCE_IMAGE_ID","metersPerUnit":0.05,"future":"keep"}]}
            """.trimIndent().toByteArray(),
            "images.json" to """{"images":[{"id":"$SOURCE_IMAGE_ID","imageFormat":"PNG"}]}""".toByteArray(),
            "surveyLookups.json" to """
                {"surveyLookups":[{"id":"source-lookup","surveyId":"source-survey","floorPlanId":"$SOURCE_FLOOR_ID"}]}
            """.trimIndent().toByteArray(),
            "survey-source-survey.json" to """
                {"surveys":[{"id":"source-survey","floorPlanId":"$SOURCE_FLOOR_ID","startTime":"2026-01-01T00:00:00Z",
                "routeType":"STOP_AND_GO","routePoints":[[{"time":0,"location":{"x":4,"y":5}}]],
                "wifiTracks":[{"binaryFileId":"source-track"}],"spectrumSessions":[{"future":"keep"}]}]}
            """.trimIndent().toByteArray(),
            "accessPointMeasurements.json" to """{"accessPointMeasurements":[],"future":"keep"}""".toByteArray(),
            "accessPoints.json" to """{"accessPoints":[]}""".toByteArray(),
            "measuredRadios.json" to """{"measuredRadios":[]}""".toByteArray(),
            "notes.json" to """{"notes":[]}""".toByteArray(),
            "pictureNotes.json" to """{"pictureNotes":[]}""".toByteArray(),
            "wifiAdapterInformations.json" to """{"wifiAdapterInformations":[]}""".toByteArray(),
            "image-$SOURCE_IMAGE_ID" to pngBytes(),
            "track-source-track.bin" to byteArrayOf(9, 8, 7, 6),
            "spectrum-source.bin" to byteArrayOf(5, 4, 3, 2),
            "future/opaque.payload" to byteArrayOf(1, 3, 3, 7)
        )
        destination.outputStream().use { output ->
            ZipOutputStream(output).use { zip ->
                entries.forEach { (name, bytes) ->
                    zip.putNextEntry(ZipEntry(name))
                    zip.write(bytes)
                    zip.closeEntry()
                }
            }
        }
    }

    private fun readZip(archive: File): Map<String, ByteArray> = ZipFile(archive).use { zip ->
        zip.entries().asSequence().filterNot(ZipEntry::isDirectory).associate { entry ->
            entry.name to zip.getInputStream(entry).use { it.readBytes() }
        }
    }

    private fun sha256(bytes: ByteArray): ByteArray = MessageDigest.getInstance("SHA-256").digest(bytes)

    private fun network(bssid: String) = WifiNetworkInfo(
        ssid = "Stage zero",
        bssid = bssid,
        level = -50,
        frequency = 5_180,
        security = "WPA2",
        technologies = listOf("AC"),
        informationElements = ""
    )

    private fun pngBytes(): ByteArray {
        val output = ByteArrayOutputStream()
        val bitmap = Bitmap.createBitmap(20, 12, Bitmap.Config.ARGB_8888)
        check(bitmap.compress(Bitmap.CompressFormat.PNG, 100, output))
        bitmap.recycle()
        return output.toByteArray()
    }

    private companion object {
        private const val SOURCE_FLOOR_ID = "11111111-1111-4111-8111-111111111111"
        private const val SOURCE_IMAGE_ID = "22222222-2222-4222-8222-222222222222"
    }
}
