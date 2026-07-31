package com.orato.app.metrics

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

class LandmarkGeometryTest {

    @Test
    fun shoulderWidth_normalizesValidDistance() {
        val left = Point2D(0.3f, 0.4f)
        val right = Point2D(0.5f, 0.4f)
        val width = LandmarkGeometry.shoulderWidth(left, right)
        assertNotNull(width)
        assertEquals(0.2f, width!!, 1e-5f)
    }

    @Test
    fun shoulderWidth_rejectsTinyWidth() {
        val left = Point2D(0.40f, 0.4f)
        val right = Point2D(0.401f, 0.4f)
        assertNull(LandmarkGeometry.shoulderWidth(left, right))
    }

    @Test
    fun normalizeByShoulderWidth_dividesDistance() {
        val normalized = LandmarkGeometry.normalizeByShoulderWidth(0.05f, 0.20f)
        assertEquals(0.25f, normalized, 1e-5f)
    }

    @Test
    fun shoulderTilt_isAbsoluteVerticalDeltaOverWidth() {
        val left = Point2D(0.3f, 0.35f)
        val right = Point2D(0.5f, 0.45f)
        val width = 0.2f
        val tilt = LandmarkGeometry.shoulderTilt(left, right, width)
        assertNotNull(tilt)
        assertEquals(0.5f, tilt!!, 1e-5f)
    }

    @Test
    fun shoulderTilt_nullWhenWidthInvalid() {
        val left = Point2D(0.3f, 0.4f)
        val right = Point2D(0.5f, 0.4f)
        assertNull(LandmarkGeometry.shoulderTilt(left, right, 0.01f))
    }

    @Test
    fun trunkInclination_zeroWhenUpright() {
        val shoulders = Point2D(0.5f, 0.3f)
        val hips = Point2D(0.5f, 0.6f)
        val angle = LandmarkGeometry.trunkInclinationDegrees(shoulders, hips)
        assertNotNull(angle)
        assertEquals(0f, angle!!, 0.5f)
    }

    @Test
    fun trunkInclination_increasesWithLateralLean() {
        val shoulders = Point2D(0.65f, 0.3f)
        val hips = Point2D(0.5f, 0.6f)
        val angle = LandmarkGeometry.trunkInclinationDegrees(shoulders, hips)
        assertNotNull(angle)
        assertTrue(angle!! > 20f)
        assertTrue(angle < 45f)
    }

    @Test
    fun trunkInclination_nullForDegenerateAxis() {
        val point = Point2D(0.5f, 0.5f)
        assertNull(LandmarkGeometry.trunkInclinationDegrees(point, point))
    }

    @Test
    fun scoreLowerIsBetter_mapsThresholds() {
        assertEquals(
            100,
            LandmarkGeometry.scoreLowerIsBetter(0.04f, goodMax = 0.05f, badMin = 0.20f),
        )
        assertEquals(
            0,
            LandmarkGeometry.scoreLowerIsBetter(0.25f, goodMax = 0.05f, badMin = 0.20f),
        )
        val mid = LandmarkGeometry.scoreLowerIsBetter(0.125f, goodMax = 0.05f, badMin = 0.20f)
        assertTrue(mid in 40..60)
    }

    @Test
    fun median_oddAndEven() {
        assertEquals(2f, LandmarkGeometry.median(listOf(3f, 1f, 2f))!!, 1e-5f)
        assertEquals(2.5f, LandmarkGeometry.median(listOf(1f, 2f, 3f, 4f))!!, 1e-5f)
        assertNull(LandmarkGeometry.median(emptyList()))
    }
}

class ExponentialMovingAverageTest {

    @Test
    fun ema_firstSampleSeedsFilter() {
        val ema = ExponentialMovingAverage(alpha = 0.25f)
        assertEquals(10f, ema.update(10f), 1e-5f)
        assertEquals(10f, ema.current()!!, 1e-5f)
    }

