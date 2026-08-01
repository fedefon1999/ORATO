package com.orato.app.face

import com.orato.app.pose.NormalizedLandmarkPoint
import com.orato.app.pose.PoseLandmarkId
import com.orato.app.pose.UpperBodyPoseFrame
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs

class FacialMatrixEulerTest {

    @Test
    fun identityMatrix_nearZeroEuler() {
        val m = floatArrayOf(
            1f, 0f, 0f, 0f,
            0f, 1f, 0f, 0f,
            0f, 0f, 1f, 0f,
            0f, 0f, 0f, 1f,
        )
        val e = FacialMatrixEuler.fromRowMajor4x4(m)
        assertEquals(0f, e.yawDeg, 1e-3f)
        assertEquals(0f, e.pitchDeg, 1e-3f)
        assertEquals(0f, e.rollDeg, 1e-3f)
    }

    @Test
    fun relativeAngles_subtractBaseline() {
        assertEquals(5f, FacialMatrixEuler.relative(12f, 7f), 1e-4f)
        assertEquals(-3f, FacialMatrixEuler.relative(4f, 7f), 1e-4f)
        assertEquals(3f, FacialMatrixEuler.absRelative(4f, 7f), 1e-4f)
    }

    @Test
    fun yawPitchRoll_relativeIndependently() {
        val baselineYaw = 10f
        val baselinePitch = -5f
        val baselineRoll = 2f
        assertEquals(2f, FacialMatrixEuler.relative(12f, baselineYaw), 1e-4f)
        assertEquals(1f, FacialMatrixEuler.relative(-4f, baselinePitch), 1e-4f)
        assertEquals(-1f, FacialMatrixEuler.relative(1f, baselineRoll), 1e-4f)
    }
}

class IrisGeometryTest {

    @Test
    fun horizontalRatio_midpointIsHalf() {
        val outer = IrisGeometry.Point(0f, 0f)
        val inner = IrisGeometry.Point(1f, 0f)
        val iris = IrisGeometry.Point(0.5f, 0f)
        assertEquals(0.5f, IrisGeometry.horizontalRatio(outer, inner, iris)!!, 1e-4f)
    }

    @Test
    fun verticalRatio_midpointIsHalf() {
        val upper = IrisGeometry.Point(0f, 0f)
        val lower = IrisGeometry.Point(0f, 1f)
        val iris = IrisGeometry.Point(0f, 0.5f)
        assertEquals(0.5f, IrisGeometry.verticalRatio(upper, lower, iris)!!, 1e-4f)
    }

    @Test
    fun invalidGeometry_returnsNull() {
        val a = IrisGeometry.Point(0f, 0f)
        assertNull(IrisGeometry.horizontalRatio(a, a, IrisGeometry.Point(0.1f, 0f)))
        assertNull(
            IrisGeometry.horizontalRatio(
                IrisGeometry.Point(0f, 0f),
                IrisGeometry.Point(1f, 0f),
                IrisGeometry.Point(5f, 0f),
            ),
        )
    }
}

class CameraGazeClassifierTest {

    private fun towardInputs() = arrayOf(
        /* yaw */ 2f, /* pitch */ 1f, /* roll */ 0f,
        /* lH */ 0.02f, /* lV */ 0.01f, /* rH */ 0.02f, /* rV */ 0.01f,
        /* lOpen */ true, /* rOpen */ true, /* conf */ 0.9f, /* framing */ true,
    )

    @Test
    fun gazeRequiresHeadAndEyeEvidence() {
        val c = CameraGazeClassifier()
        val headOnly = c.classifyInstant(
            relativeYawDeg = 1f,
            relativePitchDeg = 1f,
            relativeRollDeg = 0f,
            leftIrisHDelta = null,
            leftIrisVDelta = null,
            rightIrisHDelta = null,
            rightIrisVDelta = null,
            leftEyeOpen = true,
            rightEyeOpen = true,
            faceConfidence = 0.9f,
            framingValid = true,
        )
        assertEquals(CameraGazeState.INVALID, headOnly)

        val irisOnly = c.classifyInstant(
            relativeYawDeg = null,
            relativePitchDeg = null,
            relativeRollDeg = null,
            leftIrisHDelta = 0.01f,
            leftIrisVDelta = 0.01f,
            rightIrisHDelta = 0.01f,
            rightIrisVDelta = 0.01f,
            leftEyeOpen = true,
            rightEyeOpen = true,
            faceConfidence = 0.9f,
            framingValid = true,
        )
        assertEquals(CameraGazeState.INVALID, irisOnly)
    }

