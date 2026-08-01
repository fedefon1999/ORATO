package com.orato.app.audio

/**
 * Effective speaking blocks and discourse span timings.
 *
 * Brief gaps below [mediumPauseThresholdMs] stay inside a speaking block;
 * a medium/significant pause closes the block at silence onset.
 *
 * [speechSpanDurationMs] is first→last speech (includes all internal pauses).
 */
data class SpeakingBlock(
    val startMs: Long,
    val endMs: Long,
) {
    val durationMs: Long get() = (endMs - startMs).coerceAtLeast(0L)
}

data class EffectiveSpeakingTimeline(
    val blocks: List<SpeakingBlock>,
    /** Sum of raw qualified VAD speech segments (excludes all silence). Debug only. */
    val rawVoicedDurationMs: Long,
    /**
     * Sum of merged speaking-block durations (includes brief natural gaps only).
     * Debug / activity metric — not the WPM denominator.
     */
    val effectiveSpeechBlockDurationMs: Long,
    /**
     * Elapsed time from first confirmed speech onset to final speech offset.
     * Includes all internal pauses. Used for WPM and delivery-rate metrics.
     */
    val speechSpanDurationMs: Long,
    val firstConfirmedSpeechStartMs: Long?,
    val lastConfirmedSpeechEndMs: Long?,
    /** Silence before first speech (ms). Debug. */
    val leadingSilenceMs: Long,
    /** Silence after last speech until [capturedDurationMs] (ms). Debug. */
    val trailingSilenceMs: Long,
    /** Longest merged speaking block. */
    val longestContinuousSpeechMs: Long?,
    /** Inter-block gaps (≥ medium threshold), used for pause metrics. */
    val significantPauseDurationsMs: List<Long>,
    /** Total duration of brief gaps merged into speaking blocks. Debug. */
    val briefGapsMergedMs: Long,
) {
    /** @deprecated Prefer [effectiveSpeechBlockDurationMs]. */
    val effectiveSpeechDurationMs: Long get() = effectiveSpeechBlockDurationMs

    companion object {
        fun empty(capturedDurationMs: Long = 0L): EffectiveSpeakingTimeline =
            EffectiveSpeakingTimeline(
                blocks = emptyList(),
                rawVoicedDurationMs = 0L,
                effectiveSpeechBlockDurationMs = 0L,
                speechSpanDurationMs = 0L,
                firstConfirmedSpeechStartMs = null,
                lastConfirmedSpeechEndMs = null,
                leadingSilenceMs = capturedDurationMs.coerceAtLeast(0L),
                trailingSilenceMs = 0L,
                longestContinuousSpeechMs = null,
                significantPauseDurationsMs = emptyList(),
                briefGapsMergedMs = 0L,
            )
    }
}

object EffectiveSpeakingBlocks {

    /**
     * Builds effective speaking blocks and speech-span timing from finalized
     * **qualified** speech segments.
     *
     * @param capturedDurationMs total capture length (for trailing silence / clamps).
     * @param mediumPauseThresholdMs gaps ≥ this split blocks (default =
     *   [AudioMetricsConfig.SIGNIFICANT_PAUSE_MIN_MS] = 500).
     */
    fun build(
        qualifiedSpeechSegments: List<AudioSegment>,
        capturedDurationMs: Long = Long.MAX_VALUE / 4,
        mediumPauseThresholdMs: Int = AudioMetricsConfig.SIGNIFICANT_PAUSE_MIN_MS,
    ): EffectiveSpeakingTimeline {
        val speech = qualifiedSpeechSegments
            .filter { it.kind == AudioSegmentKind.Speech && it.durationMs > 0L }
            .sortedBy { it.startMs }
        if (speech.isEmpty()) return EffectiveSpeakingTimeline.empty(capturedDurationMs.coerceAtLeast(0L))

        val rawVoiced = speech.sumOf { it.durationMs }
        val blocks = mutableListOf<SpeakingBlock>()
        var blockStart = speech[0].startMs
        var blockEnd = speech[0].endMs
        var briefMerged = 0L

        for (i in 1 until speech.size) {
            val next = speech[i]
            val gapStart = blockEnd
            val gap = next.startMs - gapStart
            if (gap < 0L) {
                blockEnd = maxOf(blockEnd, next.endMs)
                continue
            }
            if (gap < mediumPauseThresholdMs) {
                briefMerged += gap
                blockEnd = next.endMs
            } else {
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

        val sanitized = sanitizeBlocks(blocks)
        val blockDuration = sanitized.sumOf { it.durationMs }
        val longest = sanitized.maxOfOrNull { it.durationMs }
        val pauses = mutableListOf<Long>()
        for (i in 0 until sanitized.size - 1) {
            val gap = sanitized[i + 1].startMs - sanitized[i].endMs
            if (gap >= mediumPauseThresholdMs) {
                pauses.add(gap)
            }
        }

        val firstStart = speech.first().startMs.coerceAtLeast(0L)
        val lastEnd = speech.last().endMs.coerceAtLeast(firstStart)
        val capture = capturedDurationMs.coerceAtLeast(0L)
        val clampedLast = if (capture > 0L) minOf(lastEnd, capture) else lastEnd
        val span = (clampedLast - firstStart).coerceAtLeast(0L)
        val leading = firstStart.coerceAtLeast(0L)
        val trailing = if (capture > clampedLast) capture - clampedLast else 0L

        return EffectiveSpeakingTimeline(
            blocks = sanitized,
            rawVoicedDurationMs = rawVoiced,
            effectiveSpeechBlockDurationMs = blockDuration,
            speechSpanDurationMs = span,
            firstConfirmedSpeechStartMs = firstStart,
            lastConfirmedSpeechEndMs = clampedLast,
            leadingSilenceMs = leading,
            trailingSilenceMs = trailing,
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
                out[out.lastIndex] = SpeakingBlock(last.startMs, maxOf(last.endMs, b.endMs))
            } else {
                out.add(b)
            }
        }
        return out
    }
}
