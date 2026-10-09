package com.example.wiprober

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SurveyControlsPresenterTest {
    @Test
    fun stopAndGoShowsTapHintWithoutRouteAction() {
        val presentation = presentSurveyControls(false, false, false, "0:00", "0 scans")

        assertEquals(R.string.scan_hint_stop_and_go, presentation.statusTextRes)
        assertFalse(presentation.actionVisible)
    }

    @Test
    fun continuousIdleOffersExplicitStart() {
        val presentation = presentSurveyControls(true, false, false, "0:00", "0 scans")

        assertEquals(R.string.continuous_ready, presentation.statusTextRes)
        assertEquals(R.string.start_route, presentation.actionTextRes)
        assertEquals(R.drawable.ic_route_start, presentation.actionIconRes)
        assertEquals(SurveyActionTone.PRIMARY, presentation.actionTone)
    }

    @Test
    fun awaitingStartOffersCancellationAndMapInstruction() {
        val presentation = presentSurveyControls(true, true, false, "0:00", "0 scans")

        assertEquals(R.string.select_route_start, presentation.statusTextRes)
        assertEquals(R.string.action_cancel_start, presentation.actionTextRes)
        assertNull(presentation.actionIconRes)
    }

    @Test
    fun activeRouteUsesSquareStopActionAndShowsProgress() {
        val presentation = presentSurveyControls(true, false, true, "1:05", "7 scans")

        assertTrue(presentation.actionVisible)
        assertEquals(R.string.stop_route, presentation.actionTextRes)
        assertEquals(R.drawable.ic_stop, presentation.actionIconRes)
        assertEquals(SurveyActionTone.DESTRUCTIVE, presentation.actionTone)
        assertEquals(listOf("1:05", "7 scans"), presentation.statusArguments)
    }
}