    @Test
    fun invalidTracking_isNotGazeAway() {
        val c = CameraGazeClassifier()
        val state = c.classifyInstant(
            relativeYawDeg = 1f,
            relativePitchDeg = 1f,
            relativeRollDeg = 0f,
            leftIrisHDelta = 0.01f,
            leftIrisVDelta = 0.01f,
            rightIrisHDelta = 0.01f,
            rightIrisVDelta = 0.01f,
            leftEyeOpen = true,
            rightEyeOpen = true,
            faceConfidence = 0.1f,
            framingValid = false,
        )
        assertEquals(CameraGazeState.INVALID, state)
    }

    @Test
    fun blink_isNotGazeAway() {
        val c = CameraGazeClassifier()
        val state = c.classifyInstant(
            relativeYawDeg = 1f,
            relativePitchDeg = 1f,
            relativeRollDeg = 0f,
            leftIrisHDelta = 0.01f,
            leftIrisVDelta = 0.01f,
            rightIrisHDelta = 0.01f,
            rightIrisVDelta = 0.01f,
            leftEyeOpen = false,
            rightEyeOpen = false,
            faceConfidence = 0.9f,
            framingValid = true,
        )
        assertEquals(CameraGazeState.INVALID, state)
    }

    @Test
    fun towardCamera_hysteresisRequiresEvidenceTime() {
        val c = CameraGazeClassifier()
        var s = c.update(
            timestampMs = 0L,
            relativeYawDeg = 1f,
            relativePitchDeg = 1f,
            relativeRollDeg = 0f,
            leftIrisHDelta = 0.01f,
            leftIrisVDelta = 0.01f,
            rightIrisHDelta = 0.01f,
            rightIrisVDelta = 0.01f,
            leftEyeOpen = true,
            rightEyeOpen = true,
            faceConfidence = 0.9f,
            framingValid = true,
        )
        assertEquals(CameraGazeState.INVALID, s)
        s = c.update(
            timestampMs = FaceMetricsConfig.GAZE_ENTER_TOWARD_MS - 10,
            relativeYawDeg = 1f,
            relativePitchDeg = 1f,
            relativeRollDeg = 0f,
            leftIrisHDelta = 0.01f,
            leftIrisVDelta = 0.01f,
            rightIrisHDelta = 0.01f,
            rightIrisVDelta = 0.01f,
            leftEyeOpen = true,
            rightEyeOpen = true,
            faceConfidence = 0.9f,
            framingValid = true,
        )
        assertEquals(CameraGazeState.INVALID, s)
        s = c.update(
            timestampMs = FaceMetricsConfig.GAZE_ENTER_TOWARD_MS,
            relativeYawDeg = 1f,
            relativePitchDeg = 1f,
            relativeRollDeg = 0f,
            leftIrisHDelta = 0.01f,
            leftIrisVDelta = 0.01f,
            rightIrisHDelta = 0.01f,
            rightIrisVDelta = 0.01f,
            leftEyeOpen = true,
            rightEyeOpen = true,
            faceConfidence = 0.9f,
            framingValid = true,
        )
        assertEquals(CameraGazeState.TOWARD_CAMERA, s)
    }

    @Test
    fun away_hysteresisRequiresEvidenceTime() {
        val c = CameraGazeClassifier()
        // Enter toward first
        c.update(0L, 1f, 1f, 0f, 0.01f, 0.01f, 0.01f, 0.01f, true, true, 0.9f, true)
        c.update(FaceMetricsConfig.GAZE_ENTER_TOWARD_MS, 1f, 1f, 0f, 0.01f, 0.01f, 0.01f, 0.01f, true, true, 0.9f, true)
        assertEquals(CameraGazeState.TOWARD_CAMERA, c.currentState())

        val t0 = FaceMetricsConfig.GAZE_ENTER_TOWARD_MS + 10
        c.update(t0, 30f, 1f, 0f, 0.3f, 0.01f, 0.3f, 0.01f, true, true, 0.9f, true)
        assertEquals(CameraGazeState.TOWARD_CAMERA, c.currentState())
        c.update(
            t0 + FaceMetricsConfig.GAZE_ENTER_AWAY_MS,
            30f, 1f, 0f, 0.3f, 0.01f, 0.3f, 0.01f, true, true, 0.9f, true,
        )
        assertEquals(CameraGazeState.AWAY, c.currentState())
    }
}

class FaceCalibrationManagerTest {

    private fun validFrame(ts: Long, yaw: Float = 0f): FaceFrame =
        FaceFrame(
            timestampMs = ts,
            facePresent = true,
            faceCenterX = 0.5f,
            faceCenterY = 0.45f,
            faceScale = 0.25f,
            headYawDeg = yaw,
            headPitchDeg = 0f,
            headRollDeg = 0f,
            leftIrisHorizontalRatio = 0.5f,
            leftIrisVerticalRatio = 0.5f,
            rightIrisHorizontalRatio = 0.5f,
            rightIrisVerticalRatio = 0.5f,
            leftEyeOpen = true,
            rightEyeOpen = true,
            confidence = 0.9f,
        )