    @Test
    fun ema_appliesDocumentedFormula() {
        val alpha = 0.25f
        val ema = ExponentialMovingAverage(alpha)
        ema.update(0f)
        val next = ema.update(100f)
        assertEquals(25f, next, 1e-4f)
    }

    @Test
    fun ema_reducesStepNoise() {
        val ema = ExponentialMovingAverage(0.25f)
        val noisy = listOf(0.10f, 0.40f, 0.12f, 0.38f, 0.11f, 0.09f)
        var last = 0f
        for (sample in noisy) {
            last = ema.update(sample)
        }
        assertTrue(last < 0.25f)
    }

    @Test
    fun ema_resetClearsState() {
        val ema = ExponentialMovingAverage(0.5f)
        ema.update(5f)
        ema.reset()
        assertNull(ema.current())
        assertEquals(7f, ema.update(7f), 1e-5f)
    }

    @Test
    fun landmarkPointSmoother_smoothsBothAxes() {
        val smoother = LandmarkPointSmoother(alpha = 0.5f)
        smoother.update(0f, 0f)
        val next = smoother.update(10f, 20f)
        assertEquals(5f, next.x, 1e-4f)
        assertEquals(10f, next.y, 1e-4f)
    }
}

class PoseValidationTest {

    @Test
    fun headOnlyLandmarks_doNotProduceValidTorso() {
        // Shoulders near top; hips missing / collapsed near shoulders (face crop).
        val frame = MetricsTestFixtures.frame(
            leftShoulder = MetricsTestFixtures.pt(0.40f, 0.25f),
            rightShoulder = MetricsTestFixtures.pt(0.55f, 0.25f),
            leftHip = MetricsTestFixtures.pt(0.42f, 0.30f), // vertical << 0.12
            rightHip = MetricsTestFixtures.pt(0.53f, 0.30f),
        )
        assertFalse(PoseValidation.evaluateTorso(frame).rawValid)
    }

    @Test
    fun hipsOutsideNormalizedFrame_invalidateTorso() {
        val frame = MetricsTestFixtures.frame(
            leftShoulder = MetricsTestFixtures.pt(0.35f, 0.35f),
            rightShoulder = MetricsTestFixtures.pt(0.55f, 0.35f),
            leftHip = MetricsTestFixtures.pt(0.38f, 1.05f), // out of frame
            rightHip = MetricsTestFixtures.pt(0.52f, 1.05f),
        )
        val torso = PoseValidation.evaluateTorso(frame)
        assertFalse(torso.leftHip.inFrame)
        assertFalse(torso.rightHip.inFrame)
        assertFalse(torso.rawValid)
    }

    @Test
    fun torsoValidity_requiresSufficientGeometricSize() {
        // Wide enough vertically but tiny shoulder width.
        val tinyShoulders = MetricsTestFixtures.frame(
            leftShoulder = MetricsTestFixtures.pt(0.48f, 0.35f),
            rightShoulder = MetricsTestFixtures.pt(0.50f, 0.35f), // width 0.02 < 0.06
            leftHip = MetricsTestFixtures.pt(0.48f, 0.60f),
            rightHip = MetricsTestFixtures.pt(0.50f, 0.60f),
        )
        assertFalse(PoseValidation.evaluateTorso(tinyShoulders).rawValid)

        val ok = MetricsTestFixtures.validTorsoFrame()
        assertTrue(PoseValidation.evaluateTorso(ok).rawValid)
    }

    @Test
    fun wristAlone_doesNotMakeHandVisible() {
        val frame = MetricsTestFixtures.frame(
            leftShoulder = MetricsTestFixtures.pt(0.30f, 0.35f),
            rightShoulder = MetricsTestFixtures.pt(0.50f, 0.35f),
            leftHip = MetricsTestFixtures.pt(0.35f, 0.70f),
            rightHip = MetricsTestFixtures.pt(0.45f, 0.70f),
            leftWrist = MetricsTestFixtures.pt(0.20f, 0.55f, visibility = 0.95f),
            // No finger landmarks → behind-back / occluded prediction.
        )
        assertFalse(PoseValidation.evaluateLeftHand(frame).rawVisible)
    }

