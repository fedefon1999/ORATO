package com.orato.app.metrics

import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.hypot

/**
 * Immutable 2D point in MediaPipe normalized image space (x,y in ~0–1,
 * y increasing downward).
 */
data class Point2D(val x: Float, val y: Float) {
    fun distanceTo(other: Point2D): Float =
        hypot(x - other.x, y - other.y)

    fun midpointWith(other: Point2D): Point2D =
        Point2D(x = (x + other.x) / 2f, y = (y + other.y) / 2f)
}

/**
 * Pure geometry helpers for upper-body metrics.
 * All distance-based ratios use [normalizeByShoulderWidth] when the base is valid.
 */
object LandmarkGeometry {

    /**
     * Euclidean shoulder width. Returns null if width is below
     * [BodyMetricsConfig.MIN_SHOULDER_WIDTH] (invalid normalization base).
     */
    fun shoulderWidth(leftShoulder: Point2D, rightShoulder: Point2D): Float? {
        val width = leftShoulder.distanceTo(rightShoulder)
        return width.takeIf { it >= BodyMetricsConfig.MIN_SHOULDER_WIDTH }
    }

    /**
     * Divides [distance] by a valid shoulder width. Never substitutes zero for
     * missing landmarks — caller must supply validated points and width.
     */
    fun normalizeByShoulderWidth(distance: Float, shoulderWidth: Float): Float {
        require(shoulderWidth >= BodyMetricsConfig.MIN_SHOULDER_WIDTH) {
            "shoulderWidth must be >= MIN_SHOULDER_WIDTH"
        }
        return distance / shoulderWidth
    }

    /**
     * Absolute vertical shoulder difference divided by shoulder width.
     *
     * Formula: `|y_left − y_right| / shoulderWidth`
     *
     * Returns null when [shoulderWidth] is invalid.
     */
    fun shoulderTilt(
        leftShoulder: Point2D,
        rightShoulder: Point2D,
        shoulderWidth: Float,
    ): Float? {
        if (shoulderWidth < BodyMetricsConfig.MIN_SHOULDER_WIDTH) return null
        val verticalDelta = abs(leftShoulder.y - rightShoulder.y)
        return normalizeByShoulderWidth(verticalDelta, shoulderWidth)
    }

    /**
     * Absolute lateral trunk inclination in degrees.
     *
     * Trunk axis = vector from hip midpoint to shoulder midpoint.
     * Vertical axis in image space = (0, −1) (upward against +y).
     *
     * Signed lateral angle: `atan2(dx, −dy)` (degrees).
     * Reported value: absolute value (degrees from vertical).
     *
     * Degenerate trunk (near-zero length) → null.
     */
    fun trunkInclinationDegrees(
        shoulderMidpoint: Point2D,
        hipMidpoint: Point2D,
    ): Float? {
        val dx = shoulderMidpoint.x - hipMidpoint.x
        val dy = shoulderMidpoint.y - hipMidpoint.y
        val length = hypot(dx, dy)
        if (length < 1e-4f) return null
        // atan2(horizontal, upward-vertical): 0° = upright, grows with lean.
        val signedDegrees = Math.toDegrees(
            atan2(dx.toDouble(), (-dy).toDouble()),
        ).toFloat()
        return abs(signedDegrees)
    }

    /**
     * Maps a “lower is better” raw measurement to a provisional 0–100 score.
     *
     * - value ≤ [goodMax] → 100
     * - value ≥ [badMin] → 0
     * - otherwise linear interpolation between 100 and 0
     */
    fun scoreLowerIsBetter(value: Float, goodMax: Float, badMin: Float): Int {
        require(badMin > goodMax) { "badMin must be greater than goodMax" }
        return when {
            value <= goodMax -> 100
            value >= badMin -> 0
            else -> {
                val t = (value - goodMax) / (badMin - goodMax)
                ((1f - t) * 100f).toInt().coerceIn(0, 100)
            }
        }
    }

    /**
     * Median of a non-empty float list. For even sizes, average of the two
     * central values.
     */
    fun median(values: List<Float>): Float? {
        if (values.isEmpty()) return null
        val sorted = values.sorted()
        val mid = sorted.size / 2
        return if (sorted.size % 2 == 1) {
            sorted[mid]
        } else {
            (sorted[mid - 1] + sorted[mid]) / 2f
        }
    }
}
