package com.orato.app.pose

import kotlin.math.max

/**
 * Maps MediaPipe normalized landmarks onto a Compose/Canvas overlay that mirrors
 * CameraX [androidx.camera.view.PreviewView.ScaleType.FILL_CENTER] (scale-to-fill + center crop).
 *
 * When [mirrorHorizontally] is true (front camera), X is mirrored so overlay points
 * align with the mirrored preview the user sees.
 */
object PoseOverlayMapper {

    data class OverlayPoint(val x: Float, val y: Float)

    fun toOverlayPoint(
        normalizedX: Float,
        normalizedY: Float,
        imageWidth: Float,
        imageHeight: Float,
        viewWidth: Float,
        viewHeight: Float,
        mirrorHorizontally: Boolean,
    ): OverlayPoint {
        require(imageWidth > 0f && imageHeight > 0f) { "image dimensions must be positive" }
        require(viewWidth > 0f && viewHeight > 0f) { "view dimensions must be positive" }

        val scale = max(viewWidth / imageWidth, viewHeight / imageHeight)
        val scaledWidth = imageWidth * scale
        val scaledHeight = imageHeight * scale
        val offsetX = (viewWidth - scaledWidth) / 2f
        val offsetY = (viewHeight - scaledHeight) / 2f

        val xInScaled = normalizedX * scaledWidth
        val yInScaled = normalizedY * scaledHeight

        val viewX = if (mirrorHorizontally) {
            viewWidth - (xInScaled + offsetX)
        } else {
            xInScaled + offsetX
        }
        val viewY = yInScaled + offsetY

        return OverlayPoint(x = viewX, y = viewY)
    }

    fun mapFrame(
        frame: UpperBodyPoseFrame,
        viewWidth: Float,
        viewHeight: Float,
        mirrorHorizontally: Boolean,
    ): Map<PoseLandmarkId, OverlayPoint> {
        if (viewWidth <= 0f || viewHeight <= 0f) return emptyMap()
        if (frame.imageWidth <= 0 || frame.imageHeight <= 0) return emptyMap()

        return frame.landmarks.mapValues { (_, point) ->
            toOverlayPoint(
                normalizedX = point.x,
                normalizedY = point.y,
                imageWidth = frame.imageWidth.toFloat(),
                imageHeight = frame.imageHeight.toFloat(),
                viewWidth = viewWidth,
                viewHeight = viewHeight,
                mirrorHorizontally = mirrorHorizontally,
            )
        }
    }
}
