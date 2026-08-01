package com.orato.app.face

import com.google.mediapipe.tasks.components.containers.NormalizedLandmark
import com.google.mediapipe.tasks.vision.facelandmarker.FaceLandmarkerResult
import kotlin.math.abs
import kotlin.math.hypot
import kotlin.math.max

/**
 * Maps MediaPipe [FaceLandmarkerResult] into domain [FaceFrame].
 * Landmark indexes and matrix parsing stay here — never leak to UI.
 */
object FaceFrameMapper {

    fun map(
        result: FaceLandmarkerResult,
        timestampMs: Long,
        imageWidth: Int,
        imageHeight: Int,
    ): FaceFrame {
        val landmarks = result.faceLandmarks().firstOrNull()
        if (landmarks == null || landmarks.isEmpty()) {
            return emptyFrame(timestampMs)
        }

        val matrixList = result.facialTransformationMatrixes()
        val matrix = if (matrixList.isPresent && matrixList.get().isNotEmpty()) {
            matrixList.get().firstOrNull()
        } else {
            null
        }
        val euler = if (matrix != null && matrix.size >= 16) {
            FacialMatrixEuler.fromRowMajor4x4(matrix)
        } else {
            null
        }

        val blendshapesOpt = result.faceBlendshapes()
        val blendshapes = if (blendshapesOpt.isPresent) {
            blendshapesOpt.get().firstOrNull()
        } else {
            null
        }
        val leftBlink = blendshapeScore(blendshapes, FaceLandmarkIndex.BS_EYE_BLINK_LEFT)
        val rightBlink = blendshapeScore(blendshapes, FaceLandmarkIndex.BS_EYE_BLINK_RIGHT)
        val leftEyeOpen = leftBlink?.let { it < 0.5f }
        val rightEyeOpen = rightBlink?.let { it < 0.5f }

        val forehead = point(landmarks, FaceLandmarkIndex.FOREHEAD)
        val chin = point(landmarks, FaceLandmarkIndex.CHIN)
        val leftCheek = point(landmarks, FaceLandmarkIndex.LEFT_CHEEK)
        val rightCheek = point(landmarks, FaceLandmarkIndex.RIGHT_CHEEK)

        val faceCenterX = averageNullable(forehead?.x, chin?.x, leftCheek?.x, rightCheek?.x)
        val faceCenterY = averageNullable(forehead?.y, chin?.y, leftCheek?.y, rightCheek?.y)
        val faceScale = if (forehead != null && chin != null) {
            hypot((forehead.x - chin.x).toDouble(), (forehead.y - chin.y).toDouble()).toFloat()
        } else {
            null
        }

        val leftIris = irisForEye(
            landmarks = landmarks,
            outer = FaceLandmarkIndex.LEFT_EYE_OUTER,
            inner = FaceLandmarkIndex.LEFT_EYE_INNER,
            upper = FaceLandmarkIndex.LEFT_EYE_UPPER,
            lower = FaceLandmarkIndex.LEFT_EYE_LOWER,
            iris = FaceLandmarkIndex.LEFT_IRIS_CENTER,
            eyeOpen = leftEyeOpen,
            yawDeg = euler?.yawDeg,
            pitchDeg = euler?.pitchDeg,
            rollDeg = euler?.rollDeg,
        )
        val rightIris = irisForEye(
            landmarks = landmarks,
            outer = FaceLandmarkIndex.RIGHT_EYE_OUTER,
            inner = FaceLandmarkIndex.RIGHT_EYE_INNER,
            upper = FaceLandmarkIndex.RIGHT_EYE_UPPER,
            lower = FaceLandmarkIndex.RIGHT_EYE_LOWER,
            iris = FaceLandmarkIndex.RIGHT_IRIS_CENTER,
            eyeOpen = rightEyeOpen,
            yawDeg = euler?.yawDeg,
            pitchDeg = euler?.pitchDeg,
            rollDeg = euler?.rollDeg,
        )

        val confidence = estimateConfidence(landmarks, faceScale)

        return FaceFrame(
            timestampMs = timestampMs,
            facePresent = true,
            faceCenterX = faceCenterX,
            faceCenterY = faceCenterY,
            faceScale = faceScale,
            headYawDeg = euler?.yawDeg,
            headPitchDeg = euler?.pitchDeg,
            headRollDeg = euler?.rollDeg,
            leftIrisHorizontalRatio = leftIris?.horizontal,
            leftIrisVerticalRatio = leftIris?.vertical,
            rightIrisHorizontalRatio = rightIris?.horizontal,
            rightIrisVerticalRatio = rightIris?.vertical,
            leftEyeOpen = leftEyeOpen,
            rightEyeOpen = rightEyeOpen,
            confidence = confidence,
        )
    }

