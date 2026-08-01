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

class ContinuousCalibrationTest {

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
    fun eighteenSamplesUnderThreeSeconds_doNotComplete() {
        val mgr = FaceCalibrationManager(requireUpperTorso = false, requiredSamples = 10)
        var last: FaceCalibrationProgress? = null
        // 18 samples at 80ms ≈ 1.36s < 3s
        repeat(18) { i ->
            last = mgr.process(validFrame(i * 80L))
        }
        assertTrue(last!!.validContinuousDurationMs < FaceMetricsConfig.MIN_VALID_CALIBRATION_DURATION_MS)
        assertFalse(last!!.uiState == FaceCalibrationUiState.COMPLETED)
    }

    @Test
    fun validStableDataSpanningThreeSeconds_completes() {
        val mgr = FaceCalibrationManager(requireUpperTorso = false, requiredSamples = 10)
        var last: FaceCalibrationProgress? = null
        var t = 0L
        while (t <= 3_200L) {
            last = mgr.process(validFrame(t, yaw = 0.05f))
            t += 100L
        }
        assertEquals(FaceCalibrationUiState.COMPLETED, last!!.uiState)
        assertNotNull(last!!.profile)
    }

    @Test
    fun disconnectedFragments_doNotCombineIntoThreeSeconds() {
        val mgr = FaceCalibrationManager(requireUpperTorso = false, requiredSamples = 5)
        var last: FaceCalibrationProgress? = null
        // 1.5s valid
        for (t in 0L..1_500L step 100L) last = mgr.process(validFrame(t))
        // 1s loss
        last = mgr.process(validFrame(2_600L).copy(facePresent = false, confidence = 0f))
        // another 1.5s valid — should reset, not complete from combined fragments
        for (t in 2_700L..4_200L step 100L) last = mgr.process(validFrame(t))
        // At 4.2s wall but continuous window only ~1.5s after reset
        assertTrue(last!!.validContinuousDurationMs < 3_000L || last!!.uiState != FaceCalibrationUiState.COMPLETED)
        // Continue to full 3s after gap
        for (t in 4_300L..6_000L step 100L) last = mgr.process(validFrame(t))
        assertEquals(FaceCalibrationUiState.COMPLETED, last!!.uiState)
    }

    @Test
    fun briefInvalidGap_mayBeBridged() {
        val mgr = FaceCalibrationManager(requireUpperTorso = false, requiredSamples = 8)
        var last: FaceCalibrationProgress? = null
        for (t in 0L..2_900L step 100L) last = mgr.process(validFrame(t))
        // 80ms invalid
        last = mgr.process(
            validFrame(2_980L).copy(facePresent = false, confidence = 0f, leftIrisHorizontalRatio = null),
        )
        // resume
        for (t in 3_060L..3_200L step 100L) last = mgr.process(validFrame(t))
        assertEquals(FaceCalibrationUiState.COMPLETED, last!!.uiState)
    }

    @Test
    fun sustainedInvalidGap_resetsCalibration() {
        val mgr = FaceCalibrationManager(requireUpperTorso = false, requiredSamples = 5)
        var last: FaceCalibrationProgress? = null
        for (t in 0L..1_500L step 100L) last = mgr.process(validFrame(t))
        assertTrue(last!!.acceptedSamples > 0)
        // sustained loss > 300ms
        last = mgr.process(validFrame(2_000L).copy(facePresent = false, confidence = 0f))
        assertEquals(0, last!!.acceptedSamples)
        assertEquals(0L, last!!.validContinuousDurationMs)
    }

    @Test
    fun unstableHead_rejectsCompletion() {
        val mgr = FaceCalibrationManager(requireUpperTorso = false, requiredSamples = 5)
        var last: FaceCalibrationProgress? = null
        var yaw = 0f
        for (t in 0L..3_500L step 100L) {
            yaw = if ((t / 100) % 2 == 0L) 0f else 20f
            last = mgr.process(validFrame(t, yaw = yaw))
        }
        assertFalse(last!!.uiState == FaceCalibrationUiState.COMPLETED)
    }

