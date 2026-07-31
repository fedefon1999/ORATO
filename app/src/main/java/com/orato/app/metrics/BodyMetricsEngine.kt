package com.orato.app.metrics

import com.orato.app.pose.PoseLandmarkId
import com.orato.app.pose.UpperBodyPoseFrame
import kotlin.math.abs

/**
 * Session-scoped body metrics engine (outside the UI layer).
 *
 * Thread-safe: pose callbacks may arrive off the main thread.
 * Call [reset] when a practice session starts and [release] when leaving
 * the practice screen so accumulators never leak across sessions.
 */
class BodyMetricsEngine {
    private val config = BodyMetricsConfig
    private val lock = Any()

    private val shoulderLeftSmoother = LandmarkPointSmoother()
    private val shoulderRightSmoother = LandmarkPointSmoother()
    private val elbowLeftSmoother = LandmarkPointSmoother()
    private val elbowRightSmoother = LandmarkPointSmoother()
    private val wristLeftSmoother = LandmarkPointSmoother()
    private val wristRightSmoother = LandmarkPointSmoother()
    private val hipLeftSmoother = LandmarkPointSmoother()
    private val hipRightSmoother = LandmarkPointSmoother()
    private val tiltSmoother = ExponentialMovingAverage(config.LANDMARK_EMA_ALPHA)

    private val torsoHysteresis = TorsoHysteresis()
    private val leftHandGate = ConsecutiveVisibilityGate()
    private val rightHandGate = ConsecutiveVisibilityGate()

    private var accumulating = false
    private var released = false

    private var totalAnalyzedFrames = 0
    private var validTorsoFrames = 0
    private var oneHandVisibleFrames = 0
    private var twoHandsVisibleFrames = 0

    private val shoulderTiltSamples = mutableListOf<Float>()
    private val trunkAngleSamples = mutableListOf<Float>()
    private val swaySamples = mutableListOf<Float>()
    private val gestureActivitySamples = mutableListOf<Float>()

    private var prevShoulderMid: Point2D? = null
    private var prevHipMid: Point2D? = null
    private var prevLeftWrist: Point2D? = null
    private var prevRightWrist: Point2D? = null
    private var prevShoulderWidth: Float? = null

    @Volatile
    private var latestLive: LiveBodyMetrics = LiveBodyMetrics()

    fun liveMetrics(): LiveBodyMetrics = latestLive

    /**
     * Clears all accumulators, smoothers, and temporal validation state.
     * Enables accumulation until [stopAccumulation] or [release].
     */
    fun reset() {
        synchronized(lock) {
            released = false
            accumulating = true
            clearAccumulatorsLocked()
            resetSmoothersLocked()
            resetTemporalValidationLocked()
            latestLive = LiveBodyMetrics()
        }
    }

    /** Stops session aggregation (e.g. timer finished) but keeps last live snapshot. */
    fun stopAccumulation() {
        synchronized(lock) {
            accumulating = false
        }
    }

    /** Stops processing and drops session state (leaving practice screen). */
    fun release() {
        synchronized(lock) {
            released = true
            accumulating = false
            clearAccumulatorsLocked()
            resetSmoothersLocked()
            resetTemporalValidationLocked()
            latestLive = LiveBodyMetrics()
        }
    }

