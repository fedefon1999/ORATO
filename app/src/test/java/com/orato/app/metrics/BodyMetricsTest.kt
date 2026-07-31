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
        // |0.35 - 0.45| / 0.2 = 0.5
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
        // smoothed = 0.25 * 100 + 0.75 * 0 = 25
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
        // Final value should be pulled toward recent low samples, not 0.40 spike.
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

class BodyMetricsEngineTest {

    @Test
    fun rejectsInvalidLandmarks_doesNotTreatAsZero() {
        val engine = BodyMetricsEngine()
        engine.reset()

        // Empty frame: no torso → invalid detection, no tilt invented as 0.
        val live = engine.processFrame(emptyFrame())
        assertFalse(live.validDetection)
        assertNull(live.shoulderTilt)
        assertNull(live.trunkAngleDegrees)

        val counters = engine.debugCounters()
        assertEquals(1, counters.totalAnalyzedFrames)
        assertEquals(0, counters.validTorsoFrames)
        assertEquals(0, counters.shoulderTiltSampleCount)
    }

    @Test
    fun rejectsLowVisibilityLandmarks() {
        val engine = BodyMetricsEngine()
        engine.reset()
        val live = engine.processFrame(
            torsoFrame(
                visibility = 0.2f,
                leftShoulder = 0.3f to 0.4f,
                rightShoulder = 0.5f to 0.4f,
                leftHip = 0.35f to 0.7f,
                rightHip = 0.45f to 0.7f,
            ),
        )
        assertFalse(live.validDetection)
        assertEquals(0, engine.debugCounters().validTorsoFrames)
    }

    @Test
    fun processesValidTorsoAndHands() {
        val engine = BodyMetricsEngine()
        engine.reset()
        val live = engine.processFrame(
            torsoFrame(
                leftShoulder = 0.30f to 0.35f,
                rightShoulder = 0.50f to 0.40f,
                leftHip = 0.35f to 0.70f,
                rightHip = 0.45f to 0.70f,
                leftWrist = 0.25f to 0.55f,
                rightWrist = 0.55f to 0.55f,
            ),
        )
        assertTrue(live.validDetection)
        assertNotNull(live.shoulderTilt)
        assertNotNull(live.trunkAngleDegrees)
        assertTrue(live.oneHandVisible)
        assertTrue(live.twoHandsVisible)
        assertEquals(1, engine.debugCounters().validTorsoFrames)
    }

    @Test
    fun rejectsImplausibleJumps_fromStabilityAndGesture() {
        val engine = BodyMetricsEngine()
        engine.reset()

        // Baseline frame
        engine.processFrame(
            torsoFrame(
                leftShoulder = 0.30f to 0.40f,
                rightShoulder = 0.50f to 0.40f,
                leftHip = 0.35f to 0.70f,
                rightHip = 0.45f to 0.70f,
                leftWrist = 0.25f to 0.55f,
            ),
        )
        // Huge teleport (many shoulder widths)
        engine.processFrame(
            torsoFrame(
                leftShoulder = 0.80f to 0.40f,
                rightShoulder = 1.00f to 0.40f,
                leftHip = 0.85f to 0.70f,
                rightHip = 0.95f to 0.70f,
                leftWrist = 0.90f to 0.55f,
            ),
        )

        val counters = engine.debugCounters()
        assertEquals(2, counters.validTorsoFrames)
        // First frame has no previous → no sway/gesture sample; jump frame excluded.
        assertEquals(0, counters.swaySampleCount)
        assertEquals(0, counters.gestureSampleCount)
    }

    @Test
    fun acceptsSmallMotion_forStabilityAndGesture() {
        val engine = BodyMetricsEngine()
        engine.reset()

        engine.processFrame(
            torsoFrame(
                leftShoulder = 0.30f to 0.40f,
                rightShoulder = 0.50f to 0.40f,
                leftHip = 0.35f to 0.70f,
                rightHip = 0.45f to 0.70f,
                leftWrist = 0.25f to 0.55f,
            ),
        )
        engine.processFrame(
            torsoFrame(
                leftShoulder = 0.302f to 0.40f,
                rightShoulder = 0.502f to 0.40f,
                leftHip = 0.352f to 0.70f,
                rightHip = 0.452f to 0.70f,
                leftWrist = 0.255f to 0.55f,
            ),
        )

        val counters = engine.debugCounters()
        assertEquals(1, counters.swaySampleCount)
        assertEquals(1, counters.gestureSampleCount)
    }

    @Test
    fun insufficientData_whenBelowMinSamples() {
        val engine = BodyMetricsEngine()
        engine.reset()
        repeat(5) {
            engine.processFrame(uprightFrame(offsetX = 0f))
        }
        val report = engine.buildReport()
        assertTrue(report.hasInsufficientData)
        assertTrue(report.cameraPresence.insufficientData)
        assertTrue(report.shoulderBalance.insufficientData)
    }

