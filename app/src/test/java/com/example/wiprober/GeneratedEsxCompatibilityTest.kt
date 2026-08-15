package com.example.wiprober

import com.google.gson.GsonBuilder
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.ByteArrayInputStream
import java.io.DataInputStream
import java.io.File
import java.util.UUID
import java.util.zip.ZipFile

/** Validates a generated archive without using WiProber's ESX importer or binary reader. */
class GeneratedEsxCompatibilityTest {
    @get:Rule
    val temporaryFolder = TemporaryFolder()

    @Test
    fun generatedArchiveHasConsistentJsonRelationshipsTimeUnitsAndBinaryTrackFields() {
        val network = WifiNetworkInfo(
            ssid = "Fixture",
            bssid = "00:11:22:33:44:55",
            level = -48,
            frequency = 5_180,
            security = "WPA2",
            technologies = listOf("AC"),
            informationElements = ""
        )
        var nextId = 1
        val report = EkahauReportBuilder(
            clock = MillisClock { 1_700_000_000_000L },
            idSource = IdSource {
                "00000000-0000-4000-8000-${nextId++.toString().padStart(12, '0')}"
            }
        ).build(
            projectName = "Compatibility fixture",
            floors = listOf(
                EkahauFloorInput(
                    name = "Ground",
                    mapInfo = MapInfo("ground.png", 100, 80),
                    metersPerUnit = 0.05,
                    scanPoints = listOf(
                        ScanPoint(1_700_000_000_100L, 10f, 20f, listOf(network))
                    ),
                    continuousSessions = listOf(
                        ContinuousScanSession(
                            id = CONTINUOUS_SURVEY_ID,
                            startTime = 1_700_000_001_000L,
                            endTime = 1_700_000_007_000L,
                            waypoints = listOf(
                                RoutePointWrapper(0L, 1f, 2f),
                                RoutePointWrapper(5_000L, 3f, 4f)
                            ),
                            scanResults = listOf(
                                ScanResultWrapper(5_000L, 1_000L, listOf(network.copy(level = -55)))
                            )
                        )
                    ),
                    notes = emptyList(),
                    floorPlanId = FLOOR_ID,
                    imageId = IMAGE_ID
                )
            )
        )
        val mapBytes = byteArrayOf(1, 2, 3, 4)
        val mapFile = File(temporaryFolder.root, "ground.png").apply { writeBytes(mapBytes) }
        val output = File(temporaryFolder.root, "generated.esx")

        GeneratedEsxArchiveWriter(GsonBuilder().setPrettyPrinting().create()).write(
            destinationArchive = output,
            report = report,
            adapterInformation = EsxWifiAdapterInformation(
                name = "Fixture adapter",
                driverVersion = "1",
                driverDate = "01-01-2026",
                revision = "fixture"
            ),
            staticPayloads = mapOf("version" to { ByteArrayInputStream("11.8".toByteArray()) }),
            filePayloads = mapOf("image-$IMAGE_ID" to mapFile)
        )

        ZipFile(output).use { archive ->
            fun json(name: String): JsonObject = archive.getInputStream(archive.getEntry(name)).use {
                JsonParser.parseReader(it.reader()).asJsonObject
            }
            val floor = json("floorPlans.json").getAsJsonArray("floorPlans").single().asJsonObject
            val image = json("images.json").getAsJsonArray("images").single().asJsonObject
            val measurement = json("accessPointMeasurements.json")
                .getAsJsonArray("accessPointMeasurements").single().asJsonObject
            val adapter = json("wifiAdapterInformations.json")
                .getAsJsonArray("wifiAdapterInformations").single().asJsonObject
            val lookups = json("surveyLookups.json").getAsJsonArray("surveyLookups")
                .map { it.asJsonObject }
            val surveys = archive.entries().asSequence()
                .filter { it.name.startsWith("survey-") && it.name.endsWith(".json") }
                .flatMap { entry ->
                    archive.getInputStream(entry).use {
                        JsonParser.parseReader(it.reader()).asJsonObject.getAsJsonArray("surveys")
                            .map { survey -> survey.asJsonObject }
                    }.asSequence()
                }
                .toList()

            assertEquals(FLOOR_ID, floor.get("id").asString)
            assertEquals(IMAGE_ID, floor.get("imageId").asString)
            assertEquals(IMAGE_ID, image.get("id").asString)
            assertArrayEquals(mapBytes, archive.getInputStream(archive.getEntry("image-$IMAGE_ID")).readBytes())
            assertEquals(surveys.map { it.get("id").asString }.toSet(), lookups.map {
                it.get("surveyId").asString
            }.toSet())
            assertTrue(lookups.all { it.get("floorPlanId").asString == FLOOR_ID })

            listOf(
                json("project.json").getAsJsonObject("project").get("id").asString,
                floor.get("id").asString,
                image.get("id").asString,
                measurement.get("id").asString
            ).forEach { UUID.fromString(it) }

            val stopAndGo = surveys.single { it.get("routeType").asString == "STOP_AND_GO" }
            val continuous = surveys.single { it.get("routeType").asString == "CONTINUOUS" }
            assertEquals(CONTINUOUS_SURVEY_ID, continuous.get("id").asString)
            assertEquals(6_000_000_000L, continuous.get("duration").asLong)
            assertEquals(
                5_000_000_000L,
                continuous.getAsJsonArray("routePoints").single().asJsonArray.last().asJsonObject
                    .get("time").asLong
            )
            assertTrack(archive, stopAndGo, adapter.get("id").asString, 1, -48)
            assertTrack(archive, continuous, adapter.get("id").asString, 4_000, -55)
            val continuousScanning = continuous.getAsJsonArray("wifiTracks").single().asJsonObject
                .getAsJsonArray("scannings").single().asJsonObject
            assertEquals(4_000_000_000L, continuousScanning.get("startTime").asLong)
            assertEquals(5_000_000_000L, continuousScanning.get("endTime").asLong)
        }
    }