    /**
     * Processes one pose frame. Invalid / low-visibility / out-of-frame landmarks
     * are skipped (never treated as zeros). Session samples are recorded only
     * while [accumulating] is true, and only on genuinely valid torso frames.
     */
    fun processFrame(frame: UpperBodyPoseFrame): LiveBodyMetrics {
        synchronized(lock) {
            if (released) {
                latestLive = LiveBodyMetrics()
                return latestLive
            }

            if (accumulating) {
                totalAnalyzedFrames++
            }

            val torso = PoseValidation.evaluateTorso(frame)
            val leftHand = PoseValidation.evaluateLeftHand(frame)
            val rightHand = PoseValidation.evaluateRightHand(frame)

            val latchedTorso = torsoHysteresis.update(torso.rawValid)
            val leftHandGated = leftHandGate.update(leftHand.rawVisible)
            val rightHandGated = rightHandGate.update(rightHand.rawVisible)

            updateSmoothersLocked(torso, leftHand, rightHand, frame)

            val liveBase = LiveBodyMetrics(
                validDetection = latchedTorso,
                shoulderTilt = null,
                trunkAngleDegrees = null,
                oneHandVisible = leftHandGated || rightHandGated,
                twoHandsVisible = leftHandGated && rightHandGated,
                leftShoulder = torso.leftShoulder.toDebug(),
                rightShoulder = torso.rightShoulder.toDebug(),
                leftHip = torso.leftHip.toDebug(),
                rightHip = torso.rightHip.toDebug(),
                leftWristVisibility = leftHand.wrist.visibility,
                rightWristVisibility = rightHand.wrist.visibility,
                leftValidFingerCount = leftHand.validFingerCount,
                rightValidFingerCount = rightHand.validFingerCount,
                torsoValid = latchedTorso,
                leftHandVisible = leftHandGated,
                rightHandVisible = rightHandGated,
            )

            if (!torso.rawValid) {
                clearMotionHistoryLocked()
                latestLive = liveBase
                return liveBase
            }

            val ls = torso.leftShoulder.point!!
            val rs = torso.rightShoulder.point!!
            val lh = torso.leftHip.point!!
            val rh = torso.rightHip.point!!

            val smoothLs = shoulderLeftSmoother.update(ls.x, ls.y)
            val smoothRs = shoulderRightSmoother.update(rs.x, rs.y)
            val smoothLh = hipLeftSmoother.update(lh.x, lh.y)
            val smoothRh = hipRightSmoother.update(rh.x, rh.y)

            val width = LandmarkGeometry.shoulderWidth(smoothLs, smoothRs)
                ?: torso.shoulderWidth
            if (width == null) {
                clearMotionHistoryLocked()
                latestLive = liveBase
                return liveBase
            }

            val tilt = LandmarkGeometry.shoulderTilt(smoothLs, smoothRs, width)
            val smoothedTilt = tilt?.let { tiltSmoother.update(it) }
            val shoulderMid = smoothLs.midpointWith(smoothRs)
            val hipMid = smoothLh.midpointWith(smoothRh)
            val trunkAngle = LandmarkGeometry.trunkInclinationDegrees(shoulderMid, hipMid)

            val smoothLw = leftHand.wristPoint?.let { wristLeftSmoother.update(it.x, it.y) }
            val smoothRw = rightHand.wristPoint?.let { wristRightSmoother.update(it.x, it.y) }

            if (accumulating) {
                // Camera presence & torso metrics: genuinely valid raw torso only.
                validTorsoFrames++
                if (leftHandGated || rightHandGated) oneHandVisibleFrames++
                if (leftHandGated && rightHandGated) twoHandsVisibleFrames++
                if (smoothedTilt != null) shoulderTiltSamples += smoothedTilt
                if (trunkAngle != null) trunkAngleSamples += trunkAngle

                accumulateStabilityLocked(shoulderMid, hipMid, width)
                // Gesture uses gated-visible wrists only (not inferred occluded ones).
                accumulateGestureLocked(
                    leftWrist = if (leftHandGated) smoothLw else null,
                    rightWrist = if (rightHandGated) smoothRw else null,
                    shoulderWidth = width,
                )
            } else {
                prevShoulderMid = shoulderMid
                prevHipMid = hipMid
                prevLeftWrist = if (leftHandGated) smoothLw else null
                prevRightWrist = if (rightHandGated) smoothRw else null
                prevShoulderWidth = width
            }

            val live = liveBase.copy(
                shoulderTilt = smoothedTilt,
                trunkAngleDegrees = trunkAngle,
            )
            latestLive = live
            return live
        }
    }