    @Test
    fun invalidIris_rejectsSamples() {
        val mgr = FaceCalibrationManager(requireUpperTorso = false)
        val p = mgr.process(
            validFrame(0L).copy(leftIrisHorizontalRatio = null, rightIrisHorizontalRatio = null),
        )
        assertEquals(0, p.acceptedSamples)
        assertEquals(FaceCalibrationUiState.LOOK_AT_CAMERA, p.uiState)
    }

    @Test
    fun tooSmallFace_rejects() {
        val mgr = FaceCalibrationManager(requireUpperTorso = false)
        val p = mgr.process(validFrame(0L).copy(faceScale = 0.05f))
        assertEquals(FaceCalibrationUiState.MOVE_CLOSER, p.uiState)
    }

    @Test
    fun tooLargeFace_rejects() {
        val mgr = FaceCalibrationManager(requireUpperTorso = false)
        val p = mgr.process(validFrame(0L).copy(faceScale = 0.8f))
        assertEquals(FaceCalibrationUiState.MOVE_FARTHER, p.uiState)
    }

    @Test
    fun insufficientSampleCount_doesNotComplete() {
        val mgr = FaceCalibrationManager(requireUpperTorso = false, requiredSamples = 50)
        var last: FaceCalibrationProgress? = null
        for (t in 0L..3_500L step 200L) last = mgr.process(validFrame(t))
        // Only ~18 samples
        assertTrue(last!!.acceptedSamples < 50)
        assertFalse(last!!.uiState == FaceCalibrationUiState.COMPLETED)
    }

    @Test
    fun trimmedMean_outlierResistant() {
        val mean = FaceCalibrationManager.trimmedMean(listOf(1f, 2f, 3f, 4f, 100f), 0.2f)
        assertTrue(mean < 20f)
    }

    @Test
    fun interview_rejectsStaleShoulderEvidence() {
        val mgr = FaceCalibrationManager(requireUpperTorso = true, requiredSamples = 5, sessionId = 1L)
        mgr.onPoseFrame(
            UpperBodyPoseFrame(
                landmarks = mapOf(
                    PoseLandmarkId.LEFT_SHOULDER to NormalizedLandmarkPoint(PoseLandmarkId.LEFT_SHOULDER, 0.3f, 0.4f, 0.9f),
                    PoseLandmarkId.RIGHT_SHOULDER to NormalizedLandmarkPoint(PoseLandmarkId.RIGHT_SHOULDER, 0.7f, 0.4f, 0.9f),
                    PoseLandmarkId.LEFT_HIP to NormalizedLandmarkPoint(PoseLandmarkId.LEFT_HIP, 0.35f, 0.8f, 0.7f),
                ),
                imageWidth = 100,
                imageHeight = 100,
                timestampMs = 0L,
            ),
            poseSessionId = 1L,
        )
        // Face at t=500 — pose evidence age 500 > 300
        val p = mgr.process(validFrame(500L))
        assertEquals(FaceCalibrationUiState.SHOW_SHOULDERS, p.uiState)
    }

    @Test
    fun interview_acceptsFreshShouldersAndTorso_handsNotRequired() {
        val mgr = FaceCalibrationManager(requireUpperTorso = true, requiredSamples = 5, sessionId = 1L)
        var last: FaceCalibrationProgress? = null
        for (t in 0L..3_200L step 100L) {
            mgr.onPoseFrame(
                UpperBodyPoseFrame(
                    landmarks = mapOf(
                        PoseLandmarkId.LEFT_SHOULDER to NormalizedLandmarkPoint(PoseLandmarkId.LEFT_SHOULDER, 0.3f, 0.4f, 0.9f),
                        PoseLandmarkId.RIGHT_SHOULDER to NormalizedLandmarkPoint(PoseLandmarkId.RIGHT_SHOULDER, 0.7f, 0.4f, 0.9f),
                        PoseLandmarkId.LEFT_HIP to NormalizedLandmarkPoint(PoseLandmarkId.LEFT_HIP, 0.35f, 0.8f, 0.7f),
                    ),
                    imageWidth = 100,
                    imageHeight = 100,
                    timestampMs = t,
                ),
                poseSessionId = 1L,
            )
            last = mgr.process(validFrame(t))
        }
        assertEquals(FaceCalibrationUiState.COMPLETED, last!!.uiState)
        assertNull(
            UpperBodyPoseFrame(emptyMap(), 10, 10, 0L).landmarks[PoseLandmarkId.LEFT_WRIST],
        )
    }

