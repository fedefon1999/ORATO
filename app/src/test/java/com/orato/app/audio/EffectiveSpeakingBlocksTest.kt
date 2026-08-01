package com.orato.app.audio

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class EffectiveSpeakingBlocksTest {

    private fun speech(startMs: Long, endMs: Long): AudioSegment =
        AudioSegment(AudioSegmentKind.Speech, startMs, endMs)

    @Test
    fun continuousSpeech_oneBlock() {
        val timeline = EffectiveSpeakingBlocks.build(listOf(speech(0, 5_000)))
        assertEquals(1, timeline.blocks.size)
        assertEquals(SpeakingBlock(0, 5_000), timeline.blocks.single())
        assertEquals(5_000L, timeline.effectiveSpeechDurationMs)
        assertEquals(5_000L, timeline.rawVoicedDurationMs)
        assertEquals(0L, timeline.briefGapsMergedMs)
        assertTrue(timeline.significantPauseDurationsMs.isEmpty())
        assertEquals(5_000L, timeline.longestContinuousSpeechMs)
    }

    @Test
    fun briefGaps_100_300_499_mergeIntoSameBlock_andEffectiveIncludesGap() {
        for (gapMs in listOf(100L, 300L, 499L)) {
            val timeline = EffectiveSpeakingBlocks.build(
                listOf(speech(0, 1_000), speech(1_000 + gapMs, 2_000 + gapMs)),
            )
            assertEquals("gap=$gapMs", 1, timeline.blocks.size)
            assertEquals("gap=$gapMs", SpeakingBlock(0, 2_000 + gapMs), timeline.blocks.single())
            assertEquals("gap=$gapMs", 2_000L, timeline.rawVoicedDurationMs)
            assertEquals("gap=$gapMs", 2_000L + gapMs, timeline.effectiveSpeechDurationMs)
            assertEquals("gap=$gapMs", gapMs, timeline.briefGapsMergedMs)
            assertTrue("gap=$gapMs", timeline.significantPauseDurationsMs.isEmpty())
        }
    }

    @Test
    fun significantGaps_500_800_1500_split_firstBlockEndsAtSilenceOnset() {
        for (gapMs in listOf(500L, 800L, 1_500L)) {
            val secondStart = 1_000L + gapMs
            val timeline = EffectiveSpeakingBlocks.build(
                listOf(speech(0, 1_000), speech(secondStart, secondStart + 1_000)),
            )
            assertEquals("gap=$gapMs", 2, timeline.blocks.size)
            assertEquals("gap=$gapMs", SpeakingBlock(0, 1_000), timeline.blocks[0])
            assertEquals("gap=$gapMs", SpeakingBlock(secondStart, secondStart + 1_000), timeline.blocks[1])
            assertEquals("gap=$gapMs", 2_000L, timeline.rawVoicedDurationMs)
            assertEquals("gap=$gapMs", 2_000L, timeline.effectiveSpeechDurationMs)
            assertEquals("gap=$gapMs", 0L, timeline.briefGapsMergedMs)
            assertEquals("gap=$gapMs", listOf(gapMs), timeline.significantPauseDurationsMs)
        }
    }

    @Test
    fun multipleBriefGaps_mergeIntoOneBlock() {
        val timeline = EffectiveSpeakingBlocks.build(
            listOf(
                speech(0, 500),
                speech(700, 1_200),   // gap 200
                speech(1_400, 2_000), // gap 200
                speech(2_300, 3_000), // gap 300
            ),
        )
        assertEquals(1, timeline.blocks.size)
        assertEquals(SpeakingBlock(0, 3_000), timeline.blocks.single())
        assertEquals(700L, timeline.briefGapsMergedMs) // 200+200+300
        assertEquals(2_300L, timeline.rawVoicedDurationMs)
        assertEquals(3_000L, timeline.effectiveSpeechDurationMs)
        assertTrue(timeline.significantPauseDurationsMs.isEmpty())
    }

    @Test
    fun briefAndMediumGaps_sameTimeline() {
        val timeline = EffectiveSpeakingBlocks.build(
            listOf(
                speech(0, 1_000),
                speech(1_300, 2_000), // brief 300 → merge
                speech(2_800, 3_500), // medium 800 → split
            ),
        )
        assertEquals(2, timeline.blocks.size)
        assertEquals(SpeakingBlock(0, 2_000), timeline.blocks[0])
        assertEquals(SpeakingBlock(2_800, 3_500), timeline.blocks[1])
        assertEquals(300L, timeline.briefGapsMergedMs)
        assertEquals(listOf(800L), timeline.significantPauseDurationsMs)
        assertEquals(2_400L, timeline.rawVoicedDurationMs) // 1000+700+700
        assertEquals(2_700L, timeline.effectiveSpeechDurationMs) // 2000 + 700
    }

    @Test
    fun leadingAndTrailingSilence_excluded_onlySpeechSegmentsPassed() {
        // Callers pass only qualified speech segments; leading/trailing silence never enters.
        val timeline = EffectiveSpeakingBlocks.build(
            listOf(
                speech(2_000, 3_000),
                speech(3_200, 4_000), // brief internal gap
                speech(5_000, 6_000), // significant gap after
            ),
        )
        assertEquals(2, timeline.blocks.size)
        assertEquals(SpeakingBlock(2_000, 4_000), timeline.blocks[0])
        assertEquals(SpeakingBlock(5_000, 6_000), timeline.blocks[1])
        assertEquals(listOf(1_000L), timeline.significantPauseDurationsMs)
        // Blocks never start at 0 or extend past last speech end.
        assertTrue(timeline.blocks.first().startMs >= 2_000)
        assertTrue(timeline.blocks.last().endMs <= 6_000)
    }

    @Test
    fun noOverlappingOrNegativeDurations() {
        val timeline = EffectiveSpeakingBlocks.build(
            listOf(
                speech(0, 1_000),
                speech(800, 1_500),   // overlap — sanitized
                speech(2_500, 3_000), // significant gap
                speech(3_500, 3_200), // negative / inverted — dropped by filter (durationMs==0 coerced, but end<=start skipped in sanitize)
            ),
        )
        for (block in timeline.blocks) {
            assertTrue("block=$block", block.endMs > block.startMs)
            assertTrue("block=$block", block.durationMs > 0L)
        }
        for (i in 0 until timeline.blocks.size - 1) {
            assertTrue(
                "overlap ${timeline.blocks[i]} / ${timeline.blocks[i + 1]}",
                timeline.blocks[i].endMs <= timeline.blocks[i + 1].startMs,
            )
        }
    }

    @Test
    fun rawVoiced_vs_effective_effectiveGteRawWhenBriefGapsExist() {
        val withBrief = EffectiveSpeakingBlocks.build(
            listOf(speech(0, 1_000), speech(1_250, 2_000)),
        )
        assertTrue(withBrief.effectiveSpeechDurationMs >= withBrief.rawVoicedDurationMs)
        assertEquals(250L, withBrief.effectiveSpeechDurationMs - withBrief.rawVoicedDurationMs)

        val noBrief = EffectiveSpeakingBlocks.build(
            listOf(speech(0, 1_000), speech(2_000, 3_000)),
        )
        assertEquals(noBrief.rawVoicedDurationMs, noBrief.effectiveSpeechDurationMs)
    }

    @Test
    fun significantPauses_doNotIncludeBriefGaps() {
        val timeline = EffectiveSpeakingBlocks.build(
            listOf(
                speech(0, 500),
                speech(800, 1_200),   // brief 300
                speech(1_800, 2_200), // medium 600
            ),
        )
        assertTrue(timeline.significantPauseDurationsMs.none { it < AudioMetricsConfig.SIGNIFICANT_PAUSE_MIN_MS })
        assertEquals(listOf(600L), timeline.significantPauseDurationsMs)
    }

    @Test
    fun longPauses_areSubsetOfSignificant() {
        val timeline = EffectiveSpeakingBlocks.build(
            listOf(
                speech(0, 500),
                speech(1_200, 1_700),  // medium 700
                speech(3_500, 4_000),  // long 1800
            ),
        )
        val significant = timeline.significantPauseDurationsMs
        val long = significant.filter { it >= AudioMetricsConfig.LONG_PAUSE_THRESHOLD_MS }
        assertEquals(listOf(700L, 1_800L), significant)
        assertEquals(listOf(1_800L), long)
        assertTrue(significant.containsAll(long))
    }

    @Test
    fun longestContinuous_usesMergedBlocks() {
        val timeline = EffectiveSpeakingBlocks.build(
            listOf(
                speech(0, 800),
                speech(1_000, 1_600),  // brief → merged block duration 1600
                speech(3_000, 3_400),  // significant → separate short block 400
            ),
        )
        assertEquals(1_600L, timeline.longestContinuousSpeechMs)
        assertEquals(1_600L, timeline.blocks.maxOf { it.durationMs })
        // Without merge, longest raw segment would be 800 — merged wins.
        assertTrue(timeline.longestContinuousSpeechMs!! > 800L)
    }
}
