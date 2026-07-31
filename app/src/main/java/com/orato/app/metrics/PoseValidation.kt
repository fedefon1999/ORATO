package com.orato.app.metrics

import com.orato.app.pose.NormalizedLandmarkPoint
import com.orato.app.pose.PoseLandmarkId
import com.orato.app.pose.UpperBodyPoseFrame
import kotlin.math.abs
import kotlin.math.max

/**
 * Result of the shared landmark usability check.
 *
 * Missing / low-confidence / out-of-frame landmarks are never coerced to zeros.
 */
data class LandmarkUsability(
    val usable: Boolean,
    val inFrame: Boolean,
    val visibility: Float?,
    val presence: Float?,
    val point: Point2D?,
)

/**
 * Shared landmark usability helpers used by torso and hand validation.
 */
object LandmarkUsabilityEvaluator {

    /**
     * A landmark is usable when:
     * - it exists;
     * - x/y are finite;
     * - visibility ≥ [minVisibility];
     * - presence ≥ [minPresence] **when presence is available** (null skips);
     * - if [requireInFrame], x/y lie in [[BodyMetricsConfig.IN_FRAME_MIN],
     *   [BodyMetricsConfig.IN_FRAME_MAX]].
     */
    fun evaluate(
        point: NormalizedLandmarkPoint?,
        minVisibility: Float,
        minPresence: Float = BodyMetricsConfig.MIN_LANDMARK_PRESENCE,
        requireInFrame: Boolean = true,
    ): LandmarkUsability {
        if (point == null) {
            return LandmarkUsability(
                usable = false,
                inFrame = false,
                visibility = null,
                presence = null,
                point = null,
            )
        }
        if (!point.x.isFinite() || !point.y.isFinite()) {
            return LandmarkUsability(
                usable = false,
                inFrame = false,
                visibility = point.visibility,
                presence = point.presence,
                point = null,
            )
        }
        val inFrame = isInFrame(point.x, point.y)
        val visibilityOk = point.visibility >= minVisibility
        val presenceOk = point.presence?.let { it >= minPresence } ?: true
        val usable = visibilityOk && presenceOk && (!requireInFrame || inFrame)
        return LandmarkUsability(
            usable = usable,
            inFrame = inFrame,
            visibility = point.visibility,
            presence = point.presence,
            point = if (usable) Point2D(point.x, point.y) else null,
        )
    }

    fun isInFrame(x: Float, y: Float): Boolean {
        val min = BodyMetricsConfig.IN_FRAME_MIN
        val max = BodyMetricsConfig.IN_FRAME_MAX
        return x in min..max && y in min..max
    }

    fun fromFrame(
        frame: UpperBodyPoseFrame,
        id: PoseLandmarkId,
        minVisibility: Float,
        minPresence: Float = BodyMetricsConfig.MIN_LANDMARK_PRESENCE,
        requireInFrame: Boolean = true,
    ): LandmarkUsability = evaluate(
        point = frame.landmarks[id],
        minVisibility = minVisibility,
        minPresence = minPresence,
        requireInFrame = requireInFrame,
    )
}

/**
 * Raw (pre-hysteresis) torso validity for one frame.
 */
data class TorsoValidation(
    val rawValid: Boolean,
    val leftShoulder: LandmarkUsability,
    val rightShoulder: LandmarkUsability,
    val leftHip: LandmarkUsability,
    val rightHip: LandmarkUsability,
    val shoulderWidth: Float?,
    val shoulderToHipVertical: Float?,
) {
    /**
     * Shoulder–hip quadrilateral, or null when any corner is missing.
     * Order: left shoulder → right shoulder → right hip → left hip.
     */
    fun torsoPolygon(): List<Point2D>? {
        val ls = leftShoulder.point ?: return null
        val rs = rightShoulder.point ?: return null
        val rh = rightHip.point ?: return null
        val lh = leftHip.point ?: return null
        return listOf(ls, rs, rh, lh)
    }
}

/**
 * Conservative hand visibility assessment (pre consecutive-gate).
 * Uncertain / ambiguous evidence always yields [rawVisible] = false.
 */
data class HandValidation(
    val rawVisible: Boolean,
    val wrist: LandmarkUsability,
    val validFingerCount: Int,
    val wristPoint: Point2D?,
    val averageVisibility: Float?,
    val boundingBoxSize: Float?,
    val fingerSpread: Float?,
    val insideTorsoRegion: Boolean,
    val occludedByTorso: Boolean,
)

