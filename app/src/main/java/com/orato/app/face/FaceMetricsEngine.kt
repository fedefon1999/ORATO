package com.orato.app.face

import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/**
 * Duration-based face session metrics.
 *
 * ## Denominators (documented)
 *
 * | Metric | Numerator | Denominator |
 * |--------|-----------|-------------|
 * | Face detected % | facePresentDurationMs | visualSessionDurationMs |
 * | Valid tracking % | validTrackingDurationMs | visualSessionDurationMs |
 * | Centered face % | centeredFaceDurationMs | validFramingObservationDurationMs |
 * | Toward-camera % | towardCameraDurationMs | validGazeTrackingDurationMs |
 * | Head centered % | centeredHeadDurationMs | validTrackingDurationMs |
 *
 * All durations use **capture timestamps**. Frame counts never drive user-facing %.
 *
 * Stale gaps (>[FaceMetricsConfig.STALE_RESULT_MS]): attribute at most STALE_RESULT_MS
 * to the previous state; remainder → unavailableVisualTrackingDurationMs.
 */
class FaceMetricsEngine {
    private val config = FaceMetricsConfig
    private val lock = Any()
    private val gazeClassifier = CameraGazeClassifier()

    private var accumulating = false
    private var released = false
    private var calibration: FaceCalibrationProfile? = null
    private var sessionId: Long = 0L

    private var sessionStartMs: Long = 0L
    private var lastAcceptedCaptureMs: Long = 0L
    private var sessionEndMs: Long = 0L

    // Duration accumulators (ms)
    private var visualSessionDurationMs = 0L
    private var facePresentDurationMs = 0L
    private var validTrackingDurationMs = 0L
    private var validFramingObservationDurationMs = 0L
    private var centeredFaceDurationMs = 0L
    private var tooCloseDurationMs = 0L
    private var tooFarDurationMs = 0L
    private var unavailableVisualTrackingDurationMs = 0L

    private var validGazeTrackingDurationMs = 0L
    private var unavailableGazeTrackingDurationMs = 0L
    private var towardCameraDurationMs = 0L
    private var currentTowardMs = 0L
    private var longestTowardMs = 0L
    private var currentAwayMs = 0L
    private var significantAwayCount = 0
    private var significantAwayTotalMs = 0L
    private var longestAwayMs = 0L
    private var awayCounted = false

    private var centeredHeadDurationMs = 0L
    private var largeYawCount = 0
    private var largePitchCount = 0
    private var largeRollCount = 0
    private var directionChanges = 0
    private var angularSpeedSum = 0.0
    private var angularSpeedSamples = 0
    private var peakAngularSpeed = 0f
    private var currentStableMs = 0L
    private var longestStableMs = 0L
    private var yawInLarge = false
    private var pitchInLarge = false
    private var rollInLarge = false
    private var lastYawEventMs = 0L
    private var lastPitchEventMs = 0L
    private var lastRollEventMs = 0L
    private var prevYawSign = 0
    private var prevPitchSign = 0

    private var smoothedYaw = 0f
    private var smoothedPitch = 0f
    private var smoothedRoll = 0f
    private var hasSmoothed = false
    private var prevSmoothedYaw = 0f
    private var prevSmoothedPitch = 0f
    private var prevSmoothedRoll = 0f
    private var prevSmoothTs = 0L

    private var bothEyesClosedMs = 0L
    private var prolongedClosureCount = 0
    private var longestClosureMs = 0L
    private var closureCounted = false

    private var longestValidTrackingMs = 0L
    private var currentValidTrackingMs = 0L
    private var outOfFrameEvents = 0
    private var wasOutOfFrame = true

    private var lastLive: LiveFaceMetrics = LiveFaceMetrics()
    private var lastStateForDuration: DurationState = DurationState.UNAVAILABLE

    @Volatile
    private var latestLive: LiveFaceMetrics = LiveFaceMetrics()

    @Volatile
    var duplicateTimestampRejections: Long = 0L
        private set

    @Volatile
    var outOfOrderTimestampRejections: Long = 0L
        private set

