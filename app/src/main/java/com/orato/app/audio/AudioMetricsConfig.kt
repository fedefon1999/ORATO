package com.orato.app.audio

/**
 * Central configuration for local microphone capture and voice-activity metrics.
 *
 * All capture, frame-analysis, VAD, quality, and cache thresholds live here so
 * UI and ViewModels stay free of magic numbers. Metrics are deterministic
 * engineering measurements for practice feedback — not emotion, confidence,
 * stress, pitch, speaking rate, or filler-word inference.
 */
object AudioMetricsConfig {

    // -------------------------------------------------------------------------
    // Capture
    // -------------------------------------------------------------------------

    /** Preferred sample rate (Hz). Used when the device supports it. */
    const val PREFERRED_SAMPLE_RATE_HZ: Int = 16_000

    /**
     * Fallback sample rates tried in order when the preferred rate is unsupported
     * by [android.media.AudioRecord.getMinBufferSize].
     */
    val FALLBACK_SAMPLE_RATES_HZ: IntArray = intArrayOf(44_100, 48_000, 22_050, 11_025)

    /** Mono channel count. */
    const val CHANNEL_COUNT: Int = 1

    /** PCM 16-bit sample size in bytes. */
    const val BYTES_PER_SAMPLE: Int = 2

    /**
     * Multiplier applied to [android.media.AudioRecord.getMinBufferSize] so the
     * recorder buffer is safely larger than the platform minimum.
     */
    const val AUDIO_RECORD_BUFFER_MULTIPLIER: Int = 4

    // -------------------------------------------------------------------------
    // Frame analysis
    // -------------------------------------------------------------------------

    /**
     * Target analysis frame duration in milliseconds.
     * Actual frame sample count = round(sampleRate * FRAME_DURATION_MS / 1000).
     * Per-frame duration is always derived from the samples actually read:
     * `sampleCount * 1000 / (sampleRateHz * channelCount)`.
     */
    const val FRAME_DURATION_MS: Int = 20

    /**
     * Absolute PCM full-scale magnitude for 16-bit signed samples.
     * Used for RMS → dBFS conversion.
     */
    const val PCM_FULL_SCALE: Double = 32768.0

    /**
     * Clipping threshold as a fraction of [PCM_FULL_SCALE].
     * Samples with |s| ≥ threshold · fullScale are treated as clipped.
     */
    const val CLIPPING_THRESHOLD_RATIO: Double = 0.99

    /** Floor applied before log10 to avoid log-of-zero / −∞ dBFS. */
    const val DBFS_EPSILON: Double = 1e-12

    /** Reported dBFS when a frame is absolute silence (after epsilon floor). */
    const val SILENCE_DBFS: Double = -100.0

    // -------------------------------------------------------------------------
    // Voice activity detection (lightweight, deterministic)
    // -------------------------------------------------------------------------

    /**
     * Initial period used only to estimate the noise floor before speech
     * decisions become active (milliseconds of successfully captured audio).
     */
    const val NOISE_FLOOR_CALIBRATION_MS: Int = 400

    /**
     * EMA alpha when the observed level is **below** the current noise floor
     * (fast downward adaptation). Applied only outside active speech.
     */
    const val NOISE_FLOOR_ADAPT_ALPHA_DOWN: Double = 0.20

    /**
     * EMA alpha when the observed level is **above** the current noise floor
     * during confirmed silence (slow upward adaptation toward ambient).
     * Speech frames never raise the floor.
     */
    const val NOISE_FLOOR_ADAPT_ALPHA_UP: Double = 0.04

    /**
     * Enter-speech margin (dB) above the noise floor.
     * speech-on threshold = noiseFloor + [SPEECH_ON_MARGIN_DB].
     */
    const val SPEECH_ON_MARGIN_DB: Double = 10.0

    /**
     * Leave-speech margin (dB) above the noise floor.
     * speech-off threshold = noiseFloor + [SPEECH_OFF_MARGIN_DB].
     * Must be lower than [SPEECH_ON_MARGIN_DB] to avoid a stuck speech state.
     */
    const val SPEECH_OFF_MARGIN_DB: Double = 5.0

    /**
     * Additional release path: leave speech when level drops by at least this
     * many dB below the recent speech-peak envelope for [SPEECH_RELEASE_FRAMES].
     * Unlocks VAD after an overly quiet calibration where ambient still sits
     * above noiseFloor + off-margin.
     */
    const val SPEECH_PEAK_DROP_DB: Double = 8.0