object PoseValidation {

    fun evaluateTorso(frame: UpperBodyPoseFrame): TorsoValidation {
        val minVis = BodyMetricsConfig.TORSO_MIN_VISIBILITY
        val leftShoulder = LandmarkUsabilityEvaluator.fromFrame(
            frame, PoseLandmarkId.LEFT_SHOULDER, minVis,
        )
        val rightShoulder = LandmarkUsabilityEvaluator.fromFrame(
            frame, PoseLandmarkId.RIGHT_SHOULDER, minVis,
        )
        val leftHip = LandmarkUsabilityEvaluator.fromFrame(
            frame, PoseLandmarkId.LEFT_HIP, minVis,
        )
        val rightHip = LandmarkUsabilityEvaluator.fromFrame(
            frame, PoseLandmarkId.RIGHT_HIP, minVis,
        )

        val ls = leftShoulder.point
        val rs = rightShoulder.point
        val lh = leftHip.point
        val rh = rightHip.point

        val shoulderWidth = if (ls != null && rs != null) {
            LandmarkGeometry.shoulderWidth(ls, rs)
        } else {
            null
        }

        val shoulderToHipVertical = if (ls != null && rs != null && lh != null && rh != null) {
            val shoulderMid = ls.midpointWith(rs)
            val hipMid = lh.midpointWith(rh)
            abs(hipMid.y - shoulderMid.y)
        } else {
            null
        }

        val geometryOk =
            shoulderWidth != null &&
                shoulderWidth >= BodyMetricsConfig.MIN_SHOULDER_WIDTH &&
                shoulderToHipVertical != null &&
                shoulderToHipVertical >= BodyMetricsConfig.MIN_SHOULDER_TO_HIP_VERTICAL

        val rawValid =
            leftShoulder.usable &&
                rightShoulder.usable &&
                leftHip.usable &&
                rightHip.usable &&
                geometryOk

        return TorsoValidation(
            rawValid = rawValid,
            leftShoulder = leftShoulder,
            rightShoulder = rightShoulder,
            leftHip = leftHip,
            rightHip = rightHip,
            shoulderWidth = shoulderWidth,
            shoulderToHipVertical = shoulderToHipVertical,
        )
    }

    fun evaluateLeftHand(
        frame: UpperBodyPoseFrame,
        torso: TorsoValidation = evaluateTorso(frame),
    ): HandValidation = evaluateHand(
        frame = frame,
        torso = torso,
        wristId = PoseLandmarkId.LEFT_WRIST,
        thumbId = PoseLandmarkId.LEFT_THUMB,
        indexId = PoseLandmarkId.LEFT_INDEX,
        pinkyId = PoseLandmarkId.LEFT_PINKY,
    )

    fun evaluateRightHand(
        frame: UpperBodyPoseFrame,
        torso: TorsoValidation = evaluateTorso(frame),
    ): HandValidation = evaluateHand(
        frame = frame,
        torso = torso,
        wristId = PoseLandmarkId.RIGHT_WRIST,
        thumbId = PoseLandmarkId.RIGHT_THUMB,
        indexId = PoseLandmarkId.RIGHT_INDEX,
        pinkyId = PoseLandmarkId.RIGHT_PINKY,
    )

