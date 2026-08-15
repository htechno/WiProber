package com.example.wiprober

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertThrows
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

class EsxProjectImporterTest {
    @get:Rule
    val temporaryFolder = TemporaryFolder()

    @Test
    fun importsSupportedStopAndGoSurveyAndNote() {
        val destination = File(temporaryFolder.root, "stop-and-go")

        val projects = EsxProjectImporter().import(
            ByteArrayInputStream(esxArchive(routeType = "STOP_AND_GO")),
            destination
        )

        assertEquals(1, projects.size)
        val project = projects.single()
        assertEquals("Fixture project", project.projectName)
        assertEquals("Level 1", project.floorName)
        assertEquals(0.05, project.metersPerUnit, 0.0)
        assertEquals(1, project.scanPoints.size)
        assertEquals(0, project.continuousScanSessions.size)
        assertEquals(true, project.scanPoints.single().importedFromEsx)
        assertEquals(emptyList<WifiNetworkInfo>(), project.scanPoints.single().wifiNetworks)
        assertEquals("Fixture note", project.notes.single().text)
        assertNotNull(project.notes.single().photoFile)
        assertEquals("JPEG", project.notes.single().photoFormat)
    }

    @Test
    fun importsContinuousTimingInMilliseconds() {
        val destination = File(temporaryFolder.root, "continuous")

        val project = EsxProjectImporter().import(
            ByteArrayInputStream(esxArchive(routeType = "CONTINUOUS")),
            destination
        ).single()

        assertEquals(0, project.scanPoints.size)
        val session = project.continuousScanSessions.single()
        assertEquals(listOf(0L, 2_000L), session.waypoints.map { it.timestamp })
        assertEquals(true, session.importedFromEsx)
        assertEquals(0, session.scanResults.size)
        assertEquals(2_000L, session.endTime - session.startTime)
    }

    @Test
    fun treatsMalformedWifiTrackAsOpaqueAndDoesNotExtractIt() {
        val destination = File(temporaryFolder.root, "opaque-track")
        val project = EsxProjectImporter().import(
            ByteArrayInputStream(esxArchive(routeType = "CONTINUOUS", trackBytes = byteArrayOf(9, 8, 7))),
            destination
        ).single()

        assertEquals(1, project.continuousScanSessions.size)
        assertEquals(false, File(destination, "track-track-1.bin").exists())
    }

    @Test
    fun importsAllFloorPlansForCallerSelection() {
        val archive = zip(
            "project.json" to """{"project":{"title":"Multi-floor fixture"}}""".toByteArray(),
            "floorPlans.json" to """
                {"floorPlans":[
                  {"id":"floor-1","name":"Ground","width":100,"height":100,"imageId":"map-1"},
                  {"id":"floor-2","name":"First","width":200,"height":150,"imageId":"map-2"}
                ]}
            """.trimIndent().toByteArray(),
            "images.json" to """
                {"images":[
                  {"id":"map-1","imageFormat":"PNG"},
                  {"id":"map-2","imageFormat":"PNG"}
                ]}
            """.trimIndent().toByteArray(),
            "image-map-1" to byteArrayOf(1),
            "image-map-2" to byteArrayOf(2)
        )

        val projects = EsxProjectImporter().import(
            ByteArrayInputStream(archive),
            File(temporaryFolder.root, "multiple-floors")
        )

        assertEquals(listOf("Ground", "First"), projects.map { it.floorName })
    }

    @Test
    fun ignoresUnrelatedNestedEntryWithSameBaseName() {
        val archive = zip(
            "floorPlans.json" to """
                {"floorPlans":[{"id":"floor-1","name":"Floor","width":10,"height":10,"imageId":"map-1"}]}
            """.trimIndent().toByteArray(),
            "images.json" to """{"images":[{"id":"map-1","imageFormat":"PNG"}]}""".toByteArray(),
            "image-map-1" to byteArrayOf(1),
            "nested/floorPlans.json" to "{}".toByteArray()
        )

        val projects = EsxProjectImporter().import(
            ByteArrayInputStream(archive),
            File(temporaryFolder.root, "nested-name")
        )

        assertEquals(1, projects.size)
    }

    @Test
    fun rejectsAmbiguousPayloadNames() {
        val archive = zip(
            "floorPlans.json" to """
                {"floorPlans":[{"id":"floor-1","name":"Floor","width":10,"height":10,"imageId":"map-1"}]}
            """.trimIndent().toByteArray(),
            "images.json" to """{"images":[{"id":"map-1","imageFormat":"PNG"}]}""".toByteArray(),
            "image-map-1" to byteArrayOf(1),
            "image-map-1.png" to byteArrayOf(2)
        )

        val error = assertThrows(IllegalArgumentException::class.java) {
            EsxProjectImporter().import(
                ByteArrayInputStream(archive),
                File(temporaryFolder.root, "ambiguous-payload")
            )
        }

        assertEquals("The ESX project contains ambiguous payload names", error.message)
    }

