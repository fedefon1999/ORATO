package com.orato.app.face

import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min

/**
 * Normalized iris geometry relative to eye corners / eyelids.
 *
 * Horizontal ratio:
 *   distance(outerEyeCorner, irisCenter) / distance(outerEyeCorner, innerEyeCorner)
 *
 * Vertical ratio:
 *   distance(upperEyelidReference, irisCenter) / distance(upperEyelidReference, lowerEyelidReference)
 *
 * Ratios are unitless in [0, 1] when geometry is valid.
 */
object IrisGeometry {

    data class Point(val x: Float, val y: Float)

    data class IrisRatios(
        val horizontal: Float,
        val vertical: Float,
    )

    fun distance(a: Point, b: Point): Float =
        hypot((a.x - b.x).toDouble(), (a.y - b.y).toDouble()).toFloat()

    fun horizontalRatio(
        outerCorner: Point,
        innerCorner: Point,
        irisCenter: Point,
    ): Float? {
        val denom = distance(outerCorner, innerCorner)
        if (denom < 1e-4f) return null
        val ratio = distance(outerCorner, irisCenter) / denom
        return ratio.takeIf { it in PLAUSIBLE_MIN..PLAUSIBLE_MAX }
    }

    fun verticalRatio(
        upperLid: Point,
        lowerLid: Point,
        irisCenter: Point,
    ): Float? {
        val denom = distance(upperLid, lowerLid)
        if (denom < 1e-4f) return null
        val ratio = distance(upperLid, irisCenter) / denom
        return ratio.takeIf { it in PLAUSIBLE_MIN..PLAUSIBLE_MAX }
    }

    fun eyeRatios(
        outerCorner: Point,
        innerCorner: Point,
        upperLid: Point,
        lowerLid: Point,
        irisCenter: Point,
    ): IrisRatios? {
        val h = horizontalRatio(outerCorner, innerCorner, irisCenter) ?: return null
        val v = verticalRatio(upperLid, lowerLid, irisCenter) ?: return null
        return IrisRatios(horizontal = h, vertical = v)
    }

    fun clamp01(value: Float): Float = min(1f, max(0f, value))

    const val PLAUSIBLE_MIN = -0.15f
    const val PLAUSIBLE_MAX = 1.15f
}