    @Test
    fun clearlyOpenHandInFrontOfTorso_isVisible() {
        val frame = MetricsTestFixtures.validTorsoWithHandsFrame()
        val left = PoseValidation.evaluateLeftHand(frame)
        assertTrue(left.rawVisible)
        assertFalse(left.occludedByTorso)
        assertTrue(left.validFingerCount >= 2)
        assertTrue(PoseValidation.evaluateRightHand(frame).rawVisible)
    }

    @Test
    fun bothHandsBehindBack_areNotVisible() {
        val frame = MetricsTestFixtures.bothHandsBehindBackFrame()
        assertFalse(PoseValidation.evaluateLeftHand(frame).rawVisible)
        assertFalse(PoseValidation.evaluateRightHand(frame).rawVisible)
        assertTrue(PoseValidation.evaluateLeftHand(frame).occludedByTorso)
        assertTrue(PoseValidation.evaluateRightHand(frame).occludedByTorso)
    }

    @Test
    fun oneHandBehindBack_onlyThatHandIsNotVisible() {
        val frame = MetricsTestFixtures.leftHandBehindBackFrame()
        assertFalse(PoseValidation.evaluateLeftHand(frame).rawVisible)
        assertTrue(PoseValidation.evaluateRightHand(frame).rawVisible)
    }

    @Test
    fun collapsedInferredFingerGeometry_isNotVisible() {
        val frame = MetricsTestFixtures.collapsedHandFrame()
        val left = PoseValidation.evaluateLeftHand(frame)
        assertFalse(left.rawVisible)
        assertTrue(
            (left.fingerSpread ?: 0f) < BodyMetricsConfig.HAND_MIN_FINGER_SPREAD ||
                (left.boundingBoxSize ?: 0f) < BodyMetricsConfig.HAND_MIN_BBOX_SIZE ||
                left.occludedByTorso,
        )
    }

    @Test
    fun wristInsideTorsoWithWeakFingerEvidence_isNotVisible() {
        val frame = MetricsTestFixtures.wristInsideTorsoWeakFingersFrame()
        val left = PoseValidation.evaluateLeftHand(frame)
        assertFalse(left.rawVisible)
        assertTrue(left.insideTorsoRegion)
    }

    @Test
    fun uncertainEvidence_isNotVisible() {
        // Wrist barely above old threshold but below 0.80.
        val frame = MetricsTestFixtures.frame(
            leftShoulder = MetricsTestFixtures.pt(0.30f, 0.35f),
            rightShoulder = MetricsTestFixtures.pt(0.50f, 0.35f),
            leftHip = MetricsTestFixtures.pt(0.35f, 0.70f),
            rightHip = MetricsTestFixtures.pt(0.45f, 0.70f),
            leftWrist = MetricsTestFixtures.pt(0.18f, 0.50f, visibility = 0.72f),
            leftThumb = MetricsTestFixtures.pt(0.14f, 0.48f, visibility = 0.72f),
            leftIndex = MetricsTestFixtures.pt(0.16f, 0.55f, visibility = 0.72f),
            leftPinky = MetricsTestFixtures.pt(0.20f, 0.52f, visibility = 0.72f),
        )
        assertFalse(PoseValidation.evaluateLeftHand(frame).rawVisible)
    }

