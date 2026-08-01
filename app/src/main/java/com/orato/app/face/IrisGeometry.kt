package com.orato.app.face

import kotlin.math.abs
import kotlin.math.hypot
import kotlin.math.sqrt

/**
 * Signed iris geometry in a local per-eye coordinate system.
 *
 * ## Coordinate system (per eye)
 *
 * Origin: outer eye corner.
 * Local X: normalized vector from outer → inner corner (along the eye width).
 * Local Y: normalized perpendicular to local X in image space, pointing toward
 *          the upper eyelid (sign corrected so +Y is “up” in image coords where
 *          +Y increases downward — therefore localY = (-localX.y, localX.x)
 *          or flipped to align with upper→lower eyelid direction).
 *
 * ```
 * horizontalRatio = dot(irisCenter - origin, localX) / eyeWidth
 * verticalRatio   = dot(irisCenter - origin, localY) / eyeHeight
 * ```
 *
 * Ratios are **signed**:
 * - horizontal ≈ 0.5 when iris is midway outer→inner (subject-relative)
 * - vertical ≈ 0.5 when iris is midway upper→lower
 *
 * Front-camera UI mirroring is applied once in overlay mapping — iris ratios
 * are computed in upright, unmirrored image space (same as MediaPipe input).
 *
 * Invalid geometry returns null — never a fabricated zero.
 */
object IrisGeometry {

    data class Point(val x: Float, val y: Float)

    /**
     * @param horizontal signed normalized projection on local X (≈0.5 centered)
     * @param vertical signed normalized projection on local Y (≈0.5 centered)
     */
    data class IrisRatios(
        val horizontal: Float,
        val vertical: Float,
    )

    fun distance(a: Point, b: Point): Float =
        hypot((a.x - b.x).toDouble(), (a.y - b.y).toDouble()).toFloat()

    /**
     * Signed local-axis iris ratios. Returns null when geometry is invalid.
     *
     * @param flipHorizontal when true, flips the local X sign (for consistent
     *        left/right eye orientation in subject space). Left eye typically
     *        uses false; right eye may use true so both eyes share the same
     *        “toward nose / toward temple” signed meaning relative to midline.
     *        Default: false for both — outer→inner defines +X identically.
     */
    fun eyeRatios(
        outerCorner: Point,
        innerCorner: Point,
        upperLid: Point,
        lowerLid: Point,
        irisCenter: Point,
        flipHorizontal: Boolean = false,
    ): IrisRatios? {
        if (!isFinite(outerCorner) || !isFinite(innerCorner) ||
            !isFinite(upperLid) || !isFinite(lowerLid) || !isFinite(irisCenter)
        ) {
            return null
        }

        val eyeWidth = distance(outerCorner, innerCorner)
        if (eyeWidth < FaceMetricsConfig.MIN_EYE_WIDTH) return null

        val eyeHeight = distance(upperLid, lowerLid)
        if (eyeHeight < FaceMetricsConfig.MIN_EYE_HEIGHT) return null

        var localXx = (innerCorner.x - outerCorner.x) / eyeWidth
        var localXy = (innerCorner.y - outerCorner.y) / eyeWidth
        if (flipHorizontal) {
            localXx = -localXx
            localXy = -localXy
        }

        // Candidate perpendicular (rotate 90° CCW in image space).
        var localYx = -localXy
        var localYy = localXx

        // Align +Y with upper→lower so vertical increases downward in image coords
        // matches eyelid reference: project upper→lower onto candidate Y.
        val lidDx = lowerLid.x - upperLid.x
        val lidDy = lowerLid.y - upperLid.y
        val align = localYx * lidDx + localYy * lidDy
        if (align < 0f) {
            localYx = -localYx
            localYy = -localYy
        }
        val yNorm = sqrt((localYx * localYx + localYy * localYy).toDouble()).toFloat()
        if (yNorm < 1e-6f) return null
        localYx /= yNorm
        localYy /= yNorm

        val dx = irisCenter.x - outerCorner.x
        val dy = irisCenter.y - outerCorner.y
        val horizontal = (dx * localXx + dy * localXy) / eyeWidth
        // Vertical: project from upper lid along local Y, normalized by eye height.
        val vx = irisCenter.x - upperLid.x
        val vy = irisCenter.y - upperLid.y
        val vertical = (vx * localYx + vy * localYy) / eyeHeight

        if (!horizontal.isFinite() || !vertical.isFinite()) return null
        val maxAbs = FaceMetricsConfig.IRIS_PLAUSIBLE_ABS_MAX
        if (abs(horizontal) > maxAbs || abs(vertical) > maxAbs) return null
        // Plausible open-eye range roughly [-0.15, 1.15] after outer-origin / upper-origin
        if (horizontal !in -0.15f..1.15f || vertical !in -0.15f..1.15f) return null

        return IrisRatios(horizontal = horizontal, vertical = vertical)
    }

    /** Legacy unsigned helpers retained for tests that only need distance. */
    fun horizontalRatio(
        outerCorner: Point,
        innerCorner: Point,
        irisCenter: Point,
    ): Float? = eyeRatios(
        outerCorner = outerCorner,
        innerCorner = innerCorner,
        upperLid = Point(outerCorner.x, outerCorner.y - 0.01f),
        lowerLid = Point(outerCorner.x, outerCorner.y + 0.01f),
        irisCenter = irisCenter,
    )?.horizontal

    fun verticalRatio(
        upperLid: Point,
        lowerLid: Point,
        irisCenter: Point,
    ): Float? {
        val midOuter = Point(upperLid.x - 0.05f, (upperLid.y + lowerLid.y) / 2f)
        val midInner = Point(upperLid.x + 0.05f, (upperLid.y + lowerLid.y) / 2f)
        return eyeRatios(
            outerCorner = midOuter,
            innerCorner = midInner,
            upperLid = upperLid,
            lowerLid = lowerLid,
            irisCenter = irisCenter,
        )?.vertical
    }

    private fun isFinite(p: Point): Boolean =
        p.x.isFinite() && p.y.isFinite()

    const val PLAUSIBLE_MIN = -0.15f
    const val PLAUSIBLE_MAX = 1.15f
}
