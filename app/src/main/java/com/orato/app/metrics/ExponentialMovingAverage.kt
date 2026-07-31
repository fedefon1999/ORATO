package com.orato.app.metrics

/**
 * Scalar exponential moving average.
 *
 * Formula: `smoothed = alpha * sample + (1 - alpha) * previous`
 * First sample seeds the filter (no prior state).
 */
class ExponentialMovingAverage(
    private val alpha: Float,
) {
    init {
        require(alpha in 0f..1f) { "alpha must be in [0, 1], was $alpha" }
    }

    private var smoothed: Float? = null

    fun current(): Float? = smoothed

    fun update(sample: Float): Float {
        val next = smoothed?.let { previous ->
            alpha * sample + (1f - alpha) * previous
        } ?: sample
        smoothed = next
        return next
    }

    fun reset() {
        smoothed = null
    }
}

/**
 * Per-landmark 2D EMA used to damp MediaPipe coordinate jitter.
 */
class LandmarkPointSmoother(
    alpha: Float = BodyMetricsConfig.LANDMARK_EMA_ALPHA,
) {
    private val x = ExponentialMovingAverage(alpha)
    private val y = ExponentialMovingAverage(alpha)

    fun update(rawX: Float, rawY: Float): Point2D {
        return Point2D(x = x.update(rawX), y = y.update(rawY))
    }

    fun current(): Point2D? {
        val cx = x.current() ?: return null
        val cy = y.current() ?: return null
        return Point2D(cx, cy)
    }

    fun reset() {
        x.reset()
        y.reset()
    }
}
