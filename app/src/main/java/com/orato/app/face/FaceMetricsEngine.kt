package com.orato.app.face

import kotlin.math.abs
import kotlin.math.max

/**
 * Session face metrics: framing, gaze, head movement, optional eye closure.
 * Thread-safe for off-main analysis callbacks.
 */
class FaceMetricsEngine {
    private val config = FaceMetricsConfig
    private val lock = Any()
    private val gazeClassifier = CameraGazeClassifier()

    private var accumulating = false
    private var released = false
    private var calibration: FaceCalibrationProfile? = null

    private var sessionStartMs: Long = 0L
    private var lastTimestampMs: Long = 0L

    private var totalFrames = 0
    private var facePresentFrames = 0
    private var validTrackingFrames = 0
    private var centeredFrames = 0
    private var tooCloseMs = 0L
    private var tooFarMs = 0L
    private var outOfFrameEvents = 0
    private var wasOutOfFrame = true

    private var validGazeTrackingMs = 0L
    private var unavailableGazeTrackingMs = 0L
    private var towardCameraMs = 0L
    private var currentTowardMs = 0L
    private var longestTowardMs = 0L
    private var currentAwayMs = 0L
    private var significantAwayCount = 0
    private var significantAwayTotalMs = 0L
    private var longestAwayMs = 0L
    private var awayCounted = false

    private var centeredHeadFrames = 0
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

    @Volatile
    private var latestLive: LiveFaceMetrics = LiveFaceMetrics()

    fun liveMetrics(): LiveFaceMetrics = latestLive