    @Test
    fun poseEvidenceFromAnotherSession_rejected() {
        val mgr = FaceCalibrationManager(requireUpperTorso = true, sessionId = 1L)
        mgr.onPoseFrame(
            UpperBodyPoseFrame(
                landmarks = mapOf(
                    PoseLandmarkId.LEFT_SHOULDER to NormalizedLandmarkPoint(PoseLandmarkId.LEFT_SHOULDER, 0.3f, 0.4f, 0.9f),
                    PoseLandmarkId.RIGHT_SHOULDER to NormalizedLandmarkPoint(PoseLandmarkId.RIGHT_SHOULDER, 0.7f, 0.4f, 0.9f),
                    PoseLandmarkId.LEFT_HIP to NormalizedLandmarkPoint(PoseLandmarkId.LEFT_HIP, 0.35f, 0.8f, 0.7f),
                ),
                imageWidth = 100,
                imageHeight = 100,
                timestampMs = 100L,
            ),
            poseSessionId = 99L,
        )
        val p = mgr.process(validFrame(100L))
        assertEquals(FaceCalibrationUiState.SHOW_SHOULDERS, p.uiState)
    }

    @Test
    fun progressReflectsValidContinuousDuration() {
        val mgr = FaceCalibrationManager(requireUpperTorso = false, requiredSamples = 5)
        var last: FaceCalibrationProgress? = null
        for (t in 0L..1_500L step 100L) last = mgr.process(validFrame(t))
        assertEquals(1_500L, last!!.validContinuousDurationMs)
        assertTrue(last!!.progressFraction in 0f..1f)
    }

    @Test
    fun cancellingCalibration_clearsSamplesAndTimestamps() {
        val mgr = FaceCalibrationManager(requireUpperTorso = false)
        mgr.process(validFrame(0L))
        mgr.process(validFrame(500L))
        mgr.reset()
        val p = mgr.process(validFrame(600L).copy(facePresent = false, confidence = 0f))
        assertEquals(0, p.acceptedSamples)
        assertEquals(0L, p.validContinuousDurationMs)
    }
}

class SignedIrisGeometryTest {

    @Test
    fun irisCenteredHorizontally() {
        val r = IrisGeometry.eyeRatios(
            outerCorner = IrisGeometry.Point(0f, 0f),
            innerCorner = IrisGeometry.Point(1f, 0f),
            upperLid = IrisGeometry.Point(0.5f, -0.2f),
            lowerLid = IrisGeometry.Point(0.5f, 0.2f),
            irisCenter = IrisGeometry.Point(0.5f, 0f),
        )!!
        assertEquals(0.5f, r.horizontal, 0.05f)
    }

    @Test
    fun irisDisplacedTowardLocalEyeLeft_andRight() {
        val base = Triple(
            IrisGeometry.Point(0f, 0f),
            IrisGeometry.Point(1f, 0f),
            Pair(IrisGeometry.Point(0.5f, -0.2f), IrisGeometry.Point(0.5f, 0.2f)),
        )
        val left = IrisGeometry.eyeRatios(base.first, base.second, base.third.first, base.third.second, IrisGeometry.Point(0.25f, 0f))!!
        val right = IrisGeometry.eyeRatios(base.first, base.second, base.third.first, base.third.second, IrisGeometry.Point(0.75f, 0f))!!
        assertTrue(left.horizontal < right.horizontal)
    }