    @Test
    fun ignoresUnsupportedSpectrumPayloads() {
        val archive = zip(
            "floorPlans.json" to """
                {"floorPlans":[{"id":"floor-1","name":"Floor","width":10,"height":10,"imageId":"map-1"}]}
            """.trimIndent().toByteArray(),
            "images.json" to """{"images":[{"id":"map-1","imageFormat":"PNG"}]}""".toByteArray(),
            "image-map-1" to byteArrayOf(1),
            "spectrum-unsupported.bin" to byteArrayOf(2, 3, 4)
        )
        val destination = File(temporaryFolder.root, "unsupported-spectrum")

        EsxProjectImporter().import(ByteArrayInputStream(archive), destination)

        assertEquals(false, File(destination, "spectrum-unsupported.bin").exists())
    }

    @Test
    fun doesNotValidateOpaqueTrackMeasurementIndexes() {
        val invalidTrack = BinaryDataSerializer.serialize(
            listOf(
                BinaryDataSerializer.MeasurementEntry(
                    relTimestamp = 150,
                    apIndex = 1,
                    network = network()
                )
            )
        )

        val project = EsxProjectImporter().import(
            ByteArrayInputStream(esxArchive(trackBytes = invalidTrack)),
            File(temporaryFolder.root, "invalid-reference")
        ).single()

        assertEquals(1, project.scanPoints.size)
    }

    @Test
    fun doesNotValidateTruncatedOpaqueTrack() {
        val validTrack = BinaryDataSerializer.serialize(
            listOf(BinaryDataSerializer.MeasurementEntry(150, 0, network()))
        )
        val truncatedTrack = validTrack.copyOf(validTrack.size - 2)

        val project = EsxProjectImporter().import(
            ByteArrayInputStream(esxArchive(trackBytes = truncatedTrack)),
            File(temporaryFolder.root, "truncated")
        ).single()

        assertEquals(1, project.scanPoints.size)
    }

    private fun esxArchive(
        routeType: String = "STOP_AND_GO",
        trackBytes: ByteArray = BinaryDataSerializer.serialize(
            listOf(BinaryDataSerializer.MeasurementEntry(150, 0, network()))
        )
    ): ByteArray {
        val secondPoint = if (routeType == "CONTINUOUS") {
            """{"time":2000000000,"location":{"x":200.0,"y":250.0}}"""
        } else {
            """{"time":2000000000,"location":{"x":100.0,"y":250.0}}"""
        }
        val survey = """
            {
              "surveys": [{
                "id": "survey-1",
                "floorPlanId": "floor-1",
                "startTime": "2026-08-11T12:00:00Z",
                "duration": 2000000000,
                "routeType": "$routeType",
                "routePoints": [[
                  {"time": 0, "location": {"x": 100.0, "y": 250.0}},
                  $secondPoint
                ]],
                "wifiTracks": [{
                  "binaryFileId": "track-1",
                  "accessPointMeasurementIds": ["measurement-1"],
                  "scannings": [{"startTime": 50000000, "endTime": 150000000}]
                }]
              }]
            }
        """.trimIndent().toByteArray()

        return zip(
            "project.json" to """{"project":{"title":"Fixture project"}}""".toByteArray(),
            "floorPlans.json" to """
                {"floorPlans":[{
                  "id":"floor-1",
                  "name":"Level 1",
                  "width":1000,
                  "height":500,
                  "imageId":"map-1",
                  "metersPerUnit":0.05
                }]}
            """.trimIndent().toByteArray(),
            "images.json" to """
                {"images":[
                  {"id":"map-1","imageFormat":"PNG","resolutionWidth":1000,"resolutionHeight":500},
                  {"id":"photo-1","imageFormat":"JPEG","resolutionWidth":640,"resolutionHeight":480}
                ]}
            """.trimIndent().toByteArray(),
            "accessPointMeasurements.json" to """
                {"accessPointMeasurements":[{
                  "id":"measurement-1",
                  "mac":"00:11:22:33:44:55",
                  "ssid":"Fixture Wi-Fi",
                  "security":"WPA2",
                  "technologies":["AX"],
                  "informationElements":""
                }]}
            """.trimIndent().toByteArray(),
            "surveyLookups.json" to """
                {"surveyLookups":[{"surveyId":"survey-1","floorPlanId":"floor-1"}]}
            """.trimIndent().toByteArray(),
            "survey-survey-1.json" to survey,
            "track-track-1.bin" to trackBytes,
            "notes.json" to """
                {"notes":[{"id":"note-1","text":"Fixture note","imageIds":["photo-1"]}]}
            """.trimIndent().toByteArray(),
            "pictureNotes.json" to """
                {"pictureNotes":[{
                  "id":"picture-note-1",
                  "noteIds":["note-1"],
                  "location":{"floorPlanId":"floor-1","coord":{"x":25.0,"y":50.0}}
                }]}
            """.trimIndent().toByteArray(),
            "image-map-1" to byteArrayOf(1, 2, 3),
            "image-photo-1" to byteArrayOf(4, 5, 6)
        )
    }

    private fun network() = WifiNetworkInfo(
        ssid = "Fixture Wi-Fi",
        bssid = "00:11:22:33:44:55",
        level = -47,
        frequency = 5_180,
        security = "WPA2",
        technologies = listOf("AX"),
        informationElements = ""
    )

    private fun zip(vararg entries: Pair<String, ByteArray>): ByteArray {
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