    fun buildReport(): SessionBodyReport {
        synchronized(lock) {
            if (validTorsoFrames < config.MIN_SAMPLES_FOR_SCORE ||
                totalAnalyzedFrames < config.MIN_SAMPLES_FOR_SCORE
            ) {
                return SessionBodyReport.emptyInsufficient()
            }

            val presencePercent =
                100f * validTorsoFrames.toFloat() / totalAnalyzedFrames.toFloat()

            val medianTilt = LandmarkGeometry.median(shoulderTiltSamples)
            val shoulderBalance = if (medianTilt == null ||
                shoulderTiltSamples.size < config.MIN_SAMPLES_FOR_SCORE
            ) {
                ScoredMetric.insufficient()
            } else {
                ScoredMetric(
                    rawValue = medianTilt,
                    score = LandmarkGeometry.scoreLowerIsBetter(
                        value = medianTilt,
                        goodMax = config.SHOULDER_TILT_GOOD_MAX,
                        badMin = config.SHOULDER_TILT_BAD_MIN,
                    ),
                    insufficientData = false,
                )
            }

            val medianTrunk = LandmarkGeometry.median(trunkAngleSamples)
            val trunkInclination = if (medianTrunk == null ||
                trunkAngleSamples.size < config.MIN_SAMPLES_FOR_SCORE
            ) {
                ScoredMetric.insufficient()
            } else {
                ScoredMetric(
                    rawValue = medianTrunk,
                    score = LandmarkGeometry.scoreLowerIsBetter(
                        value = medianTrunk,
                        goodMax = config.TRUNK_ANGLE_GOOD_MAX_DEG,
                        badMin = config.TRUNK_ANGLE_BAD_MIN_DEG,
                    ),
                    insufficientData = false,
                )
            }

            val meanSway = if (swaySamples.isEmpty()) {
                null
            } else {
                swaySamples.average().toFloat()
            }
            val trunkStability = if (meanSway == null ||
                swaySamples.size < config.MIN_SAMPLES_FOR_SCORE
            ) {
                ScoredMetric.insufficient()
            } else {
                ScoredMetric(
                    rawValue = meanSway,
                    score = LandmarkGeometry.scoreLowerIsBetter(
                        value = meanSway,
                        goodMax = config.STABILITY_SWAY_GOOD_MAX,
                        badMin = config.STABILITY_SWAY_BAD_MIN,
                    ),
                    insufficientData = false,
                )
            }

            val oneHandPct = 100f * oneHandVisibleFrames.toFloat() / validTorsoFrames.toFloat()
            val twoHandPct = 100f * twoHandsVisibleFrames.toFloat() / validTorsoFrames.toFloat()

            return SessionBodyReport(
                cameraPresence = PercentMetric(
                    percent = presencePercent,
                    insufficientData = false,
                ),
                shoulderBalance = shoulderBalance,
                trunkInclination = trunkInclination,
                trunkStability = trunkStability,
                oneHandVisibility = PercentMetric(
                    percent = oneHandPct,
                    insufficientData = false,
                ),
                twoHandVisibility = PercentMetric(
                    percent = twoHandPct,
                    insufficientData = false,
                ),
                gestureActivity = buildGestureMetricLocked(),
            )
        }
    }

    private fun buildGestureMetricLocked(): GestureActivityMetric {
        if (gestureActivitySamples.size < config.MIN_SAMPLES_FOR_SCORE) {
            return GestureActivityMetric.insufficient()
        }
        val average = gestureActivitySamples.average().toFloat()
        val activeCount = gestureActivitySamples.count {
            it >= config.GESTURE_MOVEMENT_THRESHOLD
        }
        val activeRatio = activeCount.toFloat() / gestureActivitySamples.size.toFloat()
        val activePercent = activeRatio * 100f

        val classification = when {
            average <= config.GESTURE_LOW_MAX_AVG &&
                activeRatio <= config.GESTURE_LOW_MAX_ACTIVE_RATIO ->
                GestureActivityClass.LOW
            average >= config.GESTURE_HIGH_MIN_AVG ||
                activeRatio >= config.GESTURE_HIGH_MIN_ACTIVE_RATIO ->
                GestureActivityClass.HIGH
            else -> GestureActivityClass.BALANCED
        }

        return GestureActivityMetric(
            averageActivity = average,
            activeTimePercent = activePercent,
            classification = classification,
            insufficientData = false,
        )
    }

    private fun updateSmoothersLocked(
        torso: TorsoValidation,
        leftHand: HandValidation,
        rightHand: HandValidation,
        frame: UpperBodyPoseFrame,
    ) {
        fun smoothOrReset(
            usability: LandmarkUsability,
            smoother: LandmarkPointSmoother,
        ) {
            val p = usability.point
            if (p != null) smoother.update(p.x, p.y) else smoother.reset()
        }

        // Shoulders/hips only smoothed when usable; never invent zeros.
        if (!torso.rawValid) {
            smoothOrReset(torso.leftShoulder, shoulderLeftSmoother)
            smoothOrReset(torso.rightShoulder, shoulderRightSmoother)
            smoothOrReset(torso.leftHip, hipLeftSmoother)
            smoothOrReset(torso.rightHip, hipRightSmoother)
        }

        val leftElbow = LandmarkUsabilityEvaluator.fromFrame(
            frame,
            PoseLandmarkId.LEFT_ELBOW,
            config.HAND_LANDMARK_MIN_VISIBILITY,
            requireInFrame = false,
        )
        val rightElbow = LandmarkUsabilityEvaluator.fromFrame(
            frame,
            PoseLandmarkId.RIGHT_ELBOW,
            config.HAND_LANDMARK_MIN_VISIBILITY,
            requireInFrame = false,
        )
        smoothOrReset(leftElbow, elbowLeftSmoother)
        smoothOrReset(rightElbow, elbowRightSmoother)

        if (leftHand.wristPoint == null) wristLeftSmoother.reset()
        if (rightHand.wristPoint == null) wristRightSmoother.reset()
    }

