package com.orato.app.metrics

import com.orato.app.pose.NormalizedLandmarkPoint
import com.orato.app.pose.PoseLandmarkId
import com.orato.app.pose.UpperBodyPoseFrame

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
     * Clears all accumulators and smoothers. Enables accumulation until
     * [stopAccumulation] or [release].
     */
    fun reset() {
        synchronized(lock) {
            released = false
            accumulating = true
            clearAccumulatorsLocked()
            resetSmoothersLocked()
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
            latestLive = LiveBodyMetrics()
        }
    }

    /**
     * Processes one pose frame. Invalid / low-visibility landmarks are skipped
     * (never treated as zeros). Session samples are recorded only while
     * [accumulating] is true.
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

            val leftShoulder = visiblePoint(frame, PoseLandmarkId.LEFT_SHOULDER)
            val rightShoulder = visiblePoint(frame, PoseLandmarkId.RIGHT_SHOULDER)
            val leftHip = visiblePoint(frame, PoseLandmarkId.LEFT_HIP)
            val rightHip = visiblePoint(frame, PoseLandmarkId.RIGHT_HIP)
            val leftWrist = visiblePoint(frame, PoseLandmarkId.LEFT_WRIST)
            val rightWrist = visiblePoint(frame, PoseLandmarkId.RIGHT_WRIST)
            // Elbows are part of the tracked upper-body set; smooth when present
            // so future metrics can reuse them without cold-start jumps.
            visiblePoint(frame, PoseLandmarkId.LEFT_ELBOW)?.let {
                elbowLeftSmoother.update(it.x, it.y)
            } ?: elbowLeftSmoother.reset()
            visiblePoint(frame, PoseLandmarkId.RIGHT_ELBOW)?.let {
                elbowRightSmoother.update(it.x, it.y)
            } ?: elbowRightSmoother.reset()

            val torsoValid =
                leftShoulder != null && rightShoulder != null &&
                    leftHip != null && rightHip != null

            if (!torsoValid) {
                // Do not invent zeros — reset smoothers for missing landmarks.
                if (leftShoulder == null) shoulderLeftSmoother.reset() else {
                    shoulderLeftSmoother.update(leftShoulder.x, leftShoulder.y)
                }
                if (rightShoulder == null) shoulderRightSmoother.reset() else {
                    shoulderRightSmoother.update(rightShoulder.x, rightShoulder.y)
                }
                if (leftHip == null) hipLeftSmoother.reset() else {
                    hipLeftSmoother.update(leftHip.x, leftHip.y)
                }
                if (rightHip == null) hipRightSmoother.reset() else {
                    hipRightSmoother.update(rightHip.x, rightHip.y)
                }
                if (leftWrist == null) wristLeftSmoother.reset() else {
                    wristLeftSmoother.update(leftWrist.x, leftWrist.y)
                }
                if (rightWrist == null) wristRightSmoother.reset() else {
                    wristRightSmoother.update(rightWrist.x, rightWrist.y)
                }
                clearMotionHistoryLocked()
                val live = LiveBodyMetrics(
                    validDetection = false,
                    shoulderTilt = null,
                    trunkAngleDegrees = null,
                    oneHandVisible = leftWrist != null || rightWrist != null,
                    twoHandsVisible = leftWrist != null && rightWrist != null,
                )
                latestLive = live
                return live
            }

            val smoothLs = shoulderLeftSmoother.update(leftShoulder!!.x, leftShoulder.y)
            val smoothRs = shoulderRightSmoother.update(rightShoulder!!.x, rightShoulder.y)
            val smoothLh = hipLeftSmoother.update(leftHip!!.x, leftHip.y)
            val smoothRh = hipRightSmoother.update(rightHip!!.x, rightHip.y)

            val width = LandmarkGeometry.shoulderWidth(smoothLs, smoothRs)
            val tilt = width?.let {
                LandmarkGeometry.shoulderTilt(smoothLs, smoothRs, it)
            }
            val smoothedTilt = tilt?.let { tiltSmoother.update(it) }

            val shoulderMid = smoothLs.midpointWith(smoothRs)
            val hipMid = smoothLh.midpointWith(smoothRh)
            val trunkAngle = LandmarkGeometry.trunkInclinationDegrees(shoulderMid, hipMid)

            val smoothLw = leftWrist?.let { wristLeftSmoother.update(it.x, it.y) }
                ?: run {
                    wristLeftSmoother.reset()
                    null
                }
            val smoothRw = rightWrist?.let { wristRightSmoother.update(it.x, it.y) }
                ?: run {
                    wristRightSmoother.reset()
                    null
                }

            val oneHand = smoothLw != null || smoothRw != null
            val twoHands = smoothLw != null && smoothRw != null

            if (accumulating && width != null) {
                validTorsoFrames++
                if (oneHand) oneHandVisibleFrames++
                if (twoHands) twoHandsVisibleFrames++
                if (smoothedTilt != null) shoulderTiltSamples += smoothedTilt
                if (trunkAngle != null) trunkAngleSamples += trunkAngle

                accumulateStabilityLocked(shoulderMid, hipMid, width)
                accumulateGestureLocked(smoothLw, smoothRw, width)
            } else if (width == null) {
                clearMotionHistoryLocked()
            } else {
                // Not accumulating: still keep motion history fresh for live feel,
                // but do not append samples.
                prevShoulderMid = shoulderMid
                prevHipMid = hipMid
                prevLeftWrist = smoothLw
                prevRightWrist = smoothRw
                prevShoulderWidth = width
            }

            val live = LiveBodyMetrics(
                validDetection = true,
                shoulderTilt = smoothedTilt,
                trunkAngleDegrees = trunkAngle,
                oneHandVisible = oneHand,
                twoHandsVisible = twoHands,
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

            val gesture = buildGestureMetricLocked()

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
                gestureActivity = gesture,
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
            // Detection jump — exclude from stability, keep new position as baseline.
            return
        }

        // Lateral sway emphasis: |Δx| of both midpoints, normalized.
        val shoulderLateral = LandmarkGeometry.normalizeByShoulderWidth(
            kotlin.math.abs(shoulderMid.x - prevS.x),
            shoulderWidth,
        )
        val hipLateral = LandmarkGeometry.normalizeByShoulderWidth(
            kotlin.math.abs(hipMid.x - prevH.x),
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

    private fun visiblePoint(
        frame: UpperBodyPoseFrame,
        id: PoseLandmarkId,
    ): NormalizedLandmarkPoint? {
        val point = frame.landmarks[id] ?: return null
        if (point.visibility < config.MIN_LANDMARK_VISIBILITY) return null
        return point
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

    /** Package-visible counters for unit tests. */
    internal fun debugCounters(): DebugCounters = synchronized(lock) {
        DebugCounters(
            totalAnalyzedFrames = totalAnalyzedFrames,
            validTorsoFrames = validTorsoFrames,
            shoulderTiltSampleCount = shoulderTiltSamples.size,
            swaySampleCount = swaySamples.size,
            gestureSampleCount = gestureActivitySamples.size,
            isAccumulating = accumulating,
            isReleased = released,
        )
    }

    internal data class DebugCounters(
        val totalAnalyzedFrames: Int,
        val validTorsoFrames: Int,
        val shoulderTiltSampleCount: Int,
        val swaySampleCount: Int,
        val gestureSampleCount: Int,
        val isAccumulating: Boolean,
        val isReleased: Boolean,
    )
}
