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
    // Landmark usability (shared)
    // -------------------------------------------------------------------------

    /**
     * Normalized image bounds treated as “inside the camera frame”.
     * Landmarks outside [[IN_FRAME_MIN], [IN_FRAME_MAX]] are not visually
     * observable even if MediaPipe predicts coordinates.
     */
    const val IN_FRAME_MIN: Float = 0.02f
    const val IN_FRAME_MAX: Float = 0.98f

    /**
     * Minimum MediaPipe presence when the model supplies a presence score.
     * When presence is absent (null), the check is skipped — never coerced to 0.
     */
    const val MIN_LANDMARK_PRESENCE: Float = 0.65f

    /**
     * Minimum visibility for torso landmarks (shoulders / hips).
     * Higher than overlay drawing threshold to reject head-only false positives.
     */
    const val TORSO_MIN_VISIBILITY: Float = 0.65f

    /**
     * Minimum wrist visibility for hand-visible decisions.
     * Inferred wrists without strong finger evidence never count as visible.
     */
    const val WRIST_MIN_VISIBILITY: Float = 0.80f

    /**
     * Minimum visibility for each usable finger landmark (thumb / index / pinky).
     */
    const val HAND_FINGER_MIN_VISIBILITY: Float = 0.75f

    /**
     * Average visibility of usable hand landmarks (wrist + qualifying fingers)
     * must reach this floor. Uncertain hands stay NOT visible.
     */
    const val HAND_AVG_VISIBILITY_MIN: Float = 0.78f

    /**
     * Legacy alias kept for elbow / supporting checks outside the strict hand rule.
     */
    const val HAND_LANDMARK_MIN_VISIBILITY: Float = 0.65f

    /**
     * Legacy / general floor used by non-torso helpers. Prefer the specific
     * torso/hand thresholds above for validation.
     */
    const val MIN_LANDMARK_VISIBILITY: Float = 0.5f

    // -------------------------------------------------------------------------
    // Torso geometry
    // -------------------------------------------------------------------------

    /**
     * Minimum shoulder width in normalized image units for a realistic torso.
     * Below this, the frame is rejected (e.g. face-only / tiny false torso).
     * Also used as the normalization base for distance-based metrics.
     */
    const val MIN_SHOULDER_WIDTH: Float = 0.06f

    /**
     * Minimum |y_shoulderMid − y_hipMid| in normalized image height.
     * Rejects collapsed / head-only geometries where hips are invented nearby.
     */
    const val MIN_SHOULDER_TO_HIP_VERTICAL: Float = 0.12f

    /**
     * Expand the shoulder–hip torso polygon by this normalized margin when
     * testing “inside / immediately behind” the torso for occlusion.
     */
    const val TORSO_OCCLUSION_MARGIN: Float = 0.04f

    // -------------------------------------------------------------------------
    // Temporal hysteresis — torso UI / latched detection
    // -------------------------------------------------------------------------

    /**
     * Become latched-valid after ≥ [TORSO_VALID_COUNT] valid raw results among
     * the latest [TORSO_VALID_WINDOW] processed results.
     */
    const val TORSO_VALID_WINDOW: Int = 7
    const val TORSO_VALID_COUNT: Int = 5

    /**
     * Become latched-invalid after ≥ [TORSO_INVALID_COUNT] invalid raw results
     * among the latest [TORSO_INVALID_WINDOW] processed results.
     */
    const val TORSO_INVALID_WINDOW: Int = 4
    const val TORSO_INVALID_COUNT: Int = 3

    // -------------------------------------------------------------------------
    // Conservative hand visibility
    // -------------------------------------------------------------------------

    /**
     * Positive evidence required for [HAND_VISIBLE_STREAK_ON] consecutive
     * processed results before handVisible becomes true.
     */
    const val HAND_VISIBLE_STREAK_ON: Int = 4

    /**
     * A single invalid / occluded / uncertain result clears handVisible.
     */
    const val HAND_VISIBLE_STREAK_OFF: Int = 1

    /** Among thumb / index / pinky, how many must be usable and in-frame. */
    const val HAND_MIN_FINGER_LANDMARKS: Int = 2

    /**
     * Minimum Euclidean distance between at least one pair of usable finger
     * landmarks. Collapsed inferred clusters fail this check.
     */
    const val HAND_MIN_FINGER_SPREAD: Float = 0.028f

    /**
     * Minimum max(width, height) of the axis-aligned bbox over wrist + usable
     * fingers. Rejects near-zero collapsed hand predictions.
     */
    const val HAND_MIN_BBOX_SIZE: Float = 0.035f

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