    fun setCalibration(profile: FaceCalibrationProfile?) {
        synchronized(lock) {
            calibration = profile
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
        synchronized(lock) {
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

    fun processFrame(frame: FaceFrame): LiveFaceMetrics {
        synchronized(lock) {
            if (released) return latestLive
            val profile = calibration
            val live = evaluateLive(frame, profile)
            latestLive = live

            if (!accumulating) return live

            val dt = if (lastTimestampMs > 0L) {
                (frame.timestampMs - lastTimestampMs).coerceAtLeast(0L)
            } else {
                0L
            }
            if (sessionStartMs == 0L) sessionStartMs = frame.timestampMs
            lastTimestampMs = frame.timestampMs

            totalFrames++
            if (frame.facePresent) facePresentFrames++

            val framingValid = live.validTracking
            if (framingValid) {
                validTrackingFrames++
                currentValidTrackingMs += dt
                longestValidTrackingMs = max(longestValidTrackingMs, currentValidTrackingMs)
                if (isCentered(frame)) centeredFrames++
                if (wasOutOfFrame) {
                    // re-entered
                }
                wasOutOfFrame = false
            } else {
                currentValidTrackingMs = 0L
                if (frame.facePresent) {
                    val scale = frame.faceScale
                    if (scale != null) {
                        if (scale > config.MAX_FACE_SCALE) tooCloseMs += dt
                        if (scale < config.MIN_FACE_SCALE) tooFarMs += dt
                    }
                }
                if (!wasOutOfFrame && (!frame.facePresent || live.validityReason == FaceValidityReason.FACE_OFF_CENTER)) {
                    outOfFrameEvents++
                }
                wasOutOfFrame = !frame.facePresent || live.validityReason == FaceValidityReason.FACE_OFF_CENTER ||
                    live.validityReason == FaceValidityReason.NO_FACE
            }

            accumulateGaze(live.gazeState, dt)
            accumulateHead(frame, profile, live, dt)
            accumulateEyeClosure(frame, dt)

            return live
        }
    }

    fun buildReport(): SessionFaceReport {
        synchronized(lock) {
            val total = totalFrames.coerceAtLeast(1)
            val presencePct = 100f * facePresentFrames / total
            val validPct = 100f * validTrackingFrames / total
            val centeredPct = if (validTrackingFrames > 0) {
                100f * centeredFrames / validTrackingFrames
            } else {
                null
            }
            val centeredHeadPct = if (validTrackingFrames > 0) {
                100f * centeredHeadFrames / validTrackingFrames
            } else {
                null
            }
            val towardPct = if (validGazeTrackingMs > 0L) {
                100.0 * towardCameraMs / validGazeTrackingMs
            } else {
                null
            }
            val avgAway = if (significantAwayCount > 0) {
                significantAwayTotalMs.toDouble() / significantAwayCount
            } else {
                null
            }
            val avgSpeed = if (angularSpeedSamples > 0) {
                (angularSpeedSum / angularSpeedSamples).toFloat()
            } else {
                null
            }

            val framing = FaceFramingReportData(
                faceDetectedPercent = presencePct,
                validTrackingPercent = validPct,
                centeredFacePercent = centeredPct,
                tooCloseDurationMs = tooCloseMs,
                tooFarDurationMs = tooFarMs,
                outOfFrameEventCount = outOfFrameEvents,
                longestValidTrackingMs = longestValidTrackingMs,
                insufficientData = facePresentFrames == 0,
            )
            val gaze = CameraGazeReportData(
                towardCameraPercent = towardPct?.toFloat(),
                longestTowardCameraMs = longestTowardMs,
                significantAwayCount = significantAwayCount,
                averageSignificantAwayMs = avgAway?.toLong(),
                longestAwayMs = longestAwayMs,
                validGazeTrackingMs = validGazeTrackingMs,
                unavailableGazeTrackingMs = unavailableGazeTrackingMs,
                insufficientData = validGazeTrackingMs == 0L,
                explanation = CameraGazeReportData.DEFAULT_EXPLANATION,
            )
            val head = HeadMovementReportData(
                centeredHeadPercent = centeredHeadPct,
                largeHorizontalTurnCount = largeYawCount,
                largeVerticalMovementCount = largePitchCount,
                lateralTiltCount = largeRollCount,
                averageAngularSpeedDegPerSec = avgSpeed,
                peakAngularSpeedDegPerSec = peakAngularSpeed.takeIf { angularSpeedSamples > 0 },
                meaningfulDirectionChanges = directionChanges,
                longestStableHeadMs = longestStableMs,
                insufficientData = validTrackingFrames == 0,
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

            return SessionFaceReport(
                framing = framing,
                gaze = gaze,
                headMovement = head,
                eyeClosure = eye,
            )
        }
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
        if (!isCentered(frame)) {
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
                validTracking = false,
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

        val irisOk = leftH != null || rightH != null
        if (!irisOk) {
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

    private fun accumulateGaze(state: CameraGazeState, dt: Long) {
        when (state) {
            CameraGazeState.TOWARD_CAMERA -> {
                validGazeTrackingMs += dt
                towardCameraMs += dt
                currentTowardMs += dt
                longestTowardMs = max(longestTowardMs, currentTowardMs)
                finalizeAwayIfNeeded()
                currentAwayMs = 0L
                awayCounted = false
            }
            CameraGazeState.AWAY -> {
                validGazeTrackingMs += dt
                currentAwayMs += dt
                longestAwayMs = max(longestAwayMs, currentAwayMs)
                currentTowardMs = 0L
                if (!awayCounted && currentAwayMs >= config.SIGNIFICANT_GAZE_AWAY_MS) {
                    significantAwayCount++
                    awayCounted = true
                }
            }
            CameraGazeState.INVALID -> {
                unavailableGazeTrackingMs += dt
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

    private fun accumulateHead(
        frame: FaceFrame,
        profile: FaceCalibrationProfile?,
        live: LiveFaceMetrics,
        dt: Long,
    ) {
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

        if (abs(smoothedYaw) <= config.HEAD_CENTERED_YAW_DEG &&
            abs(smoothedPitch) <= config.HEAD_CENTERED_PITCH_DEG &&
            abs(smoothedRoll) <= config.HEAD_CENTERED_ROLL_DEG
        ) {
            centeredHeadFrames++
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

    private fun isCentered(frame: FaceFrame): Boolean {
        val x = frame.faceCenterX ?: return false
        val y = frame.faceCenterY ?: return false
        return x in config.CENTER_X_MIN..config.CENTER_X_MAX &&
            y in config.CENTER_Y_MIN..config.CENTER_Y_MAX
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
        lastTimestampMs = 0L
        totalFrames = 0
        facePresentFrames = 0
        validTrackingFrames = 0
        centeredFrames = 0
        tooCloseMs = 0L
        tooFarMs = 0L
        outOfFrameEvents = 0
        wasOutOfFrame = true
        validGazeTrackingMs = 0L
        unavailableGazeTrackingMs = 0L
        towardCameraMs = 0L
        currentTowardMs = 0L
        longestTowardMs = 0L
        currentAwayMs = 0L
        significantAwayCount = 0
        significantAwayTotalMs = 0L
        longestAwayMs = 0L
        awayCounted = false
        centeredHeadFrames = 0
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