    @Test
    fun irisDisplacedUpAndDown() {
        val up = IrisGeometry.eyeRatios(
            IrisGeometry.Point(0f, 0f), IrisGeometry.Point(1f, 0f),
            IrisGeometry.Point(0.5f, -0.2f), IrisGeometry.Point(0.5f, 0.2f),
            IrisGeometry.Point(0.5f, -0.1f),
        )!!
        val down = IrisGeometry.eyeRatios(
            IrisGeometry.Point(0f, 0f), IrisGeometry.Point(1f, 0f),
            IrisGeometry.Point(0.5f, -0.2f), IrisGeometry.Point(0.5f, 0.2f),
            IrisGeometry.Point(0.5f, 0.1f),
        )!!
        assertTrue(up.vertical < down.vertical)
    }

    @Test
    fun verticalDisplacement_doesNotChangeHorizontalSignificantly() {
        val a = IrisGeometry.eyeRatios(
            IrisGeometry.Point(0f, 0f), IrisGeometry.Point(1f, 0f),
            IrisGeometry.Point(0.5f, -0.2f), IrisGeometry.Point(0.5f, 0.2f),
            IrisGeometry.Point(0.5f, -0.05f),
        )!!
        val b = IrisGeometry.eyeRatios(
            IrisGeometry.Point(0f, 0f), IrisGeometry.Point(1f, 0f),
            IrisGeometry.Point(0.5f, -0.2f), IrisGeometry.Point(0.5f, 0.2f),
            IrisGeometry.Point(0.5f, 0.05f),
        )!!
        assertEquals(a.horizontal, b.horizontal, 0.02f)
    }

    @Test
    fun horizontalDisplacement_doesNotChangeVerticalSignificantly() {
        val a = IrisGeometry.eyeRatios(
            IrisGeometry.Point(0f, 0f), IrisGeometry.Point(1f, 0f),
            IrisGeometry.Point(0.5f, -0.2f), IrisGeometry.Point(0.5f, 0.2f),
            IrisGeometry.Point(0.35f, 0f),
        )!!
        val b = IrisGeometry.eyeRatios(
            IrisGeometry.Point(0f, 0f), IrisGeometry.Point(1f, 0f),
            IrisGeometry.Point(0.5f, -0.2f), IrisGeometry.Point(0.5f, 0.2f),
            IrisGeometry.Point(0.65f, 0f),
        )!!
        assertEquals(a.vertical, b.vertical, 0.05f)
    }

    @Test
    fun zeroEyeWidth_invalid() {
        assertNull(
            IrisGeometry.eyeRatios(
                IrisGeometry.Point(0f, 0f), IrisGeometry.Point(0f, 0f),
                IrisGeometry.Point(0f, -0.1f), IrisGeometry.Point(0f, 0.1f),
                IrisGeometry.Point(0f, 0f),
            ),
        )
    }

    @Test
    fun zeroEyeHeight_invalid() {
        assertNull(
            IrisGeometry.eyeRatios(
                IrisGeometry.Point(0f, 0f), IrisGeometry.Point(1f, 0f),
                IrisGeometry.Point(0.5f, 0f), IrisGeometry.Point(0.5f, 0f),
                IrisGeometry.Point(0.5f, 0f),
            ),
        )
    }

    @Test
    fun nonFiniteLandmark_invalid() {
        assertNull(
            IrisGeometry.eyeRatios(
                IrisGeometry.Point(Float.NaN, 0f), IrisGeometry.Point(1f, 0f),
                IrisGeometry.Point(0.5f, -0.1f), IrisGeometry.Point(0.5f, 0.1f),
                IrisGeometry.Point(0.5f, 0f),
            ),
        )
    }

    @Test
    fun horizontalAndVerticalThresholds_independent() {
        assertTrue(FaceMetricsConfig.MAX_HORIZONTAL_IRIS_DELTA > 0f)
        assertTrue(FaceMetricsConfig.MAX_VERTICAL_IRIS_DELTA > 0f)
        // Classifier must not compare vertical to horizontal threshold alone — covered in gaze tests.
    }
}

class DurationBasedFaceMetricsTest {

