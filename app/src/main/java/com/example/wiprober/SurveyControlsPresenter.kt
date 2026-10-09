package com.example.wiprober

enum class SurveyActionTone { PRIMARY, DESTRUCTIVE }

data class SurveyControlsPresentation(
    val statusTextRes: Int,
    val statusArguments: List<Any> = emptyList(),
    val actionVisible: Boolean,
    val actionTextRes: Int? = null,
    val actionIconRes: Int? = null,
    val actionTone: SurveyActionTone = SurveyActionTone.PRIMARY
)

fun presentSurveyControls(
    isContinuous: Boolean,
    isAwaitingContinuousStart: Boolean,
    isTracking: Boolean,
    elapsedLabel: String,
    scanCountLabel: String
): SurveyControlsPresentation = when {
    !isContinuous -> SurveyControlsPresentation(
        statusTextRes = R.string.scan_hint_stop_and_go,
        actionVisible = false
    )
    isTracking -> SurveyControlsPresentation(
        statusTextRes = R.string.continuous_tracking_status,
        statusArguments = listOf(elapsedLabel, scanCountLabel),
        actionVisible = true,
        actionTextRes = R.string.stop_route,
        actionIconRes = R.drawable.ic_stop,
        actionTone = SurveyActionTone.DESTRUCTIVE
    )
    isAwaitingContinuousStart -> SurveyControlsPresentation(
        statusTextRes = R.string.select_route_start,
        actionVisible = true,
        actionTextRes = R.string.action_cancel_start
    )
    else -> SurveyControlsPresentation(
        statusTextRes = R.string.continuous_ready,
        actionVisible = true,
        actionTextRes = R.string.start_route,
        actionIconRes = R.drawable.ic_route_start
    )
}
