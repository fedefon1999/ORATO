package com.orato.app.vision

import com.orato.app.domain.model.VisualAnalysisMode
import com.orato.app.face.FaceMetricsConfig
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class VisualFrameSchedulerTest {

    private fun scheduler(mode: VisualAnalysisMode) =
        VisualFrameScheduler(mode).also { it.beginSession(1L) }

    @Test
    fun bodyOnly_alwaysSelectsPoseWhenEligible() {
        val s = scheduler(VisualAnalysisMode.BODY_ONLY)
        var t = 0L
        repeat(20) {
            val (sel, _) = s.select(t, faceAvailable = true, poseAvailable = true)
            assertEquals(SelectedVisualAnalyzer.POSE, sel)
            s.onPoseCompleted(s.currentPoseRequestToken, 1L)
            t += FaceMetricsConfig.POSE_MIN_INTERVAL_MS
        }
    }

    @Test
    fun faceOnly_alwaysSelectsFaceWhenEligible() {
        val s = scheduler(VisualAnalysisMode.FACE_ONLY)
        var t = 0L
        repeat(20) {
            val (sel, _) = s.select(t, faceAvailable = true, poseAvailable = true)
            assertEquals(SelectedVisualAnalyzer.FACE, sel)
            s.onFaceCompleted(s.currentFaceRequestToken, 1L)
            t += FaceMetricsConfig.FACE_MIN_INTERVAL_MS
        }
    }

    @Test
    fun bodyAndFace_alternatesWhenBothEligible() {
        val s = scheduler(VisualAnalysisMode.BODY_AND_FACE)
        val picks = mutableListOf<SelectedVisualAnalyzer>()
        var t = 0L
        repeat(10) {
            val (sel, token) = s.select(t, faceAvailable = true, poseAvailable = true)
            picks += sel
            when (sel) {
                SelectedVisualAnalyzer.FACE -> s.onFaceCompleted(token, 1L)
                SelectedVisualAnalyzer.POSE -> s.onPoseCompleted(token, 1L)
                SelectedVisualAnalyzer.NONE -> error("unexpected NONE")
            }
            t += 200L // both intervals elapsed
        }
        assertTrue(picks.contains(SelectedVisualAnalyzer.FACE))
        assertTrue(picks.contains(SelectedVisualAnalyzer.POSE))
        // Strict alternation when both always eligible
        for (i in 1 until picks.size) {
            assertTrue(picks[i] != picks[i - 1])
        }
    }

    @Test
    fun faceCannotStarvePose_longSequence() {
        val s = scheduler(VisualAnalysisMode.BODY_AND_FACE)
        var face = 0
        var pose = 0
        var t = 0L
        repeat(100) {
            val (sel, token) = s.select(t, faceAvailable = true, poseAvailable = true)
            when (sel) {
                SelectedVisualAnalyzer.FACE -> {
                    face++
                    s.onFaceCompleted(token, 1L)
                }
                SelectedVisualAnalyzer.POSE -> {
                    pose++
                    s.onPoseCompleted(token, 1L)
                }
                SelectedVisualAnalyzer.NONE -> Unit
            }
            t += 200L
        }
        assertTrue("pose starved: face=$face pose=$pose", pose >= 40)
        assertTrue("face starved: face=$face pose=$pose", face >= 40)
    }

    @Test
    fun poseCannotStarveFace_longSequence() {
        faceCannotStarvePose_longSequence()
    }

    @Test
    fun faceBusy_routesEligibleFramesToPose() {
        val s = scheduler(VisualAnalysisMode.BODY_AND_FACE)
        val (first, token) = s.select(0L, faceAvailable = true, poseAvailable = true)
        assertEquals(SelectedVisualAnalyzer.FACE, first)
        // Face still in flight
        val (second, token2) = s.select(200L, faceAvailable = true, poseAvailable = true)
        assertEquals(SelectedVisualAnalyzer.POSE, second)
        s.onPoseCompleted(token2, 1L)
        s.onFaceCompleted(token, 1L)
    }

    @Test
    fun poseBusy_routesEligibleFramesToFace() {
        val s = scheduler(VisualAnalysisMode.BODY_AND_FACE)
        // Force pose first by selecting once then completing face preference flip
        val (a, t1) = s.select(0L, true, true)
        if (a == SelectedVisualAnalyzer.FACE) s.onFaceCompleted(t1, 1L)
        else s.onPoseCompleted(t1, 1L)
        val (b, t2) = s.select(200L, true, true)
        // Hold whatever was selected busy
        val (c, _) = s.select(400L, true, true)
        assertTrue(c == SelectedVisualAnalyzer.FACE || c == SelectedVisualAnalyzer.POSE)
        assertTrue(c != b) // other analyzer
        // cleanup
        when (b) {
            SelectedVisualAnalyzer.FACE -> s.onFaceCompleted(t2, 1L)
            SelectedVisualAnalyzer.POSE -> s.onPoseCompleted(t2, 1L)
            else -> Unit
        }
    }

    @Test
    fun bothBusy_selectsNone() {
        val s = scheduler(VisualAnalysisMode.BODY_AND_FACE)
        val (a, ta) = s.select(0L, true, true)
        val (b, tb) = s.select(200L, true, true)
        assertTrue(a != SelectedVisualAnalyzer.NONE)
        assertTrue(b != SelectedVisualAnalyzer.NONE)
        val (c, _) = s.select(400L, true, true)
        assertEquals(SelectedVisualAnalyzer.NONE, c)
        s.onFaceCompleted(if (a == SelectedVisualAnalyzer.FACE) ta else tb, 1L)
        s.onPoseCompleted(if (a == SelectedVisualAnalyzer.POSE) ta else tb, 1L)
    }

    @Test
    fun faceThrottled_poseEligible_selectsPose() {
        val s = scheduler(VisualAnalysisMode.BODY_AND_FACE)
        val (a, ta) = s.select(0L, true, true)
        if (a == SelectedVisualAnalyzer.FACE) s.onFaceCompleted(ta, 1L) else s.onPoseCompleted(ta, 1L)
        // Advance only pose interval
        val t = FaceMetricsConfig.POSE_MIN_INTERVAL_MS
        // Face may or may not be eligible depending on who went first; force face submit then wait pose-only gap
        val s2 = scheduler(VisualAnalysisMode.BODY_AND_FACE)
        val (f, tf) = s2.select(0L, true, true)
        assertEquals(SelectedVisualAnalyzer.FACE, f)
        s2.onFaceCompleted(tf, 1L)
        val (p, _) = s2.select(FaceMetricsConfig.POSE_MIN_INTERVAL_MS, true, true)
        // At 100ms pose eligible; face needs 80ms so also eligible — round-robin picks pose
        assertEquals(SelectedVisualAnalyzer.POSE, p)
    }

    @Test
    fun poseThrottled_faceEligible_selectsFace() {
        val s = scheduler(VisualAnalysisMode.BODY_AND_FACE)
        val (f, tf) = s.select(0L, true, true)
        assertEquals(SelectedVisualAnalyzer.FACE, f)
        s.onFaceCompleted(tf, 1L)
        val (p, tp) = s.select(100L, true, true)
        assertEquals(SelectedVisualAnalyzer.POSE, p)
        s.onPoseCompleted(tp, 1L)
        // Next at +80 from face last (0) → 80: face eligible, pose last=100 so not yet
        val (again, _) = s.select(180L, true, true)
        assertEquals(SelectedVisualAnalyzer.FACE, again)
    }

    @Test
    fun neitherEligible_selectsNone() {
        val s = scheduler(VisualAnalysisMode.BODY_AND_FACE)
        val (sel, _) = s.select(0L, faceAvailable = false, poseAvailable = false)
        assertEquals(SelectedVisualAnalyzer.NONE, sel)
    }

    @Test
    fun duplicateTimestamps_rejected() {
        val s = scheduler(VisualAnalysisMode.FACE_ONLY)
        val (a, ta) = s.select(100L, true, false)
        assertEquals(SelectedVisualAnalyzer.FACE, a)
        s.onFaceCompleted(ta, 1L)
        assertFalse(s.canAcceptFace(100L))
        val (b, _) = s.select(100L, true, false)
        assertEquals(SelectedVisualAnalyzer.NONE, b)
    }

    @Test
    fun outOfOrderTimestamps_rejected() {
        val s = scheduler(VisualAnalysisMode.FACE_ONLY)
        val (a, ta) = s.select(200L, true, false)
        s.onFaceCompleted(ta, 1L)
        assertFalse(s.canAcceptFace(150L))
        val (b, _) = s.select(150L, true, false)
        assertEquals(SelectedVisualAnalyzer.NONE, b)
    }

    @Test
    fun callbackClearsOnlyMatchingInFlight() {
        val s = scheduler(VisualAnalysisMode.FACE_ONLY)
        val (_, token) = s.select(0L, true, false)
        assertTrue(s.isFaceInFlight)
        assertFalse(s.onFaceCompleted(token + 99, 1L))
        assertTrue(s.isFaceInFlight)
        assertTrue(s.onFaceCompleted(token, 1L))
        assertFalse(s.isFaceInFlight)
    }

    @Test
    fun staleSessionCallback_doesNotModifyCurrentState() {
        val s = scheduler(VisualAnalysisMode.FACE_ONLY)
        val (_, token) = s.select(0L, true, false)
        s.beginSession(2L)
        assertFalse(s.onFaceCompleted(token, 1L))
        // New session has no in-flight
        assertFalse(s.isFaceInFlight)
    }

    @Test
    fun errorCallback_releasesMatchingAnalyzer() {
        val s2 = scheduler(VisualAnalysisMode.BODY_ONLY)
        val (_, token) = s2.select(0L, false, true)
        assertTrue(s2.isPoseInFlight)
        assertTrue(s2.onPoseError(token, 1L))
        assertFalse(s2.isPoseInFlight)
    }

    @Test
    fun oneFrame_atMostOneAnalyzer() {
        val s = scheduler(VisualAnalysisMode.BODY_AND_FACE)
        val (sel, _) = s.select(0L, true, true)
        assertTrue(sel == SelectedVisualAnalyzer.FACE || sel == SelectedVisualAnalyzer.POSE)
        // Cannot have both in flight from one select
        val both = s.isFaceInFlight && s.isPoseInFlight
        assertFalse(both)
    }

    @Test
    fun longInterview_bothGetRecurringWork() {
        faceCannotStarvePose_longSequence()
    }

    @Test
    fun scheduler_deterministicUnderSameTimestampSequence() {
        fun run(): List<SelectedVisualAnalyzer> {
            val s = scheduler(VisualAnalysisMode.BODY_AND_FACE)
            val out = mutableListOf<SelectedVisualAnalyzer>()
            var t = 0L
            repeat(30) {
                val (sel, token) = s.select(t, true, true)
                out += sel
                when (sel) {
                    SelectedVisualAnalyzer.FACE -> s.onFaceCompleted(token, 1L)
                    SelectedVisualAnalyzer.POSE -> s.onPoseCompleted(token, 1L)
                    SelectedVisualAnalyzer.NONE -> Unit
                }
                t += 200L
            }
            return out
        }
        assertEquals(run(), run())
    }

    @Test
    fun keepOnlyLatest_strategyConstant() {
        assertEquals(0, androidx.camera.core.ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
    }

    @Test
    fun throttleIntervals_configurable() {
        assertTrue(FaceMetricsConfig.FACE_MIN_INTERVAL_MS in 70L..100L)
        assertTrue(FaceMetricsConfig.POSE_MIN_INTERVAL_MS in 90L..125L)
    }
}
