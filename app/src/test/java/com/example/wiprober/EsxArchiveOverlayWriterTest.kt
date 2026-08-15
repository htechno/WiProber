package com.example.wiprober

import com.google.gson.GsonBuilder
import com.google.gson.JsonParser
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.ByteArrayOutputStream
import java.io.File
import java.security.MessageDigest
import java.util.zip.ZipEntry
import java.util.zip.ZipFile
import java.util.zip.ZipOutputStream

class EsxArchiveOverlayWriterTest {
    @get:Rule
    val temporaryFolder = TemporaryFolder()

    @Test
    fun preservesEveryOriginalPayloadAndAppendsOnlyLocalSurveyData() {
        val source = File(temporaryFolder.root, "source.esx")
        val output = File(temporaryFolder.root, "output.esx")
        val map = File(temporaryFolder.root, "map.png").apply { writeBytes(byteArrayOf(1, 2, 3)) }
        val originals = sourceEntries()
        writeZip(source, originals)

        val importedPoint = ScanPoint(10L, 5f, 6f, emptyList(), importedFromEsx = true)
        val localPoint = ScanPoint(20L, 7f, 8f, listOf(network()))
        val snapshot = ProjectExportSnapshot(
            id = "local-project",
            title = "Source project",
            sourceArchive = source,
            floors = listOf(
                FloorExportSnapshot(
                    id = "floor-1",
                    name = "Source floor",
                    mapFile = map,
                    esxFloorPlanId = SOURCE_FLOOR_ID,
                    esxImageId = SOURCE_IMAGE_ID,
                    importedFromSource = true,
                    sourceMetersPerUnit = 0.05,
                    survey = ProjectSnapshot(
                        mapInfo = MapInfo("map.png", 100, 80),
                        metersPerUnit = 0.05,
                        scanPoints = listOf(importedPoint, localPoint),
                        continuousScanSessions = emptyList(),
                        notes = emptyList()
                    )
                )
            )
        )
        val report = EkahauReportBuilder().build(
            projectName = snapshot.title,
            floors = listOf(
                EkahauFloorInput(
                    name = "Source floor",
                    mapInfo = MapInfo("map.png", 100, 80),
                    metersPerUnit = 0.05,
                    scanPoints = listOf(localPoint),
                    continuousSessions = emptyList(),
                    notes = emptyList(),
                    floorPlanId = SOURCE_FLOOR_ID,
                    imageId = SOURCE_IMAGE_ID
                )
            )
        )

        EsxArchiveOverlayWriter(GsonBuilder().setPrettyPrinting().create()).write(
            sourceArchive = source,
            destinationArchive = output,
            snapshot = snapshot,
            report = report,
            adapterInformation = testAdapter(),
            additionalPayloads = emptyMap()
        )

        val exported = readZip(output)
        assertTrue(exported.keys.containsAll(originals.keys))
        listOf(
            "project.json",
            "floorPlans.json",
            "survey-source-survey.json",
            "track-source-track.bin",
            "spectrum-source.bin",
            "future/entity.bin"
        ).forEach { entryName ->
            assertArrayEquals(
                "Unchanged entry must retain its uncompressed bytes: $entryName",
                sha256(originals.getValue(entryName)),
                sha256(exported.getValue(entryName))
            )
        }
        assertTrue(exported.keys.any { it.startsWith("survey-") && it != "survey-source-survey.json" })
        assertTrue(exported.keys.any { it.startsWith("track-") && it != "track-source-track.bin" })

        val measurements = JsonParser.parseString(
            exported.getValue("accessPointMeasurements.json").toString(Charsets.UTF_8)
        ).asJsonObject
        assertEquals("keep-me", measurements.get("futureRootField").asString)
        assertEquals(
            "source-measurement",
            measurements.getAsJsonArray("accessPointMeasurements").first().asJsonObject.get("id").asString
        )
        assertEquals(2, measurements.getAsJsonArray("accessPointMeasurements").size())
        assertNotEquals(
            sha256(originals.getValue("accessPointMeasurements.json")).toList(),
            sha256(exported.getValue("accessPointMeasurements.json")).toList()
        )
    }

