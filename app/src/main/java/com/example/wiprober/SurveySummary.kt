package com.example.wiprober

internal data class SurveySummary(
    val stopAndGoPoints: Int,
    val continuousRoutes: Int,
    val notes: Int
)

internal data class ProjectSurveySummary(
    val floors: Int,
    val stopAndGoPoints: Int,
    val continuousRoutes: Int,
    val notes: Int
)

internal fun SurveyScreenState.surveySummary(): SurveySummary = SurveySummary(
    stopAndGoPoints = scanPoints.size,
    continuousRoutes = continuousScanSessions.size,
    notes = notes.size
)

internal fun ProjectExportSnapshot.surveySummary(): ProjectSurveySummary = ProjectSurveySummary(
    floors = floors.size,
    stopAndGoPoints = floors.sumOf { it.survey.scanPoints.size },
    continuousRoutes = floors.sumOf { it.survey.continuousScanSessions.size },
    notes = floors.sumOf { it.survey.notes.size }
)