    @Test
    fun occludedLowVisibilityFingers_invalidateHandVisibility() {
        val frame = MetricsTestFixtures.frame(
            leftShoulder = MetricsTestFixtures.pt(0.30f, 0.35f),
            rightShoulder = MetricsTestFixtures.pt(0.50f, 0.35f),
            leftHip = MetricsTestFixtures.pt(0.35f, 0.70f),
            rightHip = MetricsTestFixtures.pt(0.45f, 0.70f),
            leftWrist = MetricsTestFixtures.pt(0.20f, 0.55f, visibility = 0.95f),
            leftThumb = MetricsTestFixtures.pt(0.16f, 0.52f, visibility = 0.2f),
            leftIndex = MetricsTestFixtures.pt(0.18f, 0.60f, visibility = 0.2f),
            leftPinky = MetricsTestFixtures.pt(0.22f, 0.56f, visibility = 0.2f),
        )
        assertFalse(PoseValidation.evaluateLeftHand(frame).rawVisible)
    }

    @Test
    fun outOfFrameWristOrFingers_invalidateHandVisibility() {
        val outWrist = MetricsTestFixtures.frame(
            leftShoulder = MetricsTestFixtures.pt(0.30f, 0.35f),
            rightShoulder = MetricsTestFixtures.pt(0.50f, 0.35f),
            leftHip = MetricsTestFixtures.pt(0.35f, 0.70f),
            rightHip = MetricsTestFixtures.pt(0.45f, 0.70f),
            leftWrist = MetricsTestFixtures.pt(-0.05f, 0.55f, visibility = 0.95f),
            leftThumb = MetricsTestFixtures.pt(0.16f, 0.52f),
            leftIndex = MetricsTestFixtures.pt(0.18f, 0.60f),
            leftPinky = MetricsTestFixtures.pt(0.22f, 0.56f),
        )
        assertFalse(PoseValidation.evaluateLeftHand(outWrist).rawVisible)

        val outFingers = MetricsTestFixtures.frame(
            leftShoulder = MetricsTestFixtures.pt(0.30f, 0.35f),
            rightShoulder = MetricsTestFixtures.pt(0.50f, 0.35f),
            leftHip = MetricsTestFixtures.pt(0.35f, 0.70f),
            rightHip = MetricsTestFixtures.pt(0.45f, 0.70f),
            leftWrist = MetricsTestFixtures.pt(0.20f, 0.55f, visibility = 0.95f),
            leftThumb = MetricsTestFixtures.pt(1.05f, 0.52f),
            leftIndex = MetricsTestFixtures.pt(1.08f, 0.60f),
            leftPinky = MetricsTestFixtures.pt(1.10f, 0.56f),
        )
        assertFalse(PoseValidation.evaluateLeftHand(outFingers).rawVisible)
    }

    @Test
    fun landmarkUsability_skipsMissingPresence_andRejectsLowPresence() {
        val okNullPresence = MetricsTestFixtures.pt(0.5f, 0.5f, presence = null)
        assertTrue(
            LandmarkUsabilityEvaluator.evaluate(
                okNullPresence,
                minVisibility = 0.65f,
            ).usable,
        )

        val lowPresence = MetricsTestFixtures.pt(0.5f, 0.5f, presence = 0.2f)
        assertFalse(
            LandmarkUsabilityEvaluator.evaluate(
                lowPresence,
                minVisibility = 0.65f,
            ).usable,
        )
    }
}

class BodyMetricsEngineTest {

    @Test
    fun rejectsInvalidLandmarks_doesNotTreatAsZero() {
        val engine = BodyMetricsEngine()
        engine.reset()
        val live = engine.processFrame(MetricsTestFixtures.emptyFrame())
        assertFalse(live.validDetection)
        assertNull(live.shoulderTilt)
        assertEquals(0, engine.debugCounters().validTorsoFrames)
    }

    @Test
    fun rejectsLowVisibilityLandmarks() {
        val engine = BodyMetricsEngine()
        engine.reset()
        engine.processFrame(
            MetricsTestFixtures.validTorsoFrame(visibility = 0.2f),
        )
        assertEquals(0, engine.debugCounters().validTorsoFrames)
    }