    @Volatile
    var staleSessionRejections: Long = 0L
        private set

    private enum class DurationState {
        FACE_ABSENT,
        INVALID_TRACKING,
        VALID_TRACKING,
        UNAVAILABLE,
    }

    fun liveMetrics(): LiveFaceMetrics = latestLive

    fun setCalibration(profile: FaceCalibrationProfile?) {
        synchronized(lock) { calibration = profile }
    }

    fun beginSession(id: Long) {
        synchronized(lock) {
            sessionId = id
        }
    }

    fun reset() {
        synchronized(lock) {
            released = false
            accumulating = true
            gazeClassifier.reset()
            clearLocked()
            latestLive = LiveFaceMetrics()
        }
    }

    fun stopAccumulation() {
        synchronized(lock) { accumulating = false }
    }

    fun finalizeAt(sessionEndCaptureMs: Long) {
        synchronized(lock) {
            if (lastAcceptedCaptureMs > 0L && sessionEndCaptureMs > lastAcceptedCaptureMs) {
                applyInterval(lastAcceptedCaptureMs, sessionEndCaptureMs, lastLive, lastStateForDuration)
                lastAcceptedCaptureMs = sessionEndCaptureMs
            }
            sessionEndMs = sessionEndCaptureMs
            accumulating = false
        }
    }

    fun release() {
        synchronized(lock) {
            accumulating = false
            released = true
            calibration = null
            clearLocked()
            latestLive = LiveFaceMetrics()
        }
    }

    fun processFrame(frame: FaceFrame, frameSessionId: Long = sessionId): LiveFaceMetrics {
        synchronized(lock) {
            if (released) return latestLive
            if (frameSessionId != sessionId && sessionId != 0L) {
                staleSessionRejections++
                return latestLive
            }

            val captureMs = frame.timestampMs
            if (lastAcceptedCaptureMs > 0L) {
                when {
                    captureMs == lastAcceptedCaptureMs -> {
                        duplicateTimestampRejections++
                        return latestLive
                    }
                    captureMs < lastAcceptedCaptureMs -> {
                        outOfOrderTimestampRejections++
                        return latestLive
                    }
                }
            }

            val profile = calibration
            val live = evaluateLive(frame, profile)
            latestLive = live

            if (!accumulating) {
                lastLive = live
                return live
            }

            if (sessionStartMs == 0L) {
                sessionStartMs = captureMs
                lastAcceptedCaptureMs = captureMs
                lastLive = live
                lastStateForDuration = stateOf(live, frame)
                return live
            }

            val prevTs = lastAcceptedCaptureMs
            val gap = captureMs - prevTs
            if (gap > config.STALE_RESULT_MS) {
                // Attribute at most STALE_RESULT_MS to previous state; remainder unavailable.
                val reliableEnd = prevTs + config.STALE_RESULT_MS
                applyInterval(prevTs, reliableEnd, lastLive, lastStateForDuration)
                unavailableVisualTrackingDurationMs += captureMs - reliableEnd
                unavailableGazeTrackingDurationMs += captureMs - reliableEnd
                visualSessionDurationMs += gap
            } else {
                applyInterval(prevTs, captureMs, lastLive, lastStateForDuration)
            }

            lastAcceptedCaptureMs = captureMs
            lastLive = live
            lastStateForDuration = stateOf(live, frame)

            // Event counters (not percentages)
            if (!live.validTracking) {
                currentValidTrackingMs = 0L
                if (!wasOutOfFrame && (!frame.facePresent || live.validityReason == FaceValidityReason.FACE_OFF_CENTER)) {
                    outOfFrameEvents++
                }
                wasOutOfFrame = !frame.facePresent ||
                    live.validityReason == FaceValidityReason.FACE_OFF_CENTER ||
                    live.validityReason == FaceValidityReason.NO_FACE
            } else {
                wasOutOfFrame = false
            }

            accumulateGazeEvents(live.gazeState, min(gap, config.STALE_RESULT_MS).coerceAtLeast(0L))
            accumulateHead(frame, live, min(gap, config.STALE_RESULT_MS).coerceAtLeast(0L))
            accumulateEyeClosure(frame, min(gap, config.STALE_RESULT_MS).coerceAtLeast(0L))

            return live
        }
    }