    private fun calibrated() = FaceCalibrationProfile(
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

    private fun good(ts: Long, cx: Float = 0.5f) = FaceFrame(
        timestampMs = ts,
        facePresent = true,
        faceCenterX = cx,
        faceCenterY = 0.45f,
        faceScale = 0.25f,
        headYawDeg = 0f,
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
    fun equalElapsedDuration_equalPercentages_despiteDifferentFrameCounts() {
        val a = FaceMetricsEngine().also { it.reset(); it.setCalibration(calibrated()) }
        val b = FaceMetricsEngine().also { it.reset(); it.setCalibration(calibrated()) }
        // Engine A: dense frames over 900ms (within stale limit between frames)
        for (i in 0..9) a.processFrame(good(i * 100L))
        a.finalizeAt(900L)
        // Engine B: sparse but every gap <= STALE_RESULT_MS
        for (i in 0..3) b.processFrame(good(i * 300L))
        b.finalizeAt(900L)
        val ra = a.buildReport()
        val rb = b.buildReport()
        assertEquals(ra.framing.faceDetectedPercent!!, rb.framing.faceDetectedPercent!!, 2f)
        assertEquals(ra.framing.validTrackingPercent!!, rb.framing.validTrackingPercent!!, 2f)
    }

    @Test
    fun duplicateTimestamp_contributesNoDuration() {
        val e = FaceMetricsEngine().also { it.reset(); it.setCalibration(calibrated()) }
        e.processFrame(good(100L))
        e.processFrame(good(100L))
        assertEquals(1L, e.duplicateTimestampRejections)
        e.finalizeAt(100L)
        assertEquals(0L, e.buildReport().framing.visualSessionDurationMs)
    }

    @Test
    fun outOfOrderTimestamp_contributesNoDuration() {
        val e = FaceMetricsEngine().also { it.reset(); it.setCalibration(calibrated()) }
        e.processFrame(good(200L))
        e.processFrame(good(100L))
        assertEquals(1L, e.outOfOrderTimestampRejections)
    }

    @Test
    fun staleGap_becomesUnavailableTracking() {
        val e = FaceMetricsEngine().also { it.reset(); it.setCalibration(calibrated()) }
        e.processFrame(good(10_000L))
        e.processFrame(good(12_000L))
        val r = e.buildReport()
        assertTrue(r.framing.unavailableVisualTrackingDurationMs > 0L)
        assertTrue(r.framing.unavailableVisualTrackingDurationMs >= 12_000L - 10_000L - FaceMetricsConfig.STALE_RESULT_MS)
    }

    @Test
    fun invalidTracking_notCountedAsGazeAway() {
        val e = FaceMetricsEngine().also { it.reset(); it.setCalibration(calibrated()) }
        e.processFrame(good(0L))
        e.processFrame(good(500L).copy(confidence = 0.1f))
        val r = e.buildReport()
        assertEquals(0, r.gaze.significantAwayCount)
    }

    @Test
    fun blink_notCountedAsGazeAway() {
        val e = FaceMetricsEngine().also { it.reset(); it.setCalibration(calibrated()) }
        e.processFrame(good(0L))
        e.processFrame(good(300L).copy(leftEyeOpen = false, rightEyeOpen = false))
        assertEquals(0, e.buildReport().gaze.significantAwayCount)
    }

    @Test
    fun percentagesRemainIn0to100() {
        val e = FaceMetricsEngine().also { it.reset(); it.setCalibration(calibrated()) }
        for (t in 0L..5_000L step 80L) e.processFrame(good(t))
        e.finalizeAt(5_000L)
        val r = e.buildReport()
        listOfNotNull(
            r.framing.faceDetectedPercent,
            r.framing.validTrackingPercent,
            r.framing.centeredFacePercent,
            r.gaze.towardCameraPercent,
            r.headMovement.centeredHeadPercent,
        ).forEach { assertTrue(it in 0f..100f) }
    }

    @Test
    fun practiceInstructions_modeSpecific() {
        assertFalse(
            com.orato.app.ui.practice.practiceRunningInstruction(
                com.orato.app.domain.model.VisualAnalysisMode.BODY_ONLY,
            ).contains("sguardo", ignoreCase = true),
        )
        assertTrue(
            com.orato.app.ui.practice.practiceRunningInstruction(
                com.orato.app.domain.model.VisualAnalysisMode.FACE_ONLY,
            ).contains("viso", ignoreCase = true),
        )
    }
}
