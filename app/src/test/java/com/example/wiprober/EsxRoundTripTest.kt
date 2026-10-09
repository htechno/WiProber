package com.example.wiprober

import com.google.gson.Gson
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

class EsxRoundTripTest {
    @get:Rule
    val temporaryFolder = TemporaryFolder()

    @Test
    fun importsSupportedSubsetWrittenByReportBuilder() {
        val network = WifiNetworkInfo(
            ssid = "Round trip",
            bssid = "00:11:22:33:44:55",
            level = -52,
            frequency = 5_180,
            security = "WPA2",
            technologies = listOf("AC", "N"),
            informationElements = ""
        )
        val report = EkahauReportBuilder().build(
            scanPoints = listOf(ScanPoint(1_700_000_000_000L, 25f, 30f, listOf(network))),
            continuousSessions = emptyList(),
            mapInfo = MapInfo("round-trip.png", 640, 480),
            metersPerUnit = 0.04,
            notes = listOf(
                AppNote(
                    id = "note-1",
                    text = "Round-trip note",
                    photoUri = null,
                    photoWidth = null,
                    photoHeight = null,
                    photoId = null,
                    x = 12f,
                    y = 18f,
                    pictureNoteId = "picture-note-1"
                )
            )
        )

        val imported = EsxProjectImporter().import(
            ByteArrayInputStream(archive(report)),
            File(temporaryFolder.root, "round-trip")
        ).single()

        assertEquals(640, imported.mapInfo.width)
        assertEquals(480, imported.mapInfo.height)
        assertEquals(0.04, imported.metersPerUnit, 0.0)
        assertEquals(true, imported.scanPoints.single().importedFromEsx)
        assertEquals(emptyList<WifiNetworkInfo>(), imported.scanPoints.single().wifiNetworks)
        assertEquals("Round-trip note", imported.notes.single().text)
    }

    @Test
    fun roundTripsSupportedDataFromEveryFloor() {
        val network = WifiNetworkInfo(
            "Fixture",
            "00:11:22:33:44:55",
            -50,
            5_180,
            "WPA2",
            listOf("AC"),
            ""
        )
        val report = EkahauReportBuilder().build(
            "Two floors",
            listOf(
                EkahauFloorInput(
                    "Ground",
                    MapInfo("ground.png", 100, 80),
                    0.1,
                    listOf(ScanPoint(1L, 10f, 20f, listOf(network))),
                    emptyList(),
                    emptyList()
                ),
                EkahauFloorInput(
                    "First",
                    MapInfo("first.png", 120, 90),
                    0.2,
                    listOf(ScanPoint(2L, 30f, 40f, listOf(network))),
                    emptyList(),
                    emptyList()
                )
            )
        )

        val imported = EsxProjectImporter().import(
            ByteArrayInputStream(archive(report)),
            File(temporaryFolder.root, "multi-floor-round-trip")
        )

        assertEquals(listOf("Ground", "First"), imported.map { it.floorName })
        assertEquals(listOf(0.1, 0.2), imported.map { it.metersPerUnit })
        assertEquals(listOf(1, 1), imported.map { it.scanPoints.size })
    }

    private fun archive(report: EkahauReport): ByteArray {
        val gson = Gson()
        val entries = mutableListOf(
            "project.json" to gson.toJson(report.project).toByteArray(),
            "floorPlans.json" to gson.toJson(report.floorPlans).toByteArray(),
            "accessPointMeasurements.json" to gson.toJson(report.accessPointMeasurements).toByteArray(),
            "surveyLookups.json" to gson.toJson(report.surveyLookups).toByteArray(),
            "images.json" to gson.toJson(report.images).toByteArray(),
            "notes.json" to gson.toJson(report.notes).toByteArray(),
            "pictureNotes.json" to gson.toJson(report.pictureNotes).toByteArray()
        )
        report.surveys.forEach { (surveyId, survey) ->
            entries += "survey-$surveyId.json" to gson.toJson(survey).toByteArray()
        }
        report.binaryData.forEach { (trackId, bytes) ->
            entries += "track-$trackId.bin" to bytes
        }
        report.floorPlans.floorPlans.forEachIndexed { index, floor ->
            entries += "image-${floor.imageId}" to byteArrayOf(1, 2, (index + 3).toByte())
        }

        val output = ByteArrayOutputStream()
        ZipOutputStream(output).use { zip ->
            entries.forEach { (name, bytes) ->
                zip.putNextEntry(ZipEntry(name))
                zip.write(bytes)
                zip.closeEntry()
            }
        }
        return output.toByteArray()
    }
}