    fun buildReport(): SessionFaceReport {
        synchronized(lock) {
            val sessionMs = visualSessionDurationMs.coerceAtLeast(1L)
            fun pct(num: Long, den: Long): Float? {
                if (den <= 0L) return null
                return ((100.0 * num / den).coerceIn(0.0, 100.0)).toFloat()
            }

            val framing = FaceFramingReportData(
                faceDetectedPercent = pct(facePresentDurationMs, sessionMs),
                validTrackingPercent = pct(validTrackingDurationMs, sessionMs),
                centeredFacePercent = pct(centeredFaceDurationMs, validFramingObservationDurationMs),
                tooCloseDurationMs = tooCloseDurationMs,
                tooFarDurationMs = tooFarDurationMs,
                outOfFrameEventCount = outOfFrameEvents,
                longestValidTrackingMs = longestValidTrackingMs,
                insufficientData = facePresentDurationMs == 0L,
                // Denominators for diagnostics / tests
                visualSessionDurationMs = visualSessionDurationMs,
                validFramingObservationDurationMs = validFramingObservationDurationMs,
                unavailableVisualTrackingDurationMs = unavailableVisualTrackingDurationMs,
            )
            val towardPct = pct(towardCameraDurationMs, validGazeTrackingDurationMs)
            val avgAway = if (significantAwayCount > 0) {
                significantAwayTotalMs.toDouble() / significantAwayCount
            } else {
                null
            }
            val gaze = CameraGazeReportData(
                towardCameraPercent = towardPct,
                longestTowardCameraMs = longestTowardMs,
                significantAwayCount = significantAwayCount,
                averageSignificantAwayMs = avgAway?.toLong(),
                longestAwayMs = longestAwayMs,
                validGazeTrackingMs = validGazeTrackingDurationMs,
                unavailableGazeTrackingMs = unavailableGazeTrackingDurationMs,
                insufficientData = validGazeTrackingDurationMs == 0L,
                explanation = CameraGazeReportData.DEFAULT_EXPLANATION,
            )
            val avgSpeed = if (angularSpeedSamples > 0) {
                (angularSpeedSum / angularSpeedSamples).toFloat()
            } else {
                null
            }
            val head = HeadMovementReportData(
                centeredHeadPercent = pct(centeredHeadDurationMs, validTrackingDurationMs),
                largeHorizontalTurnCount = largeYawCount,
                largeVerticalMovementCount = largePitchCount,
                lateralTiltCount = largeRollCount,
                averageAngularSpeedDegPerSec = avgSpeed,
                peakAngularSpeedDegPerSec = peakAngularSpeed.takeIf { angularSpeedSamples > 0 },
                meaningfulDirectionChanges = directionChanges,
                longestStableHeadMs = longestStableMs,
                insufficientData = validTrackingDurationMs == 0L,
            )
            val eye = if (config.EYE_CLOSURE_METRICS_ENABLED) {
                EyeClosureReportData(
                    prolongedClosureEventCount = prolongedClosureCount,
                    longestProlongedClosureMs = longestClosureMs,
                    insufficientData = false,
                    reliable = true,
                )
            } else {
                null
            }
            return SessionFaceReport(framing = framing, gaze = gaze, headMovement = head, eyeClosure = eye)
        }
    }

