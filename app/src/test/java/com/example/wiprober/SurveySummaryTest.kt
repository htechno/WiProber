package com.example.wiprober

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Test

class SurveySummaryTest {

    @Test
    fun activeFloorSummaryCountsSurveyEntities() {
        val state = SurveyScreenState(
            scanPoints = listOf(point(), point()),
            continuousScanSessions = listOf(route()),
            notes = listOf(note(), note(), note())
        )

        assertEquals(SurveySummary(2, 1, 3), state.surveySummary())
    }

    @Test
    fun exportSummaryIncludesEveryFloor() {
        val first = floor("first", points = 2, routes = 1, notes = 0)
        val second = floor("second", points = 1, routes = 2, notes = 4)

        assertEquals(
            ProjectSurveySummary(2, 3, 3, 4),
            ProjectExportSnapshot("project", "Project", null, listOf(first, second))
                .surveySummary()
        )
    }

    private fun floor(
        id: String,
        points: Int,
        routes: Int,
        notes: Int
    ) = FloorExportSnapshot(
        id = id,
        name = id,
        mapFile = File("$id.png"),
        esxFloorPlanId = "floor-$id",
        esxImageId = "image-$id",
        importedFromSource = false,
        sourceMetersPerUnit = null,
        survey = ProjectSnapshot(
            mapInfo = MapInfo("$id.png", 10, 10),
            metersPerUnit = null,
            scanPoints = List(points) { point() },
            continuousScanSessions = List(routes) { route() },
            notes = List(notes) { note() }
        )
    )

    private fun point() = ScanPoint(1L, 1f, 1f, emptyList())

    private fun route() = ContinuousScanSession("id", 1L, 2L, emptyList(), emptyList())

    private fun note() = AppNote(
        id = "note",
        text = "note",
        photoUri = null,
        photoWidth = null,
        photoHeight = null,
        photoId = null,
        x = 1f,
        y = 1f,
        pictureNoteId = "picture",
        photoFormat = null
    )
}