    @Test
    fun temporalHysteresis_preventsOneFrameFalsePositives() {
        val engine = BodyMetricsEngine()
        engine.reset()

        val live1 = engine.processFrame(MetricsTestFixtures.validTorsoFrame())
        assertFalse(live1.validDetection)
        assertFalse(live1.torsoValid)
        // Raw torso still counts for presence aggregation.
        assertEquals(1, engine.debugCounters().validTorsoFrames)

        repeat(4) { engine.processFrame(MetricsTestFixtures.validTorsoFrame()) }
        // 5 valid among latest 7 → latch ON
        assertTrue(engine.liveMetrics().validDetection)

        repeat(3) { engine.processFrame(MetricsTestFixtures.emptyFrame()) }
        // 3 invalid among latest 4 → latch OFF
        assertFalse(engine.liveMetrics().validDetection)
    }

    @Test
    fun handGate_requiresFourConsecutiveVisibleResults() {
        val engine = BodyMetricsEngine()
        engine.reset()
        val withHands = MetricsTestFixtures.validTorsoWithHandsFrame()

        repeat(3) {
            engine.processFrame(withHands)
            assertFalse(engine.liveMetrics().leftHandVisible)
        }
        assertEquals(0, engine.debugCounters().oneHandVisibleFrames)

        engine.processFrame(withHands)
        assertTrue(engine.liveMetrics().leftHandVisible)
        assertTrue(engine.liveMetrics().rightHandVisible)
        assertEquals(1, engine.debugCounters().oneHandVisibleFrames)
        assertEquals(1, engine.debugCounters().twoHandsVisibleFrames)
    }

    @Test
    fun handVisible_clearsImmediatelyOnOcclusion() {
        val engine = BodyMetricsEngine()
        engine.reset()
        val open = MetricsTestFixtures.validTorsoWithHandsFrame()
        repeat(4) { engine.processFrame(open) }
        assertTrue(engine.liveMetrics().leftHandVisible)

        engine.processFrame(MetricsTestFixtures.bothHandsBehindBackFrame())
        assertFalse(engine.liveMetrics().leftHandVisible)
        assertFalse(engine.liveMetrics().rightHandVisible)
        assertTrue(engine.liveMetrics().leftHand.occludedByTorso)
    }

    @Test
    fun wristOnly_neverAccumulatesHandVisibility() {
        val engine = BodyMetricsEngine()
        engine.reset()
        val wristsOnly = MetricsTestFixtures.frame(
            leftShoulder = MetricsTestFixtures.pt(0.30f, 0.35f),
            rightShoulder = MetricsTestFixtures.pt(0.50f, 0.35f),
            leftHip = MetricsTestFixtures.pt(0.35f, 0.70f),
            rightHip = MetricsTestFixtures.pt(0.45f, 0.70f),
            leftWrist = MetricsTestFixtures.pt(0.20f, 0.55f, visibility = 0.95f),
            rightWrist = MetricsTestFixtures.pt(0.60f, 0.55f, visibility = 0.95f),
        )
        repeat(10) { engine.processFrame(wristsOnly) }
        assertEquals(0, engine.debugCounters().oneHandVisibleFrames)
        assertFalse(engine.liveMetrics().leftHandVisible)
    }

    @Test
    fun rejectsImplausibleJumps_fromStabilityAndGesture() {
        val engine = BodyMetricsEngine()
        engine.reset()
        val base = MetricsTestFixtures.validTorsoWithHandsFrame()
        val jumped = MetricsTestFixtures.validTorsoWithHandsFrame(offsetX = 0.30f)
        repeat(4) { engine.processFrame(base) }
        val swayBefore = engine.debugCounters().swaySampleCount
        engine.processFrame(jumped)
        assertEquals(swayBefore, engine.debugCounters().swaySampleCount)
    }