    /** Decay applied to the speech-peak envelope each frame while in speech. */
    const val SPEECH_PEAK_DECAY: Double = 0.995

    /**
     * Number of consecutive above-on frames required to enter speech (attack).
     * At 20 ms frames, 3 ≈ 60 ms.
     */
    const val SPEECH_ATTACK_FRAMES: Int = 3

    /**
     * Number of consecutive below-off (or peak-drop) frames required to leave
     * speech (release). At 20 ms frames, 8 ≈ 160 ms.
     */
    const val SPEECH_RELEASE_FRAMES: Int = 8

    /**
     * Speech segments shorter than this (ms) are discarded and do not count
     * toward speech ratio or pause boundaries.
     */
    const val MIN_SPEECH_SEGMENT_MS: Int = 120

    /**
     * Internal silence gaps shorter than this (ms) between speech segments are
     * not counted as raw approximate pauses (debug + bucketing input).
     */
    const val MIN_SILENCE_SEGMENT_MS: Int = 200

    /**
     * User-facing “pause significative” / medium-pause floor (ms).
     * Brief natural gaps are [[MIN_SILENCE_SEGMENT_MS], [SIGNIFICANT_PAUSE_MIN_MS]).
     * Medium pauses are [[SIGNIFICANT_PAUSE_MIN_MS], [LONG_PAUSE_THRESHOLD_MS]).
     *
     * Also the speaking-block merge threshold: silence below this stays inside
     * the current effective speaking block; silence ≥ this closes the block at
     * silence onset.
     */
    const val SIGNIFICANT_PAUSE_MIN_MS: Int = 500

    /** Alias for [SIGNIFICANT_PAUSE_MIN_MS] — effective speaking-block split threshold. */
    const val MEDIUM_PAUSE_THRESHOLD_MS: Int = SIGNIFICANT_PAUSE_MIN_MS

    /**
     * Long-pause threshold (ms). Pauses ≥ this value are “pause lunghe”.
     * (VAD detection thresholds are unchanged; this is report bucketing only.)
     */
    const val LONG_PAUSE_THRESHOLD_MS: Int = 1_500

    // -------------------------------------------------------------------------
    // Input quality classification
    // -------------------------------------------------------------------------

    /**
     * Minimum successfully captured duration (ms) before metrics are considered
     * presentable. Below this → [AudioInputQuality.INSUFFICIENT_AUDIO].
     */
    const val MIN_CAPTURED_DURATION_MS: Int = 1_000

    /**
     * Minimum speech ratio (0–1) of captured time. Below this with otherwise
     * valid audio → [AudioInputQuality.INSUFFICIENT_AUDIO].
     */
    const val MIN_SPEECH_RATIO: Double = 0.05

    /**
     * Mean speech-frame dBFS at or below this → [AudioInputQuality.TOO_QUIET]
     * (when enough speech exists).
     */
    const val TOO_QUIET_MEAN_DBFS: Double = -42.0

    /**
     * Clipped-sample percentage at or above this → [AudioInputQuality.CLIPPING].
     */
    const val CLIPPING_PERCENT_THRESHOLD: Double = 1.0

    // -------------------------------------------------------------------------
    // Cache cleanup policy
    // -------------------------------------------------------------------------

    /**
     * Subdirectory under [android.content.Context.getCacheDir] for temporary
     * session WAV files: `{cacheDir}/orato_sessions/{sessionId}.wav`.
     *
     * Cleanup policy (documented for operators and the next STT milestone):
     * 1. Files live only in app cache (never public storage; no storage permission).
     * 2. Cancelled or failed recordings are deleted immediately.
     * 3. A successfully completed WAV is kept so the next speech-to-text
     *    milestone can read it — it is **not** deleted at session end.
     * 4. When a **new** practice session starts, all other WAV files in the
     *    directory are deleted (only one completed session is retained at a time).
     * 5. As a safety net, any WAV older than [CACHE_MAX_AGE_MS] is deleted on
     *    session start regardless of which file is “current”.
     */
    const val CACHE_SUBDIR: String = "orato_sessions"

    /** Maximum age of a cached session WAV before forced deletion (24 h). */
    const val CACHE_MAX_AGE_MS: Long = 24L * 60L * 60L * 1_000L
}
