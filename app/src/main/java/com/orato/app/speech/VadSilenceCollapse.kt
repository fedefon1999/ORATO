package com.orato.app.speech

/**
 * Optional VAD-guided silence collapse for Whisper analysis only.
 *
 * Disabled by default ([SpeechConfig.ENABLE_VAD_SILENCE_COLLAPSE]).
 * Must not mutate the original WAV. Physical-device A/B required before enabling.
 *
 * Rules (when enabled):
 * - preserve ≥250 ms around speech regions
 * - preserve pauses &lt; 1.5 s
 * - collapse silence &gt; 1.5 s to ≈300 ms
 * - skip entirely when VAD quality is invalid
 */
object VadSilenceCollapse {
    const val PRESERVE_EDGE_MS: Int = 250
    const val SHORT_PAUSE_KEEP_MS: Int = 1_500
    const val COLLAPSED_SILENCE_MS: Int = 300

    data class SpeechRegion(val startMs: Long, val endMs: Long)

    /**
     * Returns [samples] unchanged while the feature flag is off.
     * Future: rewrite floats using [regions] without touching the source file.
     */
    fun maybeCollapse(
        samples: FloatArray,
        sampleRateHz: Int,
        regions: List<SpeechRegion>,
        vadQualityValid: Boolean,
    ): FloatArray {
        if (!SpeechConfig.ENABLE_VAD_SILENCE_COLLAPSE) return samples
        if (!vadQualityValid || regions.isEmpty()) return samples
        // Placeholder — keep passthrough until device A/B validates filler/word parity.
        return samples
    }
}