    /**
     * Conservative hand visibility. MediaPipe inferred coordinates alone are
     * never enough. Every ambiguous case returns not-visible.
     *
     * Requires:
     * 1. Wrist usable, in-frame, visibility ≥ [BodyMetricsConfig.WRIST_MIN_VISIBILITY]
     * 2. ≥2 fingers usable, in-frame, visibility ≥ [BodyMetricsConfig.HAND_FINGER_MIN_VISIBILITY]
     * 3. Average visibility of usable hand landmarks ≥ [BodyMetricsConfig.HAND_AVG_VISIBILITY_MIN]
     * 4. Plausible geometry (finger spread + bounding-box size)
     * 5. Hand centroid not classified as occluded behind the torso
     */
    private fun evaluateHand(
        frame: UpperBodyPoseFrame,
        torso: TorsoValidation,
        wristId: PoseLandmarkId,
        thumbId: PoseLandmarkId,
        indexId: PoseLandmarkId,
        pinkyId: PoseLandmarkId,
    ): HandValidation {
        val uncertain = HandValidation(
            rawVisible = false,
            wrist = LandmarkUsabilityEvaluator.fromFrame(
                frame, wristId, BodyMetricsConfig.WRIST_MIN_VISIBILITY,
            ),
            validFingerCount = 0,
            wristPoint = null,
            averageVisibility = null,
            boundingBoxSize = null,
            fingerSpread = null,
            insideTorsoRegion = false,
            occludedByTorso = false,
        )

        val wrist = LandmarkUsabilityEvaluator.fromFrame(
            frame = frame,
            id = wristId,
            minVisibility = BodyMetricsConfig.WRIST_MIN_VISIBILITY,
            requireInFrame = true,
        )
        if (!wrist.usable || wrist.point == null) {
            return uncertain.copy(wrist = wrist)
        }

        val fingerEvals = listOf(thumbId, indexId, pinkyId).map { id ->
            LandmarkUsabilityEvaluator.fromFrame(
                frame = frame,
                id = id,
                minVisibility = BodyMetricsConfig.HAND_FINGER_MIN_VISIBILITY,
                requireInFrame = true,
            )
        }
        val usableFingers = fingerEvals.filter { it.usable && it.point != null }
        val validFingerCount = usableFingers.size
        if (validFingerCount < BodyMetricsConfig.HAND_MIN_FINGER_LANDMARKS) {
            // Fingers missing / weak — treat as not visible (behind back / occluded).
            val weakOcclusion = isInsideExpandedTorso(wrist.point, torso.torsoPolygon())
            return HandValidation(
                rawVisible = false,
                wrist = wrist,
                validFingerCount = validFingerCount,
                wristPoint = wrist.point,
                averageVisibility = null,
                boundingBoxSize = null,
                fingerSpread = null,
                insideTorsoRegion = weakOcclusion,
                occludedByTorso = weakOcclusion,
            )
        }

        val handPoints = listOf(wrist.point) + usableFingers.map { it.point!! }
        val visibilities = listOf(wrist.visibility!!) + usableFingers.map { it.visibility!! }
        val averageVisibility = visibilities.average().toFloat()
        val fingerSpread = maxPairwiseDistance(usableFingers.map { it.point!! })
        val boundingBoxSize = axisAlignedBBoxSize(handPoints)
        val centroid = centroidOf(handPoints)
        val polygon = torso.torsoPolygon()
        val insideTorsoRegion = isInsideExpandedTorso(centroid, polygon) ||
            isInsideExpandedTorso(wrist.point, polygon)

        val geometryPlausible =
            fingerSpread >= BodyMetricsConfig.HAND_MIN_FINGER_SPREAD &&
                boundingBoxSize >= BodyMetricsConfig.HAND_MIN_BBOX_SIZE

        val visibilityStrong =
            averageVisibility >= BodyMetricsConfig.HAND_AVG_VISIBILITY_MIN

        // Occlusion: landmarks within / immediately behind torso without strong
        // open-hand evidence (typical behind-the-back inference).
        val occludedByTorso = insideTorsoRegion && (
            !visibilityStrong ||
                !geometryPlausible ||
                averageVisibility < BodyMetricsConfig.WRIST_MIN_VISIBILITY
            )

        val rawVisible =
            visibilityStrong &&
                geometryPlausible &&
                !occludedByTorso

        return HandValidation(
            rawVisible = rawVisible,
            wrist = wrist,
            validFingerCount = validFingerCount,
            wristPoint = wrist.point,
            averageVisibility = averageVisibility,
            boundingBoxSize = boundingBoxSize,
            fingerSpread = fingerSpread,
            insideTorsoRegion = insideTorsoRegion,
            occludedByTorso = occludedByTorso,
        )
    }

    /**
     * Point-in-polygon with a uniform expansion of the torso quadrilateral
     * (shoulders + hips) so “immediately behind” the silhouette is covered.
     */
    fun isInsideExpandedTorso(point: Point2D?, polygon: List<Point2D>?): Boolean {
        if (point == null || polygon == null || polygon.size < 3) return false
        val margin = BodyMetricsConfig.TORSO_OCCLUSION_MARGIN
        val expanded = expandPolygon(polygon, margin)
        return pointInPolygon(point, expanded)
    }