    private fun applyInterval(
        fromMs: Long,
        toMs: Long,
        live: LiveFaceMetrics,
        state: DurationState,
    ) {
        val dt = (toMs - fromMs).coerceAtLeast(0L)
        if (dt == 0L) return
        visualSessionDurationMs += dt
        when (state) {
            DurationState.UNAVAILABLE -> {
                unavailableVisualTrackingDurationMs += dt
                unavailableGazeTrackingDurationMs += dt
            }
            DurationState.FACE_ABSENT -> {
                // still part of session; not present
            }
            DurationState.INVALID_TRACKING -> {
                if (live.facePresent || lastLive.facePresent) {
                    facePresentDurationMs += dt
                }
                val scale = live.faceScale
                if (scale != null) {
                    if (scale > config.MAX_FACE_SCALE) tooCloseDurationMs += dt
                    if (scale < config.MIN_FACE_SCALE) tooFarDurationMs += dt
                }
                when (live.gazeState) {
                    CameraGazeState.INVALID -> unavailableGazeTrackingDurationMs += dt
                    else -> { /* invalid tracking: gaze not counted as away */ }
                }
            }
            DurationState.VALID_TRACKING -> {
                facePresentDurationMs += dt
                validTrackingDurationMs += dt
                validFramingObservationDurationMs += dt
                currentValidTrackingMs += dt
                longestValidTrackingMs = max(longestValidTrackingMs, currentValidTrackingMs)
                if (isCenteredFromLive(live)) {
                    centeredFaceDurationMs += dt
                }
                if (isHeadCentered(live)) {
                    centeredHeadDurationMs += dt
                }
                when (live.gazeState) {
                    CameraGazeState.TOWARD_CAMERA -> {
                        validGazeTrackingDurationMs += dt
                        towardCameraDurationMs += dt
                    }
                    CameraGazeState.AWAY -> {
                        validGazeTrackingDurationMs += dt
                    }
                    CameraGazeState.INVALID -> {
                        unavailableGazeTrackingDurationMs += dt
                    }
                }
            }
        }
    }

    private fun stateOf(live: LiveFaceMetrics, frame: FaceFrame): DurationState = when {
        !frame.facePresent -> DurationState.FACE_ABSENT
        live.validTracking -> DurationState.VALID_TRACKING
        else -> DurationState.INVALID_TRACKING
    }

    private fun isCenteredFromLive(live: LiveFaceMetrics): Boolean {
        val x = live.faceCenterX ?: return false
        val y = live.faceCenterY ?: return false
        return x in config.CENTER_X_MIN..config.CENTER_X_MAX &&
            y in config.CENTER_Y_MIN..config.CENTER_Y_MAX
    }

    private fun isHeadCentered(live: LiveFaceMetrics): Boolean {
        val yaw = live.relativeYawDeg ?: return false
        val pitch = live.relativePitchDeg ?: return false
        val roll = live.relativeRollDeg ?: 0f
        return abs(yaw) <= config.HEAD_CENTERED_YAW_DEG &&
            abs(pitch) <= config.HEAD_CENTERED_PITCH_DEG &&
            abs(roll) <= config.HEAD_CENTERED_ROLL_DEG
    }