    @Test
    fun sessionAggregation_producesScoresWithEnoughSamples() {
        val engine = BodyMetricsEngine()
        engine.reset()
        val n = BodyMetricsConfig.MIN_SAMPLES_FOR_SCORE + 5
        repeat(n) { i ->
            // Tiny lateral drift to produce stability samples after frame 0.
            val dx = (i % 3) * 0.001f
            engine.processFrame(uprightFrame(offsetX = dx, withWrists = true, wristDx = dx))
        }
        engine.stopAccumulation()
        val report = engine.buildReport()

        assertFalse(report.cameraPresence.insufficientData)
        assertNotNull(report.cameraPresence.percent)
        assertTrue(report.cameraPresence.percent!! > 99f)

        assertFalse(report.shoulderBalance.insufficientData)
        assertNotNull(report.shoulderBalance.score)
        assertTrue(report.shoulderBalance.score!! in 0..100)

        assertFalse(report.trunkInclination.insufficientData)
        assertNotNull(report.trunkInclination.rawValue)
        assertTrue(abs(report.trunkInclination.rawValue!!) < 5f)

        assertFalse(report.trunkStability.insufficientData)
        assertNotNull(report.trunkStability.score)

        assertFalse(report.oneHandVisibility.insufficientData)
        assertFalse(report.twoHandVisibility.insufficientData)
        assertTrue(report.twoHandVisibility.percent!! > 99f)

        assertFalse(report.gestureActivity.insufficientData)
        assertNotNull(report.gestureActivity.classification)
    }

    @Test
    fun stopAccumulation_ignoresLaterFrames() {
        val engine = BodyMetricsEngine()
        engine.reset()
        repeat(10) { engine.processFrame(uprightFrame()) }
        engine.stopAccumulation()
        val before = engine.debugCounters().validTorsoFrames
        repeat(10) { engine.processFrame(uprightFrame()) }
        assertEquals(before, engine.debugCounters().validTorsoFrames)
        assertFalse(engine.debugCounters().isAccumulating)
    }

    @Test
    fun reset_clearsAccumulatorsBetweenSessions() {
        val engine = BodyMetricsEngine()
        engine.reset()
        repeat(20) { engine.processFrame(uprightFrame(withWrists = true)) }
        assertTrue(engine.debugCounters().validTorsoFrames > 0)

        engine.reset()
        val counters = engine.debugCounters()
        assertEquals(0, counters.totalAnalyzedFrames)
        assertEquals(0, counters.validTorsoFrames)
        assertEquals(0, counters.shoulderTiltSampleCount)
        assertEquals(0, counters.swaySampleCount)
        assertEquals(0, counters.gestureSampleCount)
        assertTrue(counters.isAccumulating)

        // New session samples only.
        repeat(3) { engine.processFrame(uprightFrame()) }
        assertEquals(3, engine.debugCounters().validTorsoFrames)
    }

    @Test
    fun release_stopsProcessingAndClearsState() {
        val engine = BodyMetricsEngine()
        engine.reset()
        repeat(5) { engine.processFrame(uprightFrame()) }
        engine.release()

        val live = engine.processFrame(uprightFrame())
        assertFalse(live.validDetection)
        val counters = engine.debugCounters()
        assertTrue(counters.isReleased)
        assertEquals(0, counters.validTorsoFrames)
    }

    private fun emptyFrame(): UpperBodyPoseFrame =
        UpperBodyPoseFrame(landmarks = emptyMap(), imageWidth = 480, imageHeight = 640)

    private fun uprightFrame(
        offsetX: Float = 0f,
        withWrists: Boolean = false,
        wristDx: Float = 0f,
    ): UpperBodyPoseFrame {
        return torsoFrame(
            leftShoulder = (0.30f + offsetX) to 0.40f,
            rightShoulder = (0.50f + offsetX) to 0.40f,
            leftHip = (0.35f + offsetX) to 0.70f,
            rightHip = (0.45f + offsetX) to 0.70f,
            leftWrist = if (withWrists) (0.25f + wristDx) to 0.55f else null,
            rightWrist = if (withWrists) (0.55f + wristDx) to 0.55f else null,
        )
    }

    private fun torsoFrame(
        visibility: Float = 0.9f,
        leftShoulder: Pair<Float, Float>,
        rightShoulder: Pair<Float, Float>,
        leftHip: Pair<Float, Float>,
        rightHip: Pair<Float, Float>,
        leftWrist: Pair<Float, Float>? = null,
        rightWrist: Pair<Float, Float>? = null,
        leftElbow: Pair<Float, Float>? = null,
        rightElbow: Pair<Float, Float>? = null,
    ): UpperBodyPoseFrame {
        val landmarks = mutableMapOf<PoseLandmarkId, NormalizedLandmarkPoint>()
        fun put(id: PoseLandmarkId, xy: Pair<Float, Float>) {
            landmarks[id] = NormalizedLandmarkPoint(
                id = id,
                x = xy.first,
                y = xy.second,
                visibility = visibility,
            )
        }
        put(PoseLandmarkId.LEFT_SHOULDER, leftShoulder)
        put(PoseLandmarkId.RIGHT_SHOULDER, rightShoulder)
        put(PoseLandmarkId.LEFT_HIP, leftHip)
        put(PoseLandmarkId.RIGHT_HIP, rightHip)
        leftElbow?.let { put(PoseLandmarkId.LEFT_ELBOW, it) }
        rightElbow?.let { put(PoseLandmarkId.RIGHT_ELBOW, it) }
        leftWrist?.let { put(PoseLandmarkId.LEFT_WRIST, it) }
        rightWrist?.let { put(PoseLandmarkId.RIGHT_WRIST, it) }
        return UpperBodyPoseFrame(
            landmarks = landmarks,
            imageWidth = 480,
            imageHeight = 640,
        )
    }
}