    fun expandPolygon(polygon: List<Point2D>, margin: Float): List<Point2D> {
        val cx = polygon.map { it.x }.average().toFloat()
        val cy = polygon.map { it.y }.average().toFloat()
        return polygon.map { p ->
            val dx = p.x - cx
            val dy = p.y - cy
            val len = kotlin.math.hypot(dx, dy).coerceAtLeast(1e-4f)
            Point2D(
                x = p.x + margin * dx / len,
                y = p.y + margin * dy / len,
            )
        }
    }

    /** Ray-casting point-in-polygon (inclusive edges via even-odd fill). */
    fun pointInPolygon(point: Point2D, polygon: List<Point2D>): Boolean {
        var inside = false
        var j = polygon.lastIndex
        for (i in polygon.indices) {
            val pi = polygon[i]
            val pj = polygon[j]
            val intersect =
                ((pi.y > point.y) != (pj.y > point.y)) &&
                    (point.x < (pj.x - pi.x) * (point.y - pi.y) /
                        (pj.y - pi.y + 1e-12f) + pi.x)
            if (intersect) inside = !inside
            j = i
        }
        return inside
    }

    fun maxPairwiseDistance(points: List<Point2D>): Float {
        if (points.size < 2) return 0f
        var best = 0f
        for (i in points.indices) {
            for (k in i + 1 until points.size) {
                best = max(best, points[i].distanceTo(points[k]))
            }
        }
        return best
    }

    fun axisAlignedBBoxSize(points: List<Point2D>): Float {
        if (points.isEmpty()) return 0f
        val minX = points.minOf { it.x }
        val maxX = points.maxOf { it.x }
        val minY = points.minOf { it.y }
        val maxY = points.maxOf { it.y }
        return max(maxX - minX, maxY - minY)
    }

    fun centroidOf(points: List<Point2D>): Point2D {
        val cx = points.map { it.x }.average().toFloat()
        val cy = points.map { it.y }.average().toFloat()
        return Point2D(cx, cy)
    }
}

/**
 * Windowed hysteresis for torso detection status.
 *
 * - Latch ON after ≥ [validCount] valid among latest [validWindow].
 * - Latch OFF after ≥ [invalidCount] invalid among latest [invalidWindow].
 */
class TorsoHysteresis(
    private val validWindow: Int = BodyMetricsConfig.TORSO_VALID_WINDOW,
    private val validCount: Int = BodyMetricsConfig.TORSO_VALID_COUNT,
    private val invalidWindow: Int = BodyMetricsConfig.TORSO_INVALID_WINDOW,
    private val invalidCount: Int = BodyMetricsConfig.TORSO_INVALID_COUNT,
) {
    private val recent = ArrayDeque<Boolean>()
    private var latched = false

    fun current(): Boolean = latched

    fun update(rawValid: Boolean): Boolean {
        recent.addLast(rawValid)
        val maxWindow = maxOf(validWindow, invalidWindow)
        while (recent.size > maxWindow) {
            recent.removeFirst()
        }

        if (!latched) {
            val window = recent.takeLast(validWindow)
            if (window.count { it } >= validCount) {
                latched = true
            }
        } else {
            val window = recent.takeLast(invalidWindow)
            if (window.count { !it } >= invalidCount) {
                latched = false
            }
        }
        return latched
    }

    fun reset() {
        recent.clear()
        latched = false
    }
}

/**
 * Consecutive-result gate for hand visibility.
 *
 * - Turns ON after [streakOn] consecutive valid results (default 4).
 * - Turns OFF after [streakOff] consecutive invalid results (default 1 =
 *   immediately false on occlusion / missing fingers / weak visibility).
 */
class ConsecutiveVisibilityGate(
    private val streakOn: Int = BodyMetricsConfig.HAND_VISIBLE_STREAK_ON,
    private val streakOff: Int = BodyMetricsConfig.HAND_VISIBLE_STREAK_OFF,
) {
    private var consecutiveValid = 0
    private var consecutiveInvalid = 0
    private var gated = false

    fun current(): Boolean = gated

    fun update(rawValid: Boolean): Boolean {
        if (rawValid) {
            consecutiveValid++
            consecutiveInvalid = 0
            if (consecutiveValid >= streakOn) {
                gated = true
            }
        } else {
            consecutiveInvalid++
            consecutiveValid = 0
            if (consecutiveInvalid >= streakOff) {
                gated = false
            }
        }
        return gated
    }

    fun reset() {
        consecutiveValid = 0
        consecutiveInvalid = 0
        gated = false
    }
}