    private fun evaluateLive(frame: FaceFrame, profile: FaceCalibrationProfile?): LiveFaceMetrics {
        if (!frame.facePresent) {
            return LiveFaceMetrics(validityReason = FaceValidityReason.NO_FACE)
        }
        if (frame.confidence < config.MIN_FACE_CONFIDENCE) {
            return LiveFaceMetrics(
                facePresent = true,
                confidence = frame.confidence,
                faceScale = frame.faceScale,
                validityReason = FaceValidityReason.LOW_CONFIDENCE,
            )
        }
        val scale = frame.faceScale
        if (scale == null) {
            return LiveFaceMetrics(
                facePresent = true,
                confidence = frame.confidence,
                validityReason = FaceValidityReason.IRIS_INVALID,
            )
        }
        if (scale < config.MIN_FACE_SCALE) {
            return LiveFaceMetrics(
                facePresent = true,
                confidence = frame.confidence,
                faceScale = scale,
                validityReason = FaceValidityReason.FACE_TOO_SMALL,
            )
        }
        if (scale > config.MAX_FACE_SCALE) {
            return LiveFaceMetrics(
                facePresent = true,
                confidence = frame.confidence,
                faceScale = scale,
                validityReason = FaceValidityReason.FACE_TOO_LARGE,
            )
        }
        val cx = frame.faceCenterX
        val cy = frame.faceCenterY
        if (cx == null || cy == null ||
            cx !in config.CENTER_X_MIN..config.CENTER_X_MAX ||
            cy !in config.CENTER_Y_MIN..config.CENTER_Y_MAX
        ) {
            return LiveFaceMetrics(
                facePresent = true,
                confidence = frame.confidence,
                faceScale = scale,
                validityReason = FaceValidityReason.FACE_OFF_CENTER,
            )
        }
        if (frame.leftEyeOpen == false && frame.rightEyeOpen == false) {
            return LiveFaceMetrics(
                facePresent = true,
                confidence = frame.confidence,
                faceScale = scale,
                leftEyeOpen = false,
                rightEyeOpen = false,
                validityReason = FaceValidityReason.BLINKING,
            )
        }

        val relYaw = relativeOrNull(frame.headYawDeg, profile?.baselineYawDeg)
        val relPitch = relativeOrNull(frame.headPitchDeg, profile?.baselinePitchDeg)
        val relRoll = relativeOrNull(frame.headRollDeg, profile?.baselineRollDeg)

        if (relYaw != null && abs(relYaw) > config.MAX_ABS_YAW_FOR_IRIS_DEG * 1.5f) {
            return LiveFaceMetrics(
                facePresent = true,
                confidence = frame.confidence,
                faceScale = scale,
                relativeYawDeg = relYaw,
                relativePitchDeg = relPitch,
                relativeRollDeg = relRoll,
                validityReason = FaceValidityReason.HEAD_ROTATION_EXCESSIVE,
            )
        }

        val leftH = delta(frame.leftIrisHorizontalRatio, profile?.baselineLeftIrisHorizontalRatio)
        val leftV = delta(frame.leftIrisVerticalRatio, profile?.baselineLeftIrisVerticalRatio)
        val rightH = delta(frame.rightIrisHorizontalRatio, profile?.baselineRightIrisHorizontalRatio)
        val rightV = delta(frame.rightIrisVerticalRatio, profile?.baselineRightIrisVerticalRatio)

        if (leftH == null && rightH == null) {
            return LiveFaceMetrics(
                facePresent = true,
                confidence = frame.confidence,
                faceScale = scale,
                relativeYawDeg = relYaw,
                relativePitchDeg = relPitch,
                relativeRollDeg = relRoll,
                leftEyeOpen = frame.leftEyeOpen,
                rightEyeOpen = frame.rightEyeOpen,
                validityReason = FaceValidityReason.IRIS_INVALID,
            )
        }

        val gaze = gazeClassifier.update(
            timestampMs = frame.timestampMs,
            relativeYawDeg = relYaw,
            relativePitchDeg = relPitch,
            relativeRollDeg = relRoll,
            leftIrisHDelta = leftH,
            leftIrisVDelta = leftV,
            rightIrisHDelta = rightH,
            rightIrisVDelta = rightV,
            leftEyeOpen = frame.leftEyeOpen,
            rightEyeOpen = frame.rightEyeOpen,
            faceConfidence = frame.confidence,
            framingValid = true,
        )

        return LiveFaceMetrics(
            facePresent = true,
            validTracking = true,
            validityReason = FaceValidityReason.OK,
            confidence = frame.confidence,
            faceScale = scale,
            faceCenterX = cx,
            faceCenterY = cy,
            relativeYawDeg = relYaw,
            relativePitchDeg = relPitch,
            relativeRollDeg = relRoll,
            leftIrisHorizontalRatio = frame.leftIrisHorizontalRatio,
            leftIrisVerticalRatio = frame.leftIrisVerticalRatio,
            rightIrisHorizontalRatio = frame.rightIrisHorizontalRatio,
            rightIrisVerticalRatio = frame.rightIrisVerticalRatio,
            gazeState = gaze,
            leftEyeOpen = frame.leftEyeOpen,
            rightEyeOpen = frame.rightEyeOpen,
        )
    }

