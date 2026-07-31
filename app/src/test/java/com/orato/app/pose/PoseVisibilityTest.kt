package com.orato.app.pose

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PoseVisibilityTest {

    @Test
    fun hasSufficientTorsoVisibility_trueWhenShouldersAndHipsVisible() {
        val landmarks = torsoLandmarks(visibility = 0.8f)
        assertTrue(PoseVisibility.hasSufficientTorsoVisibility(landmarks))
    }

    @Test
    fun hasSufficientTorsoVisibility_falseWhenAnyTorsoLandmarkMissing() {
        val landmarks = torsoLandmarks(visibility = 0.9f)
            .filterKeys { it != PoseLandmarkId.LEFT_HIP }
        assertFalse(PoseVisibility.hasSufficientTorsoVisibility(landmarks))
    }

    @Test
    fun hasSufficientTorsoVisibility_falseWhenVisibilityBelowThreshold() {
        val landmarks = torsoLandmarks(visibility = 0.4f)
        assertFalse(PoseVisibility.hasSufficientTorsoVisibility(landmarks))
    }

    private fun torsoLandmarks(visibility: Float): Map<PoseLandmarkId, NormalizedLandmarkPoint> {
        return listOf(
            PoseLandmarkId.LEFT_SHOULDER,
            PoseLandmarkId.RIGHT_SHOULDER,
            PoseLandmarkId.LEFT_HIP,
            PoseLandmarkId.RIGHT_HIP,
        ).associateWith { id ->
            NormalizedLandmarkPoint(
                id = id,
                x = 0.5f,
                y = 0.5f,
                visibility = visibility,
            )
        }
    }
}