    @Test
    fun acceptsSmallMotion_forStabilityAndGesture() {
        val engine = BodyMetricsEngine()
        engine.reset()
        // Hand gate needs 4 consecutive visible frames; gesture needs a delta after.
        repeat(6) { i ->
            val dx = i * 0.002f
            engine.processFrame(MetricsTestFixtures.validTorsoWithHandsFrame(offsetX = dx))
        }
        val counters = engine.debugCounters()
        assertTrue(counters.swaySampleCount >= 1)
        assertTrue(counters.gestureSampleCount >= 1)
    }

    @Test
    fun insufficientData_whenBelowMinSamples() {
        val engine = BodyMetricsEngine()
        engine.reset()
        repeat(5) { engine.processFrame(MetricsTestFixtures.validTorsoFrame()) }
        val report = engine.buildReport()
        assertTrue(report.hasInsufficientData)
    }

    @Test
    fun sessionAggregation_producesScoresWithEnoughSamples() {
        val engine = BodyMetricsEngine()
        engine.reset()
        val n = BodyMetricsConfig.MIN_SAMPLES_FOR_SCORE + 5
        repeat(n) { i ->
            val dx = (i % 3) * 0.001f
            engine.processFrame(MetricsTestFixtures.validTorsoWithHandsFrame(offsetX = dx))
        }
        engine.stopAccumulation()
        val report = engine.buildReport()

        assertFalse(report.cameraPresence.insufficientData)
        assertTrue(report.cameraPresence.percent!! > 99f)
        assertFalse(report.shoulderBalance.insufficientData)
        assertFalse(report.trunkInclination.insufficientData)
        assertTrue(abs(report.trunkInclination.rawValue!!) < 5f)
        assertFalse(report.trunkStability.insufficientData)
        assertFalse(report.oneHandVisibility.insufficientData)
        assertFalse(report.twoHandVisibility.insufficientData)
        assertTrue(report.twoHandVisibility.percent!! > 90f)
        assertFalse(report.gestureActivity.insufficientData)
    }

    @Test
    fun stopAccumulation_ignoresLaterFrames() {
        val engine = BodyMetricsEngine()
        engine.reset()
        repeat(10) { engine.processFrame(MetricsTestFixtures.validTorsoFrame()) }
        engine.stopAccumulation()
        val before = engine.debugCounters().validTorsoFrames
        repeat(10) { engine.processFrame(MetricsTestFixtures.validTorsoFrame()) }
        assertEquals(before, engine.debugCounters().validTorsoFrames)
    }

    @Test
    fun reset_clearsAccumulatorsAndTemporalStateBetweenSessions() {
        val engine = BodyMetricsEngine()
        engine.reset()
        repeat(10) { engine.processFrame(MetricsTestFixtures.validTorsoWithHandsFrame()) }
        assertTrue(engine.debugCounters().torsoLatched)
        assertTrue(engine.debugCounters().leftHandGated)

        engine.reset()
        val counters = engine.debugCounters()
        assertEquals(0, counters.totalAnalyzedFrames)
        assertEquals(0, counters.validTorsoFrames)
        assertEquals(0, counters.oneHandVisibleFrames)
        assertFalse(counters.torsoLatched)
        assertFalse(counters.leftHandGated)
        assertFalse(counters.rightHandGated)

        val live = engine.processFrame(MetricsTestFixtures.validTorsoFrame())
        assertFalse(live.validDetection)
        assertEquals(1, engine.debugCounters().validTorsoFrames)
    }

    @Test
    fun release_stopsProcessingAndClearsState() {
        val engine = BodyMetricsEngine()
        engine.reset()
        repeat(5) { engine.processFrame(MetricsTestFixtures.validTorsoFrame()) }
        engine.release()
        assertFalse(engine.processFrame(MetricsTestFixtures.validTorsoFrame()).validDetection)
        assertEquals(0, engine.debugCounters().validTorsoFrames)
        assertTrue(engine.debugCounters().isReleased)
    }
}

object MetricsTestFixtures {

    fun emptyFrame(): UpperBodyPoseFrame =
        UpperBodyPoseFrame(emptyMap(), imageWidth = 480, imageHeight = 640)