    private fun accumulateGazeEvents(state: CameraGazeState, dt: Long) {
        when (state) {
            CameraGazeState.TOWARD_CAMERA -> {
                currentTowardMs += dt
                longestTowardMs = max(longestTowardMs, currentTowardMs)
                finalizeAwayIfNeeded()
                currentAwayMs = 0L
                awayCounted = false
            }
            CameraGazeState.AWAY -> {
                currentAwayMs += dt
                longestAwayMs = max(longestAwayMs, currentAwayMs)
                currentTowardMs = 0L
                if (!awayCounted && currentAwayMs >= config.SIGNIFICANT_GAZE_AWAY_MS) {
                    significantAwayCount++
                    awayCounted = true
                }
            }
            CameraGazeState.INVALID -> {
                finalizeAwayIfNeeded()
                currentTowardMs = 0L
                currentAwayMs = 0L
                awayCounted = false
            }
        }
    }

    private fun finalizeAwayIfNeeded() {
        if (awayCounted && currentAwayMs > 0L) {
            significantAwayTotalMs += currentAwayMs
        }
    }

    private fun accumulateHead(frame: FaceFrame, live: LiveFaceMetrics, dt: Long) {
        val yaw = live.relativeYawDeg ?: return
        val pitch = live.relativePitchDeg ?: return
        val roll = live.relativeRollDeg ?: 0f
        if (!live.validTracking) return

        val alpha = config.HEAD_ANGLE_EMA_ALPHA
        if (!hasSmoothed) {
            smoothedYaw = yaw
            smoothedPitch = pitch
            smoothedRoll = roll
            hasSmoothed = true
            prevSmoothedYaw = yaw
            prevSmoothedPitch = pitch
            prevSmoothedRoll = roll
            prevSmoothTs = frame.timestampMs
        } else {
            smoothedYaw = alpha * yaw + (1 - alpha) * smoothedYaw
            smoothedPitch = alpha * pitch + (1 - alpha) * smoothedPitch
            smoothedRoll = alpha * roll + (1 - alpha) * smoothedRoll
        }

        val dts = if (prevSmoothTs > 0L) {
            (frame.timestampMs - prevSmoothTs).coerceAtLeast(1L) / 1000f
        } else {
            0.033f
        }
        val speed = (
            abs(smoothedYaw - prevSmoothedYaw) +
                abs(smoothedPitch - prevSmoothedPitch) +
                abs(smoothedRoll - prevSmoothedRoll)
            ) / dts
        angularSpeedSum += speed
        angularSpeedSamples++
        peakAngularSpeed = max(peakAngularSpeed, speed)

        if (speed <= config.STABLE_HEAD_MAX_SPEED_DEG_PER_S) {
            currentStableMs += dt
            longestStableMs = max(longestStableMs, currentStableMs)
        } else {
            currentStableMs = 0L
        }

        detectLargeEvent(
            value = abs(smoothedYaw),
            threshold = config.LARGE_YAW_EVENT_DEG,
            inLarge = yawInLarge,
            setInLarge = { yawInLarge = it },
            lastEventMs = lastYawEventMs,
            setLastEventMs = { lastYawEventMs = it },
            timestampMs = frame.timestampMs,
            onEvent = { largeYawCount++ },
        )
        detectLargeEvent(
            value = abs(smoothedPitch),
            threshold = config.LARGE_PITCH_EVENT_DEG,
            inLarge = pitchInLarge,
            setInLarge = { pitchInLarge = it },
            lastEventMs = lastPitchEventMs,
            setLastEventMs = { lastPitchEventMs = it },
            timestampMs = frame.timestampMs,
            onEvent = { largePitchCount++ },
        )
        detectLargeEvent(
            value = abs(smoothedRoll),
            threshold = config.LARGE_ROLL_EVENT_DEG,
            inLarge = rollInLarge,
            setInLarge = { rollInLarge = it },
            lastEventMs = lastRollEventMs,
            setLastEventMs = { lastRollEventMs = it },
            timestampMs = frame.timestampMs,
            onEvent = { largeRollCount++ },
        )

        val yawSign = signOf(smoothedYaw)
        val pitchSign = signOf(smoothedPitch)
        if (prevYawSign != 0 && yawSign != 0 && yawSign != prevYawSign && abs(smoothedYaw) > 8f) {
            directionChanges++
        }
        if (prevPitchSign != 0 && pitchSign != 0 && pitchSign != prevPitchSign && abs(smoothedPitch) > 8f) {
            directionChanges++
        }
        if (yawSign != 0) prevYawSign = yawSign
        if (pitchSign != 0) prevPitchSign = pitchSign

        prevSmoothedYaw = smoothedYaw
        prevSmoothedPitch = smoothedPitch
        prevSmoothedRoll = smoothedRoll
        prevSmoothTs = frame.timestampMs
    }

