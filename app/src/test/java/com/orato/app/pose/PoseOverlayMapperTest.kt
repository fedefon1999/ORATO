package com.orato.app.pose

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PoseOverlayMapperTest {

    @Test
    fun mapsNormalizedCenter_withoutMirroring_toViewCenter_whenAspectMatches() {
        val point = PoseOverlayMapper.toOverlayPoint(
            normalizedX = 0.5f,
            normalizedY = 0.5f,
            imageWidth = 100f,
            imageHeight = 200f,
            viewWidth = 100f,
            viewHeight = 200f,
            mirrorHorizontally = false,
        )

        assertEquals(50f, point.x, 0.01f)
        assertEquals(100f, point.y, 0.01f)
    }

    @Test
    fun mirrorsHorizontally_forFrontCamera() {
        val mirrored = PoseOverlayMapper.toOverlayPoint(
            normalizedX = 0.25f,
            normalizedY = 0.4f,
            imageWidth = 100f,
            imageHeight = 100f,
            viewWidth = 100f,
            viewHeight = 100f,
            mirrorHorizontally = true,
        )
        val unmirrored = PoseOverlayMapper.toOverlayPoint(
            normalizedX = 0.25f,
            normalizedY = 0.4f,
            imageWidth = 100f,
            imageHeight = 100f,
            viewWidth = 100f,
            viewHeight = 100f,
            mirrorHorizontally = false,
        )

        assertEquals(100f - unmirrored.x, mirrored.x, 0.01f)
        assertEquals(unmirrored.y, mirrored.y, 0.01f)
        assertEquals(75f, mirrored.x, 0.01f)
        assertEquals(40f, mirrored.y, 0.01f)
    }

    @Test
    fun frontCameraMirroring_isSymmetricAroundVerticalCenter() {
        val left = PoseOverlayMapper.toOverlayPoint(
            normalizedX = 0.2f,
            normalizedY = 0.5f,
            imageWidth = 200f,
            imageHeight = 200f,
            viewWidth = 200f,
            viewHeight = 200f,
            mirrorHorizontally = true,
        )
        val right = PoseOverlayMapper.toOverlayPoint(
            normalizedX = 0.8f,
            normalizedY = 0.5f,
            imageWidth = 200f,
            imageHeight = 200f,
            viewWidth = 200f,
            viewHeight = 200f,
            mirrorHorizontally = true,
        )

        assertEquals(200f - left.x, right.x, 0.01f)
        assertEquals(left.y, right.y, 0.01f)
    }

    @Test
    fun fillCenter_scalesAndCenterCrops_whenImageIsWiderThanView() {
        // Image 200x100 into view 100x100 → scale = max(0.5, 1) = 1
        // scaled = 200x100, offsetX = -50, offsetY = 0
        val topLeft = PoseOverlayMapper.toOverlayPoint(
            normalizedX = 0f,
            normalizedY = 0f,
            imageWidth = 200f,
            imageHeight = 100f,
            viewWidth = 100f,
            viewHeight = 100f,
            mirrorHorizontally = false,
        )
        val center = PoseOverlayMapper.toOverlayPoint(
            normalizedX = 0.5f,
            normalizedY = 0.5f,
            imageWidth = 200f,
            imageHeight = 100f,
            viewWidth = 100f,
            viewHeight = 100f,
            mirrorHorizontally = false,
        )
        val bottomRight = PoseOverlayMapper.toOverlayPoint(
            normalizedX = 1f,
            normalizedY = 1f,
            imageWidth = 200f,
            imageHeight = 100f,
            viewWidth = 100f,
            viewHeight = 100f,
            mirrorHorizontally = false,
        )

        assertEquals(-50f, topLeft.x, 0.01f)
        assertEquals(0f, topLeft.y, 0.01f)
        assertEquals(50f, center.x, 0.01f)
        assertEquals(50f, center.y, 0.01f)
        assertEquals(150f, bottomRight.x, 0.01f)
        assertEquals(100f, bottomRight.y, 0.01f)
    }

    @Test
    fun fillCenter_scalesAndCenterCrops_whenImageIsTallerThanView() {
        // Image 100x200 into view 100x100 → scale = max(1, 0.5) = 1
        // scaled = 100x200, offsetX = 0, offsetY = -50
        val topLeft = PoseOverlayMapper.toOverlayPoint(
            normalizedX = 0f,
            normalizedY = 0f,
            imageWidth = 100f,
            imageHeight = 200f,
            viewWidth = 100f,
            viewHeight = 100f,
            mirrorHorizontally = false,
        )
        val center = PoseOverlayMapper.toOverlayPoint(
            normalizedX = 0.5f,
            normalizedY = 0.5f,
            imageWidth = 100f,
            imageHeight = 200f,
            viewWidth = 100f,
            viewHeight = 100f,
            mirrorHorizontally = false,
        )

        assertEquals(0f, topLeft.x, 0.01f)
        assertEquals(-50f, topLeft.y, 0.01f)
        assertEquals(50f, center.x, 0.01f)
        assertEquals(50f, center.y, 0.01f)
    }

    @Test
    fun fillCenter_scalesUp_whenViewIsLargerThanImage() {
        // Image 100x100 into view 300x200 → scale = max(3, 2) = 3
        // scaled = 300x300, offsetX = 0, offsetY = -50
        val center = PoseOverlayMapper.toOverlayPoint(
            normalizedX = 0.5f,
            normalizedY = 0.5f,
            imageWidth = 100f,
            imageHeight = 100f,
            viewWidth = 300f,
            viewHeight = 200f,
            mirrorHorizontally = false,
        )

        assertEquals(150f, center.x, 0.01f)
        assertEquals(100f, center.y, 0.01f)
    }

    @Test
    fun mapFrame_appliesMirroringToAllLandmarks() {
        val frame = UpperBodyPoseFrame(
            landmarks = mapOf(
                PoseLandmarkId.LEFT_SHOULDER to NormalizedLandmarkPoint(
                    id = PoseLandmarkId.LEFT_SHOULDER,
                    x = 0.3f,
                    y = 0.2f,
                    visibility = 0.9f,
                ),
                PoseLandmarkId.RIGHT_SHOULDER to NormalizedLandmarkPoint(
                    id = PoseLandmarkId.RIGHT_SHOULDER,
                    x = 0.7f,
                    y = 0.2f,
                    visibility = 0.9f,
                ),
            ),
            imageWidth = 100,
            imageHeight = 100,
        )

        val mapped = PoseOverlayMapper.mapFrame(
            frame = frame,
            viewWidth = 100f,
            viewHeight = 100f,
            mirrorHorizontally = true,
        )

        assertEquals(2, mapped.size)
        assertEquals(70f, mapped.getValue(PoseLandmarkId.LEFT_SHOULDER).x, 0.01f)
        assertEquals(30f, mapped.getValue(PoseLandmarkId.RIGHT_SHOULDER).x, 0.01f)
        assertTrue(
            mapped.getValue(PoseLandmarkId.LEFT_SHOULDER).x >
                mapped.getValue(PoseLandmarkId.RIGHT_SHOULDER).x,
        )
    }
}