    private fun assertTrack(
        archive: ZipFile,
        survey: JsonObject,
        adapterId: String,
        expectedTimestamp: Int,
        expectedLevel: Int
    ) {
        val track = survey.getAsJsonArray("wifiTracks").single().asJsonObject
        assertEquals(adapterId, track.get("wifiAdapterInformationId").asString)
        assertEquals(1, track.getAsJsonArray("accessPointMeasurementIds").size())
        val binaryId = track.get("binaryFileId").asString
        UUID.fromString(binaryId)
        val values = archive.getInputStream(archive.getEntry("track-$binaryId.bin")).use {
            readBinaryIndependently(it.readBytes())
        }
        assertEquals(listOf(IndependentValue(expectedTimestamp, 0, expectedLevel, 5_180, 0)), values)
    }

    private fun readBinaryIndependently(bytes: ByteArray): List<IndependentValue> {
        val input = DataInputStream(ByteArrayInputStream(bytes))
        assertEquals(0x02, input.readUnsignedByte())
        assertEquals(0x01, input.readUnsignedByte())
        val result = mutableListOf<IndependentValue>()
        var scanIndex = -1
        while (input.available() > 0) {
            when (input.readUnsignedByte()) {
                0x00 -> scanIndex = input.readInt()
                0x01 -> {
                    assertEquals(0x02, input.readUnsignedByte())
                    val timestamp = input.readInt()
                    assertEquals(0x04, input.readUnsignedByte())
                    val apIndex = input.readUnsignedShort()
                    assertEquals(0x05, input.readUnsignedByte())
                    val level = input.readByte().toInt()
                    assertEquals(0x11, input.readUnsignedByte())
                    val frequency = input.readInt()
                    assertEquals(0x09, input.readUnsignedByte())
                    result += IndependentValue(timestamp, apIndex, level, frequency, scanIndex)
                }
                0x10 -> Unit
                else -> error("Unexpected binary field")
            }
        }
        return result
    }

    private data class IndependentValue(
        val timestamp: Int,
        val apIndex: Int,
        val level: Int,
        val frequency: Int,
        val scanIndex: Int
    )

    private companion object {
        const val FLOOR_ID = "11111111-1111-4111-8111-111111111111"
        const val IMAGE_ID = "22222222-2222-4222-8222-222222222222"
        const val CONTINUOUS_SURVEY_ID = "33333333-3333-4333-8333-333333333333"
    }
}