    private fun detectLargeEvent(
        value: Float,
        threshold: Float,
        inLarge: Boolean,
        setInLarge: (Boolean) -> Unit,
        lastEventMs: Long,
        setLastEventMs: (Long) -> Unit,
        timestampMs: Long,
        onEvent: () -> Unit,
    ) {
        val enter = threshold
        val exit = threshold - config.HEAD_EVENT_HYSTERESIS_DEG
        if (!inLarge && value >= enter) {
            if (timestampMs - lastEventMs >= config.HEAD_EVENT_REFRACTORY_MS) {
                onEvent()
                setLastEventMs(timestampMs)
            }
            setInLarge(true)
        } else if (inLarge && value < exit) {
            setInLarge(false)
        }
    }

    private fun accumulateEyeClosure(frame: FaceFrame, dt: Long) {
        if (!config.EYE_CLOSURE_METRICS_ENABLED) return
        val closed = frame.leftEyeOpen == false && frame.rightEyeOpen == false
        if (closed) {
            bothEyesClosedMs += dt
            longestClosureMs = max(longestClosureMs, bothEyesClosedMs)
            if (!closureCounted && bothEyesClosedMs >= config.PROLONGED_EYE_CLOSURE_MS) {
                prolongedClosureCount++
                closureCounted = true
            }
        } else {
            bothEyesClosedMs = 0L
            closureCounted = false
        }
    }

    private fun relativeOrNull(current: Float?, baseline: Float?): Float? {
        if (current == null) return null
        if (baseline == null) return current
        return FacialMatrixEuler.relative(current, baseline)
    }

    private fun delta(current: Float?, baseline: Float?): Float? {
        if (current == null || baseline == null) return null
        return current - baseline
    }

    private fun signOf(v: Float): Int = when {
        v > 1f -> 1
        v < -1f -> -1
        else -> 0
    }

    private fun clearLocked() {
        sessionStartMs = 0L
        lastAcceptedCaptureMs = 0L
        sessionEndMs = 0L
        visualSessionDurationMs = 0L
        facePresentDurationMs = 0L
        validTrackingDurationMs = 0L
        validFramingObservationDurationMs = 0L
        centeredFaceDurationMs = 0L
        tooCloseDurationMs = 0L
        tooFarDurationMs = 0L
        unavailableVisualTrackingDurationMs = 0L
        validGazeTrackingDurationMs = 0L
        unavailableGazeTrackingDurationMs = 0L
        towardCameraDurationMs = 0L
        currentTowardMs = 0L
        longestTowardMs = 0L
        currentAwayMs = 0L
        significantAwayCount = 0
        significantAwayTotalMs = 0L
        longestAwayMs = 0L
        awayCounted = false
        centeredHeadDurationMs = 0L
        largeYawCount = 0
        largePitchCount = 0
        largeRollCount = 0
        directionChanges = 0
        angularSpeedSum = 0.0
        angularSpeedSamples = 0
        peakAngularSpeed = 0f
        currentStableMs = 0L
        longestStableMs = 0L
        yawInLarge = false
        pitchInLarge = false
        rollInLarge = false
        lastYawEventMs = 0L
        lastPitchEventMs = 0L
        lastRollEventMs = 0L
        prevYawSign = 0
        prevPitchSign = 0
        hasSmoothed = false
        prevSmoothTs = 0L
        bothEyesClosedMs = 0L
        prolongedClosureCount = 0
        longestClosureMs = 0L
        closureCounted = false
        longestValidTrackingMs = 0L
        currentValidTrackingMs = 0L
        outOfFrameEvents = 0
        wasOutOfFrame = true
        lastStateForDuration = DurationState.UNAVAILABLE
        lastLive = LiveFaceMetrics()
        duplicateTimestampRejections = 0L
        outOfOrderTimestampRejections = 0L
        staleSessionRejections = 0L
    }
}

