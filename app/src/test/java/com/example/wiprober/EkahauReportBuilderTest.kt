package com.example.wiprober

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class EkahauReportBuilderTest {
    @Test
    fun buildsAllFloorsWithSharedMeasurementsAndFloorScopedSurveys() {
        val network = WifiNetworkInfo(
            ssid = "Shared",
            bssid = "00:11:22:33:44:55",
            level = -50,
            frequency = 5_180,
            security = "WPA2",
            technologies = listOf("AC"),
            informationElements = ""
        )
        val report = EkahauReportBuilder().build(
            projectName = "Office",
            floors = listOf(
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
                    MapInfo("first.jpg", 120, 90),
                    0.2,
                    listOf(ScanPoint(2L, 30f, 40f, listOf(network.copy(level = -60)))),
                    emptyList(),
                    emptyList()
                )
            )
        )

        assertEquals("Office", report.project.project.title)
        assertEquals(listOf("Ground", "First"), report.floorPlans.floorPlans.map { it.name })
        assertEquals(2, report.images.images.size)
        assertEquals(1, report.accessPointMeasurements.accessPointMeasurements.size)
        assertEquals(2, report.surveys.size)
        val floorIds = report.floorPlans.floorPlans.map { it.id }.toSet()
        assertEquals(floorIds, report.surveyLookups.surveyLookups.map { it.floorPlanId }.toSet())
        report.surveyLookups.surveyLookups.forEach { lookup ->
            assertEquals(
                lookup.floorPlanId,
                report.surveys.getValue(lookup.surveyId).surveys.single().floorPlanId
            )
        }
    }

    @Test
    fun keepsSurveyReferencesAndImageFormatsConsistent() {
        val network = WifiNetworkInfo(
            ssid = "Fixture",
            bssid = "00:11:22:33:44:55",
            level = -48,
            frequency = 5_180,
            security = "WPA2",
            technologies = listOf("AC", "N"),
            informationElements = ""
        )
        val report = EkahauReportBuilder().build(
            scanPoints = listOf(ScanPoint(1_700_000_000_000L, 10f, 20f, listOf(network))),
            continuousSessions = emptyList(),
            mapInfo = MapInfo("fixture.png", 1_000, 500),
            metersPerUnit = 0.05,
            notes = listOf(
                AppNote(
                    id = "note-1",
                    text = "Fixture note",
                    photoUri = null,
                    photoWidth = 20,
                    photoHeight = 10,
                    photoId = "photo-1",
                    x = 5f,
                    y = 6f,
                    pictureNoteId = "picture-note-1",
                    photoFormat = "PNG"
                )
            )
        )

        val floor = report.floorPlans.floorPlans.single()
        val survey = report.surveys.values.single().surveys.single()
        val lookup = report.surveyLookups.surveyLookups.single()
        val measurement = report.accessPointMeasurements.accessPointMeasurements.single()
        val track = survey.wifiTracks.single()

        assertEquals(floor.id, survey.floorPlanId)
        assertEquals(survey.id, lookup.surveyId)
        assertEquals(floor.id, lookup.floorPlanId)
        assertEquals(listOf(measurement.id), track.accessPointMeasurementIds)
        assertEquals("PNG", report.images.images.single { it.id == "photo-1" }.imageFormat)
        assertTrue(report.binaryData.containsKey(track.binaryFileId))
        assertEquals(
            listOf(BinaryTrackReader.Value(1, 0, -48, 5_180, 0)),
            BinaryTrackReader.readStrict(report.binaryData.getValue(track.binaryFileId))
        )
    }

    @Test
    fun mergesObservationsOfTheSameBssidWithoutLosingChannels() {
        val first = WifiNetworkInfo(
            ssid = "<unknown ssid>",
            bssid = "aa:bb:cc:dd:ee:ff",
            level = -60,
            frequency = 2_412,
            security = "Unknown",
            technologies = listOf("N"),
            informationElements = ""
        )
        val second = first.copy(
            ssid = "Fixture",
            bssid = "AA:BB:CC:DD:EE:FF",
            frequency = 5_180,
            security = "WPA2",
            technologies = listOf("AC"),
            informationElements = "AQID"
        )

        val report = EkahauReportBuilder().build(
            scanPoints = listOf(
                ScanPoint(1L, 1f, 1f, listOf(first)),
                ScanPoint(2L, 2f, 2f, listOf(second))
            ),
            continuousSessions = emptyList(),
            mapInfo = MapInfo("fixture.png", 100, 100),
            metersPerUnit = null,
            notes = emptyList()
        )

        val measurement = report.accessPointMeasurements.accessPointMeasurements.single()
        assertEquals("AA:BB:CC:DD:EE:FF", measurement.mac)
        assertEquals("Fixture", measurement.ssid)
        assertEquals(listOf(2_412, 5_180), measurement.channels)
        assertEquals("WPA2", measurement.security)
        assertEquals(listOf("AC", "N"), measurement.technologies)
        assertEquals("AQID", measurement.informationElements)
    }
}