    fun emptyFrame(timestampMs: Long): FaceFrame =
        FaceFrame(
            timestampMs = timestampMs,
            facePresent = false,
            faceCenterX = null,
            faceCenterY = null,
            faceScale = null,
            headYawDeg = null,
            headPitchDeg = null,
            headRollDeg = null,
            leftIrisHorizontalRatio = null,
            leftIrisVerticalRatio = null,
            rightIrisHorizontalRatio = null,
            rightIrisVerticalRatio = null,
            leftEyeOpen = null,
            rightEyeOpen = null,
            confidence = 0f,
        )

    private fun irisForEye(
        landmarks: List<NormalizedLandmark>,
        outer: Int,
        inner: Int,
        upper: Int,
        lower: Int,
        iris: Int,
        eyeOpen: Boolean?,
        yawDeg: Float?,
        pitchDeg: Float?,
        rollDeg: Float?,
    ): IrisGeometry.IrisRatios? {
        if (eyeOpen == false) return null
        if (yawDeg != null && abs(yawDeg) > FaceMetricsConfig.MAX_ABS_YAW_FOR_IRIS_DEG) return null
        if (pitchDeg != null && abs(pitchDeg) > FaceMetricsConfig.MAX_ABS_PITCH_FOR_IRIS_DEG) return null
        if (rollDeg != null && abs(rollDeg) > FaceMetricsConfig.MAX_ABS_ROLL_FOR_IRIS_DEG) return null

        val o = point(landmarks, outer) ?: return null
        val i = point(landmarks, inner) ?: return null
        val u = point(landmarks, upper) ?: return null
        val l = point(landmarks, lower) ?: return null
        val c = point(landmarks, iris) ?: return null
        return IrisGeometry.eyeRatios(
            outerCorner = IrisGeometry.Point(o.x, o.y),
            innerCorner = IrisGeometry.Point(i.x, i.y),
            upperLid = IrisGeometry.Point(u.x, u.y),
            lowerLid = IrisGeometry.Point(l.x, l.y),
            irisCenter = IrisGeometry.Point(c.x, c.y),
            // Both eyes use outer→inner as +X in upright image space (mirroring applied once in UI).
            flipHorizontal = false,
        )
    }

    private fun point(landmarks: List<NormalizedLandmark>, index: Int): IrisGeometry.Point? {
        val lm = landmarks.getOrNull(index) ?: return null
        return IrisGeometry.Point(lm.x(), lm.y())
    }

    private fun blendshapeScore(
        categories: List<com.google.mediapipe.tasks.components.containers.Category>?,
        name: String,
    ): Float? {
        if (categories == null) return null
        return categories.firstOrNull { it.categoryName() == name }?.score()
    }

    private fun estimateConfidence(
        landmarks: List<NormalizedLandmark>,
        faceScale: Float?,
    ): Float {
        if (landmarks.size < 100) return 0.3f
        val scaleOk = faceScale != null &&
            faceScale >= FaceMetricsConfig.MIN_FACE_SCALE &&
            faceScale <= FaceMetricsConfig.MAX_FACE_SCALE
        return if (scaleOk) 0.85f else 0.6f
    }

    private fun averageNullable(vararg values: Float?): Float? {
        val present = values.filterNotNull()
        if (present.isEmpty()) return null
        return present.sum() / present.size
    }
}
