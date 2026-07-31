package com.orato.app.metrics

/**
 * Provisional gesture-activity band. Not a quality ranking — more movement is
 * not assumed to be better.
 */
enum class GestureActivityClass {
    LOW,
    BALANCED,
    HIGH,
}

/**
 * Live per-frame debug snapshot for the practice overlay panel.
 * Raw measurements only (no session scores).
 */
data class LiveBodyMetrics(
    val validDetection: Boolean = false,
    /** Absolute shoulder tilt ratio, or null when not measurable this frame. */
    val shoulderTilt: Float? = null,
    /** Absolute trunk inclination in degrees, or null when not measurable. */
    val trunkAngleDegrees: Float? = null,
    val oneHandVisible: Boolean = false,
    val twoHandsVisible: Boolean = false,
)

/**
 * Raw measurement plus optional provisional presentation score.
 * When [insufficientData] is true, UI should show “Dati insufficienti”.
 */
data class ScoredMetric(
    /** Raw aggregated measurement (tilt ratio, degrees, sway, …). */
    val rawValue: Float?,
    /** Provisional 0–100 engineering score; null when data are insufficient. */
    val score: Int?,
    val insufficientData: Boolean,
) {
    companion object {
        fun insufficient(): ScoredMetric =
            ScoredMetric(rawValue = null, score = null, insufficientData = true)
    }
}

/**
 * Percentage metric (0–100) with insufficient-data flag.
 */
data class PercentMetric(
    val percent: Float?,
    val insufficientData: Boolean,
) {
    companion object {
        fun insufficient(): PercentMetric =
            PercentMetric(percent = null, insufficientData = true)
    }
}

/**
 * Gesture activity session summary. Classification is provisional.
 */
data class GestureActivityMetric(
    /** Mean normalized wrist movement over accepted frames. */
    val averageActivity: Float?,
    /** Percentage of accepted frames with meaningful hand movement (0–100). */
    val activeTimePercent: Float?,
    val classification: GestureActivityClass?,
    val insufficientData: Boolean,
) {
    companion object {
        fun insufficient(): GestureActivityMetric =
            GestureActivityMetric(
                averageActivity = null,
                activeTimePercent = null,
                classification = null,
                insufficientData = true,
            )
    }
}

/**
 * Immutable end-of-session body report.
 * Raw measurements stay separate from presentation scores.
 */
data class SessionBodyReport(
    val cameraPresence: PercentMetric,
    val shoulderBalance: ScoredMetric,
    /** Median absolute trunk inclination (degrees) + provisional score. */
    val trunkInclination: ScoredMetric,
    val trunkStability: ScoredMetric,
    val oneHandVisibility: PercentMetric,
    val twoHandVisibility: PercentMetric,
    val gestureActivity: GestureActivityMetric,
) {
    val hasInsufficientData: Boolean
        get() = cameraPresence.insufficientData ||
            shoulderBalance.insufficientData ||
            trunkInclination.insufficientData ||
            trunkStability.insufficientData ||
            oneHandVisibility.insufficientData ||
            twoHandVisibility.insufficientData ||
            gestureActivity.insufficientData

    companion object {
        fun emptyInsufficient(): SessionBodyReport =
            SessionBodyReport(
                cameraPresence = PercentMetric.insufficient(),
                shoulderBalance = ScoredMetric.insufficient(),
                trunkInclination = ScoredMetric.insufficient(),
                trunkStability = ScoredMetric.insufficient(),
                oneHandVisibility = PercentMetric.insufficient(),
                twoHandVisibility = PercentMetric.insufficient(),
                gestureActivity = GestureActivityMetric.insufficient(),
            )
    }
}