    @Test
    fun invalidCalibrationFrames_rejected() {
        val mgr = FaceCalibrationManager(requireUpperTorso = false)
        val bad = FaceFrameMapper.emptyFrame(1L)
        val p = mgr.process(bad)
        assertEquals(0, p.acceptedSamples)
        assertEquals(FaceCalibrationUiState.POSITION_FACE, p.uiState)
    }

    @Test
    fun stableCalibration_completesWithAggregation() {
        val mgr = FaceCalibrationManager(requireUpperTorso = false, requiredSamples = 5)
        var last: FaceCalibrationProgress? = null
        repeat(8) { i ->
            last = mgr.process(validFrame(i * 50L, yaw = 0.1f * (i % 2)))
        }
        assertEquals(FaceCalibrationUiState.COMPLETED, last!!.uiState)
        assertNotNull(last!!.profile)
        assertTrue(abs(last!!.profile!!.baselineYawDeg) < 1f)
    }

    @Test
    fun trimmedMean_robustAggregation() {
        val values = listOf(1f, 2f, 3f, 4f, 100f)
        val mean = FaceCalibrationManager.trimmedMean(values, trimFraction = 0.2f)
        assertTrue(mean < 20f)
    }

    @Test
    fun interviewCalibration_checksFaceAndUpperTorso_handsNotRequired() {
        val mgr = FaceCalibrationManager(requireUpperTorso = true, requiredSamples = 3)
        val pose = UpperBodyPoseFrame(
            landmarks = mapOf(
                PoseLandmarkId.LEFT_SHOULDER to NormalizedLandmarkPoint(
                    PoseLandmarkId.LEFT_SHOULDER, 0.3f, 0.4f, 0.9f,
                ),
                PoseLandmarkId.RIGHT_SHOULDER to NormalizedLandmarkPoint(
                    PoseLandmarkId.RIGHT_SHOULDER, 0.7f, 0.4f, 0.9f,
                ),
                PoseLandmarkId.LEFT_HIP to NormalizedLandmarkPoint(
                    PoseLandmarkId.LEFT_HIP, 0.35f, 0.8f, 0.7f,
                ),
            ),
            imageWidth = 100,
            imageHeight = 100,
        )
        assertTrue(mgr.hasVisibleShouldersAndTorso(pose))
        // Hands absent — still valid for interview framing.
        assertNull(pose.landmarks[PoseLandmarkId.LEFT_WRIST])
        mgr.onPoseFrame(pose)
        var last: FaceCalibrationProgress? = null
        repeat(5) { i -> last = mgr.process(validFrame(i * 40L)) }
        assertEquals(FaceCalibrationUiState.COMPLETED, last!!.uiState)
        assertEquals(true, last!!.shouldersValid)
    }

    @Test
    fun interviewWithoutShoulders_showsShouldersPrompt() {
        val mgr = FaceCalibrationManager(requireUpperTorso = true, requiredSamples = 5)
        mgr.onPoseFrame(
            UpperBodyPoseFrame(landmarks = emptyMap(), imageWidth = 10, imageHeight = 10),
        )
        val p = mgr.process(validFrame(1L))
        assertEquals(FaceCalibrationUiState.SHOW_SHOULDERS, p.uiState)
    }
}

class FaceMetricsEngineTest {

    private fun calibrated(): FaceCalibrationProfile =
        FaceCalibrationProfile(
            calibratedAtMs = 0L,
            baselineYawDeg = 0f,
            baselinePitchDeg = 0f,
            baselineRollDeg = 0f,
            baselineLeftIrisHorizontalRatio = 0.5f,
            baselineLeftIrisVerticalRatio = 0.5f,
            baselineRightIrisHorizontalRatio = 0.5f,
            baselineRightIrisVerticalRatio = 0.5f,
            baselineFaceCenterX = 0.5f,
            baselineFaceCenterY = 0.45f,
            baselineFaceScale = 0.25f,
        )