    @Test
    fun appendsNewFloorAndMapWhilePreservingUnknownSourceFloorFields() {
        val source = File(temporaryFolder.root, "floor-source.esx")
        val output = File(temporaryFolder.root, "floor-output.esx")
        val sourceMap = File(temporaryFolder.root, "source-map.png").apply { writeBytes(byteArrayOf(1)) }
        val newMap = File(temporaryFolder.root, "new-map.png").apply { writeBytes(byteArrayOf(4, 5, 6)) }
        writeZip(source, sourceEntries())
        val floors = listOf(
            FloorExportSnapshot(
                id = "floor-1",
                name = "Source floor",
                mapFile = sourceMap,
                esxFloorPlanId = SOURCE_FLOOR_ID,
                esxImageId = SOURCE_IMAGE_ID,
                importedFromSource = true,
                sourceMetersPerUnit = 0.05,
                survey = ProjectSnapshot(MapInfo("source.png", 100, 80), 0.05, emptyList(), emptyList(), emptyList())
            ),
            FloorExportSnapshot(
                id = "floor-2",
                name = "New floor",
                mapFile = newMap,
                esxFloorPlanId = LOCAL_FLOOR_ID,
                esxImageId = LOCAL_IMAGE_ID,
                importedFromSource = false,
                sourceMetersPerUnit = null,
                survey = ProjectSnapshot(MapInfo("new.png", 120, 90), 0.1, emptyList(), emptyList(), emptyList())
            )
        )
        val snapshot = ProjectExportSnapshot("project", "Source", source, floors)
        val report = EkahauReportBuilder().build(
            "Source",
            floors.map { floor ->
                EkahauFloorInput(
                    floor.name,
                    floor.survey.mapInfo,
                    floor.survey.metersPerUnit,
                    emptyList(),
                    emptyList(),
                    emptyList(),
                    floor.esxFloorPlanId,
                    floor.esxImageId
                )
            }
        )

        EsxArchiveOverlayWriter(GsonBuilder().setPrettyPrinting().create()).write(
            source,
            output,
            snapshot,
            report,
            testAdapter(),
            mapOf("image-$LOCAL_IMAGE_ID" to newMap)
        )

        val exported = readZip(output)
        val floorPlans = JsonParser.parseString(exported.getValue("floorPlans.json").toString(Charsets.UTF_8))
            .asJsonObject.getAsJsonArray("floorPlans")
        assertEquals(2, floorPlans.size())
        assertEquals(
            "keep",
            floorPlans.first { it.asJsonObject.get("id").asString == SOURCE_FLOOR_ID }
                .asJsonObject.get("futureFloorField").asString
        )
        assertTrue(floorPlans.any { it.asJsonObject.get("id").asString == LOCAL_FLOOR_ID })
        assertArrayEquals(newMap.readBytes(), exported.getValue("image-$LOCAL_IMAGE_ID"))
    }

    private fun sourceEntries(): Map<String, ByteArray> = linkedMapOf(
        "project.json" to """{"project":{"id":"source-project","title":"Source project","future":{"x":1}}}""".toByteArray(),
        "floorPlans.json" to """
            {"floorPlans":[{"id":"$SOURCE_FLOOR_ID","name":"Source floor","width":100,"height":80,
            "imageId":"$SOURCE_IMAGE_ID","metersPerUnit":0.05,"futureFloorField":"keep"}]}
        """.trimIndent().toByteArray(),
        "images.json" to """{"images":[{"id":"$SOURCE_IMAGE_ID","imageFormat":"PNG"}]}""".toByteArray(),
        "accessPointMeasurements.json" to """
            {"futureRootField":"keep-me","accessPointMeasurements":[{"id":"source-measurement","mac":"00:00:00:00:00:01"}]}
        """.trimIndent().toByteArray(),
        "surveyLookups.json" to """
            {"surveyLookups":[{"id":"source-lookup","surveyId":"source-survey","floorPlanId":"$SOURCE_FLOOR_ID"}]}
        """.trimIndent().toByteArray(),
        "accessPoints.json" to """{"accessPoints":[]}""".toByteArray(),
        "measuredRadios.json" to """{"measuredRadios":[]}""".toByteArray(),
        "notes.json" to """{"notes":[]}""".toByteArray(),
        "pictureNotes.json" to """{"pictureNotes":[]}""".toByteArray(),
        "wifiAdapterInformations.json" to """{"wifiAdapterInformations":[]}""".toByteArray(),
        "image-$SOURCE_IMAGE_ID" to byteArrayOf(1, 2, 3),
        "survey-source-survey.json" to """
            {"surveys":[{"id":"source-survey","floorPlanId":"$SOURCE_FLOOR_ID","routePoints":[],"future":"keep"}]}
        """.trimIndent().toByteArray(),
        "track-source-track.bin" to byteArrayOf(99, 1, 2, 3, 4),
        "spectrum-source.bin" to byteArrayOf(7, 7, 7, 7),
        "future/entity.bin" to byteArrayOf(42, 24)
    )

    private fun network() = WifiNetworkInfo(
        ssid = "Local",
        bssid = "00:11:22:33:44:55",
        level = -50,
        frequency = 5_180,
        security = "WPA2",
        technologies = listOf("AC"),
        informationElements = ""
    )

    private fun testAdapter() = EsxWifiAdapterInformation(
        name = "Test adapter",
        driverVersion = "1",
        driverDate = "01-01-2026",
        revision = "test"
    )

    private fun writeZip(destination: File, entries: Map<String, ByteArray>) {
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

    private companion object {
        private const val SOURCE_FLOOR_ID = "11111111-1111-4111-8111-111111111111"
        private const val SOURCE_IMAGE_ID = "22222222-2222-4222-8222-222222222222"
        private const val LOCAL_FLOOR_ID = "33333333-3333-4333-8333-333333333333"
        private const val LOCAL_IMAGE_ID = "44444444-4444-4444-8444-444444444444"
    }
}
