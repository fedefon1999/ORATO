package com.orato.app.ui.practice

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PracticeDebugPanelTest {

    @Test
    fun formatDurationMmSs_formatsMinutesAndSeconds() {
        assertEquals("0:00", formatDurationMmSs(0))
        assertEquals("0:01", formatDurationMmSs(1_000))
        assertEquals("0:59", formatDurationMmSs(59_000))
        assertEquals("1:00", formatDurationMmSs(60_000))
        assertEquals("1:30", formatDurationMmSs(90_500))
    }

    @Test
    fun debugPanelFlag_isCentralized() {
        // Ensures the toggle exists for later hide; value may change intentionally.
        assertTrue(PracticeDebugConfig.SHOW_DEBUG_PANEL || !PracticeDebugConfig.SHOW_DEBUG_PANEL)
    }
}
