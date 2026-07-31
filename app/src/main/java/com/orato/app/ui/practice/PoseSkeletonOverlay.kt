package com.orato.app.ui.practice

import androidx.compose.foundation.Canvas
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.unit.dp
import com.orato.app.pose.PoseLandmarkId
import com.orato.app.pose.PoseOverlayMapper
import com.orato.app.pose.PoseVisibility
import com.orato.app.pose.UpperBodyPoseFrame

private val SkeletonColor = Color(0xFF4ADE80)
private val LandmarkColor = Color(0xFFFACC15)

private val SkeletonConnections = listOf(
    PoseLandmarkId.LEFT_SHOULDER to PoseLandmarkId.RIGHT_SHOULDER,
    PoseLandmarkId.LEFT_SHOULDER to PoseLandmarkId.LEFT_ELBOW,
    PoseLandmarkId.RIGHT_SHOULDER to PoseLandmarkId.RIGHT_ELBOW,
    PoseLandmarkId.LEFT_ELBOW to PoseLandmarkId.LEFT_WRIST,
    PoseLandmarkId.RIGHT_ELBOW to PoseLandmarkId.RIGHT_WRIST,
    PoseLandmarkId.LEFT_SHOULDER to PoseLandmarkId.LEFT_HIP,
    PoseLandmarkId.RIGHT_SHOULDER to PoseLandmarkId.RIGHT_HIP,
    PoseLandmarkId.LEFT_HIP to PoseLandmarkId.RIGHT_HIP,
)

@Composable
fun PoseSkeletonOverlay(
    poseFrame: UpperBodyPoseFrame?,
    mirrorHorizontally: Boolean,
    modifier: Modifier = Modifier,
) {
    Canvas(modifier = modifier) {
        val frame = poseFrame ?: return@Canvas
        if (size.width <= 0f || size.height <= 0f) return@Canvas

        val points = PoseOverlayMapper.mapFrame(
            frame = frame,
            viewWidth = size.width,
            viewHeight = size.height,
            mirrorHorizontally = mirrorHorizontally,
        )
        if (points.isEmpty()) return@Canvas

        val strokeWidth = 3.dp.toPx()
        val pointRadius = 5.dp.toPx()

        for ((fromId, toId) in SkeletonConnections) {
            val from = points[fromId] ?: continue
            val to = points[toId] ?: continue
            val fromLandmark = frame.landmarks[fromId] ?: continue
            val toLandmark = frame.landmarks[toId] ?: continue
            if (fromLandmark.visibility < PoseVisibility.MIN_VISIBILITY) continue
            if (toLandmark.visibility < PoseVisibility.MIN_VISIBILITY) continue

            drawLine(
                color = SkeletonColor,
                start = Offset(from.x, from.y),
                end = Offset(to.x, to.y),
                strokeWidth = strokeWidth,
                cap = StrokeCap.Round,
            )
        }

        for ((id, point) in points) {
            val landmark = frame.landmarks[id] ?: continue
            if (landmark.visibility < PoseVisibility.MIN_VISIBILITY) continue
            drawCircle(
                color = LandmarkColor,
                radius = pointRadius,
                center = Offset(point.x, point.y),
            )
        }
    }
}
