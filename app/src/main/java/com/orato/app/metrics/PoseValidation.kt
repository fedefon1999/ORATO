package com.orato.app.metrics

import com.orato.app.pose.NormalizedLandmarkPoint
import com.orato.app.pose.PoseLandmarkId
import com.orato.app.pose.UpperBodyPoseFrame
import kotlin.math.abs

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
)

/**
 * Raw hand visibility for one side (pre consecutive-gate).
 */
data class HandValidation(
    val rawVisible: Boolean,
    val wrist: LandmarkUsability,
    val elbow: LandmarkUsability,
    val validFingerCount: Int,
    val wristPoint: Point2D?,
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

    fun evaluateLeftHand(frame: UpperBodyPoseFrame): HandValidation =
        evaluateHand(
            frame = frame,
            wristId = PoseLandmarkId.LEFT_WRIST,
            elbowId = PoseLandmarkId.LEFT_ELBOW,
            thumbId = PoseLandmarkId.LEFT_THUMB,
            indexId = PoseLandmarkId.LEFT_INDEX,
            pinkyId = PoseLandmarkId.LEFT_PINKY,
        )

    fun evaluateRightHand(frame: UpperBodyPoseFrame): HandValidation =
        evaluateHand(
            frame = frame,
            wristId = PoseLandmarkId.RIGHT_WRIST,
            elbowId = PoseLandmarkId.RIGHT_ELBOW,
            thumbId = PoseLandmarkId.RIGHT_THUMB,
            indexId = PoseLandmarkId.RIGHT_INDEX,
            pinkyId = PoseLandmarkId.RIGHT_PINKY,
        )

    /**
     * A hand is visually observable only when:
     * - wrist is usable, in-frame, visibility ≥ [BodyMetricsConfig.WRIST_MIN_VISIBILITY];
     * - elbow is usable (supporting evidence; in-frame not required);
     * - ≥ [BodyMetricsConfig.HAND_MIN_FINGER_LANDMARKS] among thumb/index/pinky
     *   are usable and in-frame at hand landmark visibility.
     *
     * Occluded / inferred wrists without reliable fingers do **not** count.
     */
    private fun evaluateHand(
        frame: UpperBodyPoseFrame,
        wristId: PoseLandmarkId,
        elbowId: PoseLandmarkId,
        thumbId: PoseLandmarkId,
        indexId: PoseLandmarkId,
        pinkyId: PoseLandmarkId,
    ): HandValidation {
        val wrist = LandmarkUsabilityEvaluator.fromFrame(
            frame = frame,
            id = wristId,
            minVisibility = BodyMetricsConfig.WRIST_MIN_VISIBILITY,
            requireInFrame = true,
        )
        val elbow = LandmarkUsabilityEvaluator.fromFrame(
            frame = frame,
            id = elbowId,
            minVisibility = BodyMetricsConfig.HAND_LANDMARK_MIN_VISIBILITY,
            requireInFrame = false,
        )
        val fingers = listOf(thumbId, indexId, pinkyId).map { id ->
            LandmarkUsabilityEvaluator.fromFrame(
                frame = frame,
                id = id,
                minVisibility = BodyMetricsConfig.HAND_LANDMARK_MIN_VISIBILITY,
                requireInFrame = true,
            )
        }
        val validFingerCount = fingers.count { it.usable }
        val rawVisible =
            wrist.usable &&
                elbow.usable &&
                validFingerCount >= BodyMetricsConfig.HAND_MIN_FINGER_LANDMARKS

        return HandValidation(
            rawVisible = rawVisible,
            wrist = wrist,
            elbow = elbow,
            validFingerCount = validFingerCount,
            wristPoint = wrist.point,
        )
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
 * Consecutive-result gate for hand visibility accumulation.
 *
 * - Turns ON after [streakOn] consecutive valid results.
 * - Turns OFF after [streakOff] consecutive invalid results.
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