    fun pt(
        x: Float,
        y: Float,
        visibility: Float = 0.9f,
        presence: Float? = 0.9f,
        id: PoseLandmarkId = PoseLandmarkId.LEFT_SHOULDER,
    ): NormalizedLandmarkPoint = NormalizedLandmarkPoint(
        id = id,
        x = x,
        y = y,
        visibility = visibility,
        presence = presence,
    )

    fun validTorsoFrame(
        visibility: Float = 0.9f,
        offsetX: Float = 0f,
    ): UpperBodyPoseFrame = frame(
        leftShoulder = pt(0.30f + offsetX, 0.35f, visibility),
        rightShoulder = pt(0.50f + offsetX, 0.35f, visibility),
        leftHip = pt(0.35f + offsetX, 0.70f, visibility),
        rightHip = pt(0.45f + offsetX, 0.70f, visibility),
    )

    fun validTorsoWithHandsFrame(offsetX: Float = 0f): UpperBodyPoseFrame = frame(
        leftShoulder = pt(0.30f + offsetX, 0.35f),
        rightShoulder = pt(0.50f + offsetX, 0.35f),
        leftHip = pt(0.35f + offsetX, 0.70f),
        rightHip = pt(0.45f + offsetX, 0.70f),
        // Hands clearly beside the torso with open finger geometry.
        leftWrist = pt(0.18f + offsetX, 0.50f, visibility = 0.92f),
        rightWrist = pt(0.62f + offsetX, 0.50f, visibility = 0.92f),
        leftThumb = pt(0.12f + offsetX, 0.46f, visibility = 0.88f),
        leftIndex = pt(0.14f + offsetX, 0.58f, visibility = 0.88f),
        leftPinky = pt(0.22f + offsetX, 0.54f, visibility = 0.88f),
        rightThumb = pt(0.68f + offsetX, 0.46f, visibility = 0.88f),
        rightIndex = pt(0.66f + offsetX, 0.58f, visibility = 0.88f),
        rightPinky = pt(0.58f + offsetX, 0.54f, visibility = 0.88f),
    )

    /** Both wrists + collapsed fingers inferred inside the torso silhouette. */
    fun bothHandsBehindBackFrame(): UpperBodyPoseFrame = frame(
        leftShoulder = pt(0.30f, 0.35f),
        rightShoulder = pt(0.50f, 0.35f),
        leftHip = pt(0.35f, 0.70f),
        rightHip = pt(0.45f, 0.70f),
        // High enough visibility to look “detected”, but collapsed inside torso.
        leftWrist = pt(0.38f, 0.52f, visibility = 0.85f),
        rightWrist = pt(0.42f, 0.52f, visibility = 0.85f),
        leftThumb = pt(0.375f, 0.515f, visibility = 0.80f),
        leftIndex = pt(0.380f, 0.520f, visibility = 0.80f),
        leftPinky = pt(0.385f, 0.525f, visibility = 0.80f),
        rightThumb = pt(0.415f, 0.515f, visibility = 0.80f),
        rightIndex = pt(0.420f, 0.520f, visibility = 0.80f),
        rightPinky = pt(0.425f, 0.525f, visibility = 0.80f),
    )

    fun leftHandBehindBackFrame(): UpperBodyPoseFrame = frame(
        leftShoulder = pt(0.30f, 0.35f),
        rightShoulder = pt(0.50f, 0.35f),
        leftHip = pt(0.35f, 0.70f),
        rightHip = pt(0.45f, 0.70f),
        leftWrist = pt(0.40f, 0.52f, visibility = 0.85f),
        leftThumb = pt(0.395f, 0.515f, visibility = 0.80f),
        leftIndex = pt(0.400f, 0.520f, visibility = 0.80f),
        leftPinky = pt(0.405f, 0.525f, visibility = 0.80f),
        rightWrist = pt(0.62f, 0.50f, visibility = 0.92f),
        rightThumb = pt(0.68f, 0.46f, visibility = 0.88f),
        rightIndex = pt(0.66f, 0.58f, visibility = 0.88f),
        rightPinky = pt(0.58f, 0.54f, visibility = 0.88f),
    )