data class FaceFramingReportData(
    val faceDetectedPercent: Float?,
    val validTrackingPercent: Float?,
    val centeredFacePercent: Float?,
    val tooCloseDurationMs: Long,
    val tooFarDurationMs: Long,
    val outOfFrameEventCount: Int,
    val longestValidTrackingMs: Long,
    val insufficientData: Boolean,
    val visualSessionDurationMs: Long = 0L,
    val validFramingObservationDurationMs: Long = 0L,
    val unavailableVisualTrackingDurationMs: Long = 0L,
)

data class CameraGazeReportData(
    val towardCameraPercent: Float?,
    val longestTowardCameraMs: Long,
    val significantAwayCount: Int,
    val averageSignificantAwayMs: Long?,
    val longestAwayMs: Long,
    val validGazeTrackingMs: Long,
    val unavailableGazeTrackingMs: Long,
    val insufficientData: Boolean,
    val explanation: String,
) {
    companion object {
        const val DEFAULT_EXPLANATION =
            "Stima basata sulla calibrazione, sull’orientamento della testa e sulla posizione degli occhi."
    }
}

data class HeadMovementReportData(
    val centeredHeadPercent: Float?,
    val largeHorizontalTurnCount: Int,
    val largeVerticalMovementCount: Int,
    val lateralTiltCount: Int,
    val averageAngularSpeedDegPerSec: Float?,
    val peakAngularSpeedDegPerSec: Float?,
    val meaningfulDirectionChanges: Int,
    val longestStableHeadMs: Long,
    val insufficientData: Boolean,
)

data class EyeClosureReportData(
    val prolongedClosureEventCount: Int,
    val longestProlongedClosureMs: Long,
    val insufficientData: Boolean,
    val reliable: Boolean,
)

data class SessionFaceReport(
    val framing: FaceFramingReportData,
    val gaze: CameraGazeReportData,
    val headMovement: HeadMovementReportData,
    val eyeClosure: EyeClosureReportData?,
) {
    companion object {
        fun emptyInsufficient(): SessionFaceReport =
            SessionFaceReport(
                framing = FaceFramingReportData(
                    faceDetectedPercent = null,
                    validTrackingPercent = null,
                    centeredFacePercent = null,
                    tooCloseDurationMs = 0L,
                    tooFarDurationMs = 0L,
                    outOfFrameEventCount = 0,
                    longestValidTrackingMs = 0L,
                    insufficientData = true,
                ),
                gaze = CameraGazeReportData(
                    towardCameraPercent = null,
                    longestTowardCameraMs = 0L,
                    significantAwayCount = 0,
                    averageSignificantAwayMs = null,
                    longestAwayMs = 0L,
                    validGazeTrackingMs = 0L,
                    unavailableGazeTrackingMs = 0L,
                    insufficientData = true,
                    explanation = CameraGazeReportData.DEFAULT_EXPLANATION,
                ),
                headMovement = HeadMovementReportData(
                    centeredHeadPercent = null,
                    largeHorizontalTurnCount = 0,
                    largeVerticalMovementCount = 0,
                    lateralTiltCount = 0,
                    averageAngularSpeedDegPerSec = null,
                    peakAngularSpeedDegPerSec = null,
                    meaningfulDirectionChanges = 0,
                    longestStableHeadMs = 0L,
                    insufficientData = true,
                ),
                eyeClosure = null,
            )
    }
}
