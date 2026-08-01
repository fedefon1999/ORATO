package com.orato.app.face

import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.sqrt

/**
 * Deterministic facial transformation matrix → Euler angles (degrees).
 *
 * ## Coordinate axes (MediaPipe facial transformation matrix, column-major 4×4)
 *
 * MediaPipe provides a 4×4 row-major float array in `facialTransformationMatrixes`
 * (16 values). The upper-left 3×3 is the rotation matrix R where columns are
 * the rotated basis vectors of the face in camera space.
 *
 * Convention used here (OpenGL-like camera space after upright rotation):
 * - +X points to the subject's right in the upright image (before front-camera UI mirror)
 * - +Y points up
 * - +Z points toward the camera / out of the screen toward the viewer
 *
 * ## Euler extraction (yaw / pitch / roll)
 *
 * Using R = [r00 r01 r02; r10 r11 r12; r20 r21 r22]:
 * - yaw   (Y): rotation about vertical axis — left/right turn
 * - pitch (X): rotation about horizontal axis — up/down nod
 * - roll  (Z): rotation about depth axis — lateral tilt
 *
 * ```
 * pitch = atan2(-r21, sqrt(r20² + r22²))
 * yaw   = atan2(r20, r22)
 * roll  = atan2(r01, r11)
 * ```
 *
 * Angles are returned in **degrees**.
 *
 * ## Sign convention (subject-relative, upright image)
 *
 * - Positive yaw: subject turns left (nose toward image +X before UI mirror)
 * - Positive pitch: subject looks up
 * - Positive roll: subject tilts head counterclockwise in the upright image
 *
 * ## Front-camera mirroring
 *
 * Inference runs on the upright (rotation-corrected) bitmap **without** horizontal
 * flip. UI overlays may mirror for PreviewView FILL_CENTER. Relative calibration
 * angles cancel absolute bias; yaw sign is consistent within a session.
 *
 * ## CameraX image rotation
 *
 * Callers must rotate ImageProxy by `imageInfo.rotationDegrees` before building
 * the matrix input so that Euler axes match upright preview orientation.
 */
object FacialMatrixEuler {
    data class EulerDeg(
        val yawDeg: Float,
        val pitchDeg: Float,
        val rollDeg: Float,
    )

    /**
     * @param matrix16 row-major 4×4 facial transformation matrix (16 floats)
     */
    fun fromRowMajor4x4(matrix16: FloatArray): EulerDeg {
        require(matrix16.size >= 16) { "Expected 4x4 matrix (16 floats)" }
        val r00 = matrix16[0]
        val r01 = matrix16[1]
        val r02 = matrix16[2]
        val r10 = matrix16[4]
        val r11 = matrix16[5]
        val r12 = matrix16[6]
        val r20 = matrix16[8]
        val r21 = matrix16[9]
        val r22 = matrix16[10]

        val pitch = atan2(-r21.toDouble(), sqrt((r20 * r20 + r22 * r22).toDouble()))
        val yaw = atan2(r20.toDouble(), r22.toDouble())
        val roll = atan2(r01.toDouble(), r11.toDouble())

        return EulerDeg(
            yawDeg = Math.toDegrees(yaw).toFloat(),
            pitchDeg = Math.toDegrees(pitch).toFloat(),
            rollDeg = Math.toDegrees(roll).toFloat(),
        )
    }

    fun relative(current: Float, baseline: Float): Float = current - baseline

    fun absRelative(current: Float, baseline: Float): Float = abs(relative(current, baseline))
}
