package com.orato.app.audio

/**
 * Effective speaking blocks: brief gaps below [mediumPauseThresholdMs] stay inside
 * the current block; a medium/significant pause closes the block at silence onset.
 */
data class SpeakingBlock(
    val startMs: Long,
    val endMs: Long,
) {
    val durationMs: Long get() = (endMs - startMs).coerceAtLeast(0L)
}

data class EffectiveSpeakingTimeline(
    val blocks: List<SpeakingBlock>,
    /** Sum of raw qualified VAD speech segments (excludes all silence). */
    val rawVoicedDurationMs: Long,
    /** Sum of merged speaking-block durations (includes brief natural gaps). */
    val effectiveSpeechDurationMs: Long,
    /** Longest merged speaking block. */
    val longestContinuousSpeechMs: Long?,
    /** Inter-block gaps (≥ medium threshold), used for pause metrics. */
    val significantPauseDurationsMs: List<Long>,
    /** Total duration of brief gaps that were merged into speaking blocks. */
    val briefGapsMergedMs: Long,
) {
    companion object {
        fun empty(): EffectiveSpeakingTimeline =
            EffectiveSpeakingTimeline(
                blocks = emptyList(),
                rawVoicedDurationMs = 0L,
                effectiveSpeechDurationMs = 0L,
                longestContinuousSpeechMs = null,
                significantPauseDurationsMs = emptyList(),
                briefGapsMergedMs = 0L,
            )
    }
}

object EffectiveSpeakingBlocks {

    /**
     * Builds effective speaking blocks from finalized **qualified** speech segments.
     *
     * @param mediumPauseThresholdMs gaps ≥ this split blocks (default =
     *   [AudioMetricsConfig.SIGNIFICANT_PAUSE_MIN_MS] = 500).
     */
    fun build(
        qualifiedSpeechSegments: List<AudioSegment>,
        mediumPauseThresholdMs: Int = AudioMetricsConfig.SIGNIFICANT_PAUSE_MIN_MS,
    ): EffectiveSpeakingTimeline {
        val speech = qualifiedSpeechSegments
            .filter { it.kind == AudioSegmentKind.Speech && it.durationMs > 0L }
            .sortedBy { it.startMs }
        if (speech.isEmpty()) return EffectiveSpeakingTimeline.empty()

        val rawVoiced = speech.sumOf { it.durationMs }
        val blocks = mutableListOf<SpeakingBlock>()
        var blockStart = speech[0].startMs
        var blockEnd = speech[0].endMs
        var briefMerged = 0L

        for (i in 1 until speech.size) {
            val next = speech[i]
            // Prevent overlapping / backwards intervals.
            val gapStart = blockEnd
            val gap = next.startMs - gapStart
            if (gap < 0L) {
                // Overlap or unordered — extend block to cover.
                blockEnd = maxOf(blockEnd, next.endMs)
                continue
            }
            if (gap < mediumPauseThresholdMs) {
                briefMerged += gap
                blockEnd = next.endMs
            } else {
                // Close at exact silence onset (= end of last speech), not after threshold.
                if (blockEnd > blockStart) {
                    blocks.add(SpeakingBlock(blockStart, blockEnd))
                }
                blockStart = next.startMs
                blockEnd = next.endMs
            }
        }
        if (blockEnd > blockStart) {
            blocks.add(SpeakingBlock(blockStart, blockEnd))
        }

        // Deduplicate / sanitize
        val sanitized = sanitizeBlocks(blocks)
        val effective = sanitized.sumOf { it.durationMs }
        val longest = sanitized.maxOfOrNull { it.durationMs }
        val pauses = mutableListOf<Long>()
        for (i in 0 until sanitized.size - 1) {
            val gap = sanitized[i + 1].startMs - sanitized[i].endMs
            if (gap >= mediumPauseThresholdMs) {
                pauses.add(gap)
            }
        }

        return EffectiveSpeakingTimeline(
            blocks = sanitized,
            rawVoicedDurationMs = rawVoiced,
            effectiveSpeechDurationMs = effective,
            longestContinuousSpeechMs = longest,
            significantPauseDurationsMs = pauses,
            briefGapsMergedMs = briefMerged,
        )
    }

    private fun sanitizeBlocks(blocks: List<SpeakingBlock>): List<SpeakingBlock> {
        if (blocks.isEmpty()) return emptyList()
        val out = mutableListOf<SpeakingBlock>()
        for (b in blocks.sortedBy { it.startMs }) {
            if (b.endMs <= b.startMs) continue
            val last = out.lastOrNull()
            if (last != null && b.startMs < last.endMs) {
                // Merge accidental overlap.
                out[out.lastIndex] = SpeakingBlock(last.startMs, maxOf(last.endMs, b.endMs))
            } else {
                out.add(b)
            }
        }
        return out
    }
}