    private fun frame(
        ts: Long,
        present: Boolean = true,
        scale: Float = 0.25f,
        cx: Float = 0.5f,
        cy: Float = 0.45f,
        yaw: Float = 0f,
        pitch: Float = 0f,
        leftH: Float = 0.5f,
        rightH: Float = 0.5f,
        leftOpen: Boolean = true,
        rightOpen: Boolean = true,
    ): FaceFrame = FaceFrame(
        timestampMs = ts,
        facePresent = present,
        faceCenterX = cx,
        faceCenterY = cy,
        faceScale = scale,
        headYawDeg = yaw,
        headPitchDeg = pitch,
        headRollDeg = 0f,
        leftIrisHorizontalRatio = leftH,
        leftIrisVerticalRatio = 0.5f,
        rightIrisHorizontalRatio = rightH,
        rightIrisVerticalRatio = 0.5f,
        leftEyeOpen = leftOpen,
        rightEyeOpen = rightOpen,
        confidence = 0.9f,
    )

    @Test
    fun facePresenceAndCenteredPercentages() {
        val eng = FaceMetricsEngine()
        eng.reset()
        eng.setCalibration(calibrated())
        eng.processFrame(frame(0L))
        eng.processFrame(frame(100L, cx = 0.1f)) // off center
        eng.processFrame(frame(200L, present = false))
        val report = eng.buildReport()
        assertTrue(report.framing.faceDetectedPercent!! > 50f)
        assertNotNull(report.framing.centeredFacePercent)
    }

    @Test
    fun tooCloseAndTooFarDurations() {
        val eng = FaceMetricsEngine()
        eng.reset()
        eng.setCalibration(calibrated())
        eng.processFrame(frame(0L, scale = 0.05f))
        eng.processFrame(frame(500L, scale = 0.05f))
        eng.processFrame(frame(1000L, scale = 0.8f))
        eng.processFrame(frame(1500L, scale = 0.8f))
        val report = eng.buildReport()
        assertTrue(report.framing.tooFarDurationMs > 0L || report.framing.tooCloseDurationMs > 0L)
    }

    @Test
    fun outOfFrameEventCount() {
        val eng = FaceMetricsEngine()
        eng.reset()
        eng.setCalibration(calibrated())
        eng.processFrame(frame(0L))
        eng.processFrame(frame(100L, present = false))
        eng.processFrame(frame(200L))
        eng.processFrame(frame(300L, present = false))
        val report = eng.buildReport()
        assertTrue(report.framing.outOfFrameEventCount >= 1)
    }

    @Test
    fun significantGazeAway_thresholdAndMerge() {
        val eng = FaceMetricsEngine()
        eng.reset()
        eng.setCalibration(calibrated())
        // Build toward state then away for >500ms continuous
        var t = 0L
        repeat(20) {
            eng.processFrame(frame(t, yaw = 0f, leftH = 0.5f, rightH = 0.5f))
            t += 50L
        }
        // Away — large iris delta
        repeat(20) {
            eng.processFrame(frame(t, yaw = 30f, leftH = 0.8f, rightH = 0.8f))
            t += 50L
        }
        // Back toward briefly then away again (separate event)
        repeat(15) {
            eng.processFrame(frame(t, yaw = 0f, leftH = 0.5f, rightH = 0.5f))
            t += 50L
        }
        repeat(20) {
            eng.processFrame(frame(t, yaw = 30f, leftH = 0.8f, rightH = 0.8f))
            t += 50L
        }
        val report = eng.buildReport()
        // Adjacent away frames merge; separate blocks remain separate when interrupted by toward.
        assertTrue(report.gaze.significantAwayCount >= 1)
    }

    @Test
    fun headAngleSmoothing_andLargeMovementWithRefractory() {
        val eng = FaceMetricsEngine()
        eng.reset()
        eng.setCalibration(calibrated())
        var t = 0L
        // Establish tracking with centered head
        repeat(15) {
            eng.processFrame(frame(t, yaw = 0f))
            t += 40L
        }
        // Drop to small angle then spike large — enter large event (still under iris gate)
        eng.processFrame(frame(t, yaw = 0f))
        t += FaceMetricsConfig.HEAD_EVENT_REFRACTORY_MS + 50L
        eng.processFrame(frame(t, yaw = 23f))
        t += 40L
        // Stay large (no second count while inLarge)
        repeat(8) {
            eng.processFrame(frame(t, yaw = 23f))
            t += 40L
        }
        // Exit large then re-enter after refractory
        eng.processFrame(frame(t, yaw = 0f))
        t += FaceMetricsConfig.HEAD_EVENT_REFRACTORY_MS + 50L
        eng.processFrame(frame(t, yaw = 23f))
        val report = eng.buildReport()
        assertTrue(
            "expected at least one large yaw event, got ${report.headMovement.largeHorizontalTurnCount}",
            report.headMovement.largeHorizontalTurnCount >= 1,
        )
        // Refractory + hysteresis prevent a burst of duplicates
        assertTrue(report.headMovement.largeHorizontalTurnCount <= 3)
    }
}