    private fun accumulateStabilityLocked(
        shoulderMid: Point2D,
        hipMid: Point2D,
        shoulderWidth: Float,
    ) {
        val prevS = prevShoulderMid
        val prevH = prevHipMid
        prevShoulderMid = shoulderMid
        prevHipMid = hipMid
        prevShoulderWidth = shoulderWidth

        if (prevS == null || prevH == null) return

        val shoulderJump = LandmarkGeometry.normalizeByShoulderWidth(
            prevS.distanceTo(shoulderMid),
            shoulderWidth,
        )
        val hipJump = LandmarkGeometry.normalizeByShoulderWidth(
            prevH.distanceTo(hipMid),
            shoulderWidth,
        )
        if (shoulderJump > config.MAX_NORMALIZED_JUMP ||
            hipJump > config.MAX_NORMALIZED_JUMP
        ) {
            return
        }

        val shoulderLateral = LandmarkGeometry.normalizeByShoulderWidth(
            abs(shoulderMid.x - prevS.x),
            shoulderWidth,
        )
        val hipLateral = LandmarkGeometry.normalizeByShoulderWidth(
            abs(hipMid.x - prevH.x),
            shoulderWidth,
        )
        swaySamples += (shoulderLateral + hipLateral) / 2f
    }

    private fun accumulateGestureLocked(
        leftWrist: Point2D?,
        rightWrist: Point2D?,
        shoulderWidth: Float,
    ) {
        val movements = mutableListOf<Float>()

        fun consider(current: Point2D?, previous: Point2D?): Point2D? {
            if (current == null) return null
            if (previous != null) {
                val jump = LandmarkGeometry.normalizeByShoulderWidth(
                    previous.distanceTo(current),
                    shoulderWidth,
                )
                if (jump <= config.MAX_NORMALIZED_JUMP) {
                    movements += jump
                }
            }
            return current
        }

        prevLeftWrist = consider(leftWrist, prevLeftWrist)
        prevRightWrist = consider(rightWrist, prevRightWrist)

        if (leftWrist == null) prevLeftWrist = null
        if (rightWrist == null) prevRightWrist = null

        if (movements.isNotEmpty()) {
            gestureActivitySamples += movements.average().toFloat()
        }
    }

    private fun clearMotionHistoryLocked() {
        prevShoulderMid = null
        prevHipMid = null
        prevLeftWrist = null
        prevRightWrist = null
        prevShoulderWidth = null
        tiltSmoother.reset()
    }

    private fun clearAccumulatorsLocked() {
        totalAnalyzedFrames = 0
        validTorsoFrames = 0
        oneHandVisibleFrames = 0
        twoHandsVisibleFrames = 0
        shoulderTiltSamples.clear()
        trunkAngleSamples.clear()
        swaySamples.clear()
        gestureActivitySamples.clear()
        clearMotionHistoryLocked()
    }

    private fun resetSmoothersLocked() {
        shoulderLeftSmoother.reset()
        shoulderRightSmoother.reset()
        elbowLeftSmoother.reset()
        elbowRightSmoother.reset()
        wristLeftSmoother.reset()
        wristRightSmoother.reset()
        hipLeftSmoother.reset()
        hipRightSmoother.reset()
        tiltSmoother.reset()
    }

    private fun resetTemporalValidationLocked() {
        torsoHysteresis.reset()
        leftHandGate.reset()
        rightHandGate.reset()
    }

    private fun LandmarkUsability.toDebug(): LandmarkDebugInfo =
        LandmarkDebugInfo(visibility = visibility, inFrame = inFrame)

    /** Package-visible counters for unit tests. */
    internal fun debugCounters(): DebugCounters = synchronized(lock) {
        DebugCounters(
            totalAnalyzedFrames = totalAnalyzedFrames,
            validTorsoFrames = validTorsoFrames,
            oneHandVisibleFrames = oneHandVisibleFrames,
            twoHandsVisibleFrames = twoHandsVisibleFrames,
            shoulderTiltSampleCount = shoulderTiltSamples.size,
            swaySampleCount = swaySamples.size,
            gestureSampleCount = gestureActivitySamples.size,
            isAccumulating = accumulating,
            isReleased = released,
            torsoLatched = torsoHysteresis.current(),
            leftHandGated = leftHandGate.current(),
            rightHandGated = rightHandGate.current(),
        )
    }

    internal data class DebugCounters(
        val totalAnalyzedFrames: Int,
        val validTorsoFrames: Int,
        val oneHandVisibleFrames: Int,
        val twoHandsVisibleFrames: Int,
        val shoulderTiltSampleCount: Int,
        val swaySampleCount: Int,
        val gestureSampleCount: Int,
        val isAccumulating: Boolean,
        val isReleased: Boolean,
        val torsoLatched: Boolean,
        val leftHandGated: Boolean,
        val rightHandGated: Boolean,
    )
}
