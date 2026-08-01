package com.orato.app.audio

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class EffectiveSpeakingBlocksTest {

    private fun speech(startMs: Long, endMs: Long): AudioSegment =
        AudioSegment(AudioSegmentKind.Speech, startMs, endMs)

    @Test
    fun continuousSpeech_oneBlock_andSpanEqualsCaptureInterval() {
        val timeline = EffectiveSpeakingBlocks.build(
            listOf(speech(0, 5_000)),
            capturedDurationMs = 5_000,
        )
        assertEquals(1, timeline.blocks.size)
        assertEquals(SpeakingBlock(0, 5_000), timeline.blocks.single())
        assertEquals(5_000L, timeline.effectiveSpeechBlockDurationMs)
        assertEquals(5_000L, timeline.rawVoicedDurationMs)
        assertEquals(5_000L, timeline.speechSpanDurationMs)
        assertEquals(0L, timeline.briefGapsMergedMs)
        assertTrue(timeline.significantPauseDurationsMs.isEmpty())
        assertEquals(5_000L, timeline.longestContinuousSpeechMs)
    }

    @Test
    fun briefGaps_100_300_499_mergeIntoSameBlock_andEffectiveIncludesGap() {
        for (gapMs in listOf(100L, 300L, 499L)) {
            val timeline = EffectiveSpeakingBlocks.build(
                listOf(speech(0, 1_000), speech(1_000 + gapMs, 2_000 + gapMs)),
                capturedDurationMs = 2_000 + gapMs,
            )
            assertEquals("gap=$gapMs", 1, timeline.blocks.size)
            assertEquals("gap=$gapMs", SpeakingBlock(0, 2_000 + gapMs), timeline.blocks.single())
            assertEquals("gap=$gapMs", 2_000L, timeline.rawVoicedDurationMs)
            assertEquals("gap=$gapMs", 2_000L + gapMs, timeline.effectiveSpeechBlockDurationMs)
            assertEquals("gap=$gapMs", 2_000L + gapMs, timeline.speechSpanDurationMs)
            assertEquals("gap=$gapMs", gapMs, timeline.briefGapsMergedMs)
            assertTrue("gap=$gapMs", timeline.significantPauseDurationsMs.isEmpty())
        }
    }

    @Test
    fun significantGaps_500_800_1500_split_butRemainInsideSpeechSpan() {
        for (gapMs in listOf(500L, 800L, 1_500L)) {
            val secondStart = 1_000L + gapMs
            val lastEnd = secondStart + 1_000
            val timeline = EffectiveSpeakingBlocks.build(
                listOf(speech(0, 1_000), speech(secondStart, lastEnd)),
                capturedDurationMs = lastEnd,
            )
            assertEquals("gap=$gapMs", 2, timeline.blocks.size)
            assertEquals("gap=$gapMs", SpeakingBlock(0, 1_000), timeline.blocks[0])
            assertEquals("gap=$gapMs", SpeakingBlock(secondStart, lastEnd), timeline.blocks[1])
            assertEquals("gap=$gapMs", 2_000L, timeline.rawVoicedDurationMs)
            assertEquals("gap=$gapMs", 2_000L, timeline.effectiveSpeechBlockDurationMs)
            // Span includes the internal pause.
            assertEquals("gap=$gapMs", lastEnd, timeline.speechSpanDurationMs)
            assertTrue(
                "gap=$gapMs",
                timeline.speechSpanDurationMs >= timeline.effectiveSpeechBlockDurationMs,
            )
            assertEquals("gap=$gapMs", listOf(gapMs), timeline.significantPauseDurationsMs)
        }
    }

    @Test
    fun leadingAndTrailingSilence_excludedFromSpan() {
        val timeline = EffectiveSpeakingBlocks.build(
            listOf(speech(2_000, 3_000), speech(3_200, 4_000), speech(5_000, 6_000)),
            capturedDurationMs = 90_000,
        )
        assertEquals(2_000L, timeline.firstConfirmedSpeechStartMs)
        assertEquals(6_000L, timeline.lastConfirmedSpeechEndMs)
        assertEquals(4_000L, timeline.speechSpanDurationMs) // 6000-2000
        assertEquals(2_000L, timeline.leadingSilenceMs)
        assertEquals(84_000L, timeline.trailingSilenceMs)
        assertTrue(timeline.speechSpanDurationMs <= 90_000L)
        assertTrue(timeline.rawVoicedDurationMs <= timeline.effectiveSpeechBlockDurationMs)
        assertTrue(timeline.effectiveSpeechBlockDurationMs <= timeline.speechSpanDurationMs)
    }

    @Test
    fun almostContinuous90sReading_spanNear86sExample() {
        // Captured 90s, first speech 2s, last end 88s → span 86s
        val timeline = EffectiveSpeakingBlocks.build(
            listOf(
                speech(2_000, 40_000),
                speech(41_000, 88_000), // 1s internal medium pause
            ),
            capturedDurationMs = 90_000,
        )
        assertEquals(86_000L, timeline.speechSpanDurationMs)
        assertEquals(2_000L, timeline.leadingSilenceMs)
        assertEquals(2_000L, timeline.trailingSilenceMs)
        assertTrue(timeline.speechSpanDurationMs > timeline.effectiveSpeechBlockDurationMs)
        assertTrue(timeline.effectiveSpeechBlockDurationMs > timeline.rawVoicedDurationMs ||
            timeline.effectiveSpeechBlockDurationMs == timeline.rawVoicedDurationMs)
    }

    @Test
    fun internalBriefMediumLong_remainInsideSpeechSpan() {
        val timeline = EffectiveSpeakingBlocks.build(
            listOf(
                speech(1_000, 2_000),
                speech(2_300, 3_000),  // brief 300
                speech(3_800, 5_000),  // medium 800
                speech(7_000, 8_000),  // long 2000
            ),
            capturedDurationMs = 10_000,
        )
        assertEquals(7_000L, timeline.speechSpanDurationMs) // 8000-1000
        assertEquals(1_000L, timeline.leadingSilenceMs)
        assertEquals(2_000L, timeline.trailingSilenceMs)
        assertTrue(timeline.significantPauseDurationsMs.contains(800L))
        assertTrue(timeline.significantPauseDurationsMs.contains(2_000L))
        assertTrue(timeline.speechSpanDurationMs > timeline.effectiveSpeechBlockDurationMs)
    }

    @Test
    fun multipleBriefGaps_mergeIntoOneBlock() {
        val timeline = EffectiveSpeakingBlocks.build(
            listOf(
                speech(0, 500),
                speech(700, 1_200),
                speech(1_400, 2_000),
                speech(2_300, 3_000),
            ),
            capturedDurationMs = 3_000,
        )
        assertEquals(1, timeline.blocks.size)
        assertEquals(SpeakingBlock(0, 3_000), timeline.blocks.single())
        assertEquals(700L, timeline.briefGapsMergedMs)
        assertEquals(2_300L, timeline.rawVoicedDurationMs)
        assertEquals(3_000L, timeline.effectiveSpeechBlockDurationMs)
        assertEquals(3_000L, timeline.speechSpanDurationMs)
    }

    @Test
    fun briefAndMediumGaps_sameTimeline() {
        val timeline = EffectiveSpeakingBlocks.build(
            listOf(
                speech(0, 1_000),
                speech(1_300, 2_000),
                speech(2_800, 3_500),
            ),
            capturedDurationMs = 3_500,
        )
        assertEquals(2, timeline.blocks.size)
        assertEquals(2_400L, timeline.rawVoicedDurationMs)
        assertEquals(2_700L, timeline.effectiveSpeechBlockDurationMs)
        assertEquals(3_500L, timeline.speechSpanDurationMs)
    }

    @Test
    fun noSpeech_emptySpan() {
        val timeline = EffectiveSpeakingBlocks.build(emptyList(), capturedDurationMs = 90_000)
        assertEquals(0L, timeline.speechSpanDurationMs)
        assertNull(timeline.firstConfirmedSpeechStartMs)
        assertNull(timeline.lastConfirmedSpeechEndMs)
        assertNull(timeline.longestContinuousSpeechMs)
    }

    @Test
    fun noOverlappingOrNegativeDurations() {
        val timeline = EffectiveSpeakingBlocks.build(
            listOf(
                speech(0, 1_000),
                speech(800, 1_500),
                speech(2_500, 3_000),
                speech(3_500, 3_200),
            ),
            capturedDurationMs = 4_000,
        )
        for (block in timeline.blocks) {
            assertTrue("block=$block", block.endMs > block.startMs)
            assertTrue("block=$block", block.durationMs > 0L)
        }
        for (i in 0 until timeline.blocks.size - 1) {
            assertTrue(
                timeline.blocks[i].endMs <= timeline.blocks[i + 1].startMs,
            )
        }
    }

    @Test
    fun invariants_raw_le_block_le_span_le_capture() {
        val timeline = EffectiveSpeakingBlocks.build(
            listOf(
                speech(2_000, 10_000),
                speech(10_300, 40_000),
                speech(42_000, 80_000),
            ),
            capturedDurationMs = 90_000,
        )
        assertTrue(timeline.rawVoicedDurationMs <= timeline.effectiveSpeechBlockDurationMs)
        assertTrue(timeline.effectiveSpeechBlockDurationMs <= timeline.speechSpanDurationMs)
        assertTrue(timeline.speechSpanDurationMs <= 90_000L)
        assertTrue(timeline.firstConfirmedSpeechStartMs!! >= 0L)
        assertTrue(timeline.lastConfirmedSpeechEndMs!! <= 90_000L)
        assertTrue(timeline.lastConfirmedSpeechEndMs!! >= timeline.firstConfirmedSpeechStartMs!!)
    }

    @Test
    fun longestContinuous_usesMergedBlocks() {
        val timeline = EffectiveSpeakingBlocks.build(
            listOf(
                speech(0, 800),
                speech(1_000, 1_600),
                speech(3_000, 3_400),
            ),
            capturedDurationMs = 4_000,
        )
        assertEquals(1_600L, timeline.longestContinuousSpeechMs)
        assertTrue(timeline.longestContinuousSpeechMs!! > 800L)
    }

    @Test
    fun longPauses_areSubsetOfSignificant() {
        val timeline = EffectiveSpeakingBlocks.build(
            listOf(
                speech(0, 500),
                speech(1_200, 1_700),
                speech(3_500, 4_000),
            ),
            capturedDurationMs = 5_000,
        )
        val significant = timeline.significantPauseDurationsMs
        val long = significant.filter { it >= AudioMetricsConfig.LONG_PAUSE_THRESHOLD_MS }
        assertEquals(listOf(700L, 1_800L), significant)
        assertEquals(listOf(1_800L), long)
    }
}
