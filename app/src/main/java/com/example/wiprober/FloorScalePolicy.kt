package com.example.wiprober

/** Null means uncalibrated in existing workspace schemas; 0.025 can be a real calibration. */
object FloorScalePolicy {
    const val ASSUMED_METERS_PER_PIXEL = 0.025

    fun isConfirmed(scale: Double?): Boolean = scale != null && scale.isFinite() && scale > 0.0

    fun forExport(scale: Double?): Double =
        scale?.takeIf(::isConfirmed) ?: ASSUMED_METERS_PER_PIXEL

    fun uncalibratedFloors(snapshot: ProjectExportSnapshot): List<FloorExportSnapshot> =
        snapshot.floors.filterNot { isConfirmed(it.survey.metersPerUnit) }
}
