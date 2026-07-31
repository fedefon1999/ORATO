package com.orato.app.metrics

/**
 * Central configuration for upper-body metrics.
 *
 * All formulas and provisional score thresholds live here so UI and engine stay
 * free of magic numbers. Scores are **engineering heuristics for practice
 * feedback**, not medical or scientific evaluations. Natural body asymmetry
 * must never be labeled as a health problem.
 */
object BodyMetricsConfig {

    // -------------------------------------------------------------------------
    // Landmark validity
    // -------------------------------------------------------------------------

    /**
     * Minimum MediaPipe visibility to treat a landmark as present.
     * Missing / low-confidence landmarks are never coerced to zero coordinates.
     */
    const val MIN_LANDMARK_VISIBILITY: Float = 0.5f

    /**
     * Minimum shoulder width in normalized image units (0–1).
     * Below this, distance-based ratios are discarded (invalid normalization base).
     */
    const val MIN_SHOULDER_WIDTH: Float = 0.02f

    // -------------------------------------------------------------------------
    // Temporal smoothing (exponential moving average)
    // -------------------------------------------------------------------------

    /**
     * EMA: `smoothed = alpha * sample + (1 - alpha) * previous`.
     * Applied to landmark x/y after visibility checks to reduce jitter.
     * Alpha closer to 1 → more responsive; closer to 0 → smoother.
     */
    const val LANDMARK_EMA_ALPHA: Float = 0.25f

    // -------------------------------------------------------------------------
    // Jump rejection (implausible single-frame motion)
    // -------------------------------------------------------------------------

    /**
     * Maximum accepted inter-frame displacement of a tracked point, expressed in
     * units of current shoulder width. Larger jumps are treated as detection
     * glitches and excluded from movement / stability / gesture aggregates.
     */
    const val MAX_NORMALIZED_JUMP: Float = 0.35f

    // -------------------------------------------------------------------------
    // Session sufficiency
    // -------------------------------------------------------------------------

    /**
     * Minimum number of valid torso frames required before provisional scores
     * and percentages are shown. Below this → “Dati insufficienti”.
     */
    const val MIN_SAMPLES_FOR_SCORE: Int = 30

    // -------------------------------------------------------------------------
    // Shoulder balance
    // -------------------------------------------------------------------------

    /**
     * Raw tilt = |leftShoulder.y − rightShoulder.y| / shoulderWidth.
     * Session aggregate = median of EMA-smoothed tilt samples (ignores brief spikes).
     *
     * Score (lower tilt → higher score), linear between:
     * - [SHOULDER_TILT_GOOD_MAX] → 100
     * - [SHOULDER_TILT_BAD_MIN] → 0
     */
    const val SHOULDER_TILT_GOOD_MAX: Float = 0.05f
    const val SHOULDER_TILT_BAD_MIN: Float = 0.20f

    // -------------------------------------------------------------------------
    // Trunk inclination
    // -------------------------------------------------------------------------

    /**
     * Absolute lateral inclination (degrees) between the trunk axis
     * (hip midpoint → shoulder midpoint) and the image vertical axis.
     * Session aggregate = median of absolute inclination samples.
     *
     * Score (lower angle → higher score), linear between:
     * - [TRUNK_ANGLE_GOOD_MAX_DEG] → 100
     * - [TRUNK_ANGLE_BAD_MIN_DEG] → 0
     */
    const val TRUNK_ANGLE_GOOD_MAX_DEG: Float = 5f
    const val TRUNK_ANGLE_BAD_MIN_DEG: Float = 25f

    // -------------------------------------------------------------------------
    // Trunk stability (lateral sway)
    // -------------------------------------------------------------------------

    /**
     * Per-frame sway = mean of |Δx| of shoulder midpoint and hip midpoint,
     * each divided by shoulder width. Jump frames are excluded.
     * Session aggregate = mean sway over accepted frames.
     *
     * Score (lower sway → higher score), linear between:
     * - [STABILITY_SWAY_GOOD_MAX] → 100
     * - [STABILITY_SWAY_BAD_MIN] → 0
     *
     * Thresholds target excessive lateral sway, not normal micro-movements.
     */
    const val STABILITY_SWAY_GOOD_MAX: Float = 0.015f
    const val STABILITY_SWAY_BAD_MIN: Float = 0.08f

    // -------------------------------------------------------------------------
    // Gesture activity
    // -------------------------------------------------------------------------

    /**
     * Per-frame wrist activity = mean of accepted |Δ| of visible wrists,
     * normalized by shoulder width. Implausible jumps excluded.
     *
     * [GESTURE_MOVEMENT_THRESHOLD]: frame counts as “meaningful hand movement”
     * when activity ≥ this value.
     *
     * Classification (provisional, not “more is better”):
     * - LOW: average activity ≤ [GESTURE_LOW_MAX_AVG] AND active-time ratio
     *   ≤ [GESTURE_LOW_MAX_ACTIVE_RATIO]
     * - HIGH: average activity ≥ [GESTURE_HIGH_MIN_AVG] OR active-time ratio
     *   ≥ [GESTURE_HIGH_MIN_ACTIVE_RATIO]
     * - BALANCED: otherwise
     */
    const val GESTURE_MOVEMENT_THRESHOLD: Float = 0.02f
    const val GESTURE_LOW_MAX_AVG: Float = 0.01f
    const val GESTURE_HIGH_MIN_AVG: Float = 0.06f
    const val GESTURE_LOW_MAX_ACTIVE_RATIO: Float = 0.15f
    const val GESTURE_HIGH_MIN_ACTIVE_RATIO: Float = 0.55f
}