    fun collapsedHandFrame(): UpperBodyPoseFrame = frame(
        leftShoulder = pt(0.30f, 0.35f),
        rightShoulder = pt(0.50f, 0.35f),
        leftHip = pt(0.35f, 0.70f),
        rightHip = pt(0.45f, 0.70f),
        leftWrist = pt(0.18f, 0.50f, visibility = 0.90f),
        leftThumb = pt(0.181f, 0.501f, visibility = 0.85f),
        leftIndex = pt(0.182f, 0.502f, visibility = 0.85f),
        leftPinky = pt(0.183f, 0.503f, visibility = 0.85f),
    )

    fun wristInsideTorsoWeakFingersFrame(): UpperBodyPoseFrame = frame(
        leftShoulder = pt(0.30f, 0.35f),
        rightShoulder = pt(0.50f, 0.35f),
        leftHip = pt(0.35f, 0.70f),
        rightHip = pt(0.45f, 0.70f),
        leftWrist = pt(0.40f, 0.50f, visibility = 0.82f),
        leftThumb = pt(0.39f, 0.49f, visibility = 0.60f),
        leftIndex = pt(0.41f, 0.51f, visibility = 0.60f),
        leftPinky = pt(0.40f, 0.52f, visibility = 0.60f),
    )

    fun frame(
        leftShoulder: NormalizedLandmarkPoint? = null,
        rightShoulder: NormalizedLandmarkPoint? = null,
        leftHip: NormalizedLandmarkPoint? = null,
        rightHip: NormalizedLandmarkPoint? = null,
        leftElbow: NormalizedLandmarkPoint? = null,
        rightElbow: NormalizedLandmarkPoint? = null,
        leftWrist: NormalizedLandmarkPoint? = null,
        rightWrist: NormalizedLandmarkPoint? = null,
        leftThumb: NormalizedLandmarkPoint? = null,
        leftIndex: NormalizedLandmarkPoint? = null,
        leftPinky: NormalizedLandmarkPoint? = null,
        rightThumb: NormalizedLandmarkPoint? = null,
        rightIndex: NormalizedLandmarkPoint? = null,
        rightPinky: NormalizedLandmarkPoint? = null,
    ): UpperBodyPoseFrame {
        val landmarks = mutableMapOf<PoseLandmarkId, NormalizedLandmarkPoint>()
        fun put(id: PoseLandmarkId, point: NormalizedLandmarkPoint?) {
            if (point != null) landmarks[id] = point.copy(id = id)
        }
        put(PoseLandmarkId.LEFT_SHOULDER, leftShoulder)
        put(PoseLandmarkId.RIGHT_SHOULDER, rightShoulder)
        put(PoseLandmarkId.LEFT_HIP, leftHip)
        put(PoseLandmarkId.RIGHT_HIP, rightHip)
        put(PoseLandmarkId.LEFT_ELBOW, leftElbow)
        put(PoseLandmarkId.RIGHT_ELBOW, rightElbow)
        put(PoseLandmarkId.LEFT_WRIST, leftWrist)
        put(PoseLandmarkId.RIGHT_WRIST, rightWrist)
        put(PoseLandmarkId.LEFT_THUMB, leftThumb)
        put(PoseLandmarkId.LEFT_INDEX, leftIndex)
        put(PoseLandmarkId.LEFT_PINKY, leftPinky)
        put(PoseLandmarkId.RIGHT_THUMB, rightThumb)
        put(PoseLandmarkId.RIGHT_INDEX, rightIndex)
        put(PoseLandmarkId.RIGHT_PINKY, rightPinky)
        return UpperBodyPoseFrame(landmarks, imageWidth = 480, imageHeight = 640)
    }
}
