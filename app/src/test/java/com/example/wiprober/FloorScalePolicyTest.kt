package com.example.wiprober

import java.io.File
import org.junit.Assert.*
import org.junit.Test

class FloorScalePolicyTest {
    @Test
    fun checksAllFloorsNotJustActiveAndDoesNotTreatDefaultAsMissing() {
        val floors = listOf(floor("calibrated", 0.025), floor("missing", null), floor("imported", 0.05, true))
        val snapshot = ProjectExportSnapshot("id", "Project", null, floors)
        assertEquals(listOf("missing"), FloorScalePolicy.uncalibratedFloors(snapshot).map { it.name })
        assertEquals(emptyList<FloorExportSnapshot>(), FloorScalePolicy.uncalibratedFloors(snapshot.copy(
            floors = listOf(floor("imported default", 0.025, true))
        )))
    }

    @Test
    fun assumedExportScaleDoesNotConfirmCalibration() {
        listOf(null, 0.0, -1.0, Double.NaN, Double.POSITIVE_INFINITY).forEach { scale ->
            assertFalse(FloorScalePolicy.isConfirmed(scale))
            assertEquals(0.025, FloorScalePolicy.forExport(scale), 0.0)
        }
        assertEquals(0.1, FloorScalePolicy.forExport(0.1), 0.0)
    }

    private fun floor(name: String, scale: Double?, imported: Boolean = false) = FloorExportSnapshot(
        name, name, File("map.png"), "floor", "image", imported, if (imported) scale else null,
        ProjectSnapshot(MapInfo("map.png", 10, 10), scale, emptyList(), emptyList(), emptyList())
    )
}
