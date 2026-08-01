package com.orato.app.audio

/**
 * User-facing pause duration bucket.
 * Raw silence segments shorter than [AudioMetricsConfig.MIN_SILENCE_SEGMENT_MS]
 * are never counted; everything at or above that floor is kept for debugging
 * and then classified here for the report.
 */
enum class PauseBucket {
    /** 200–499 ms — often normal articulation. */
    Brief,
    /** 500–1499 ms — coaching-relevant. */
    Medium,
    /** ≥ 1500 ms — long pauses. */
    Long,
}

/**
 * Bucketed pause counts derived from raw internal pause durations.
 * [rawPauseDurationsMs] preserves every detected silence gap ≥ min silence.
 */
data class PauseBuckets(
    /** All raw internal pause durations (ms), leading/trailing silence excluded. */
    val rawPauseDurationsMs: List<Long>,
    val briefCount: Int,
    val mediumCount: Int,
    val longCount: Int,
) {
    /** Pauses ≥ [AudioMetricsConfig.SIGNIFICANT_PAUSE_MIN_MS] (medium + long). */
    val significantCount: Int get() = mediumCount + longCount

    /** Total raw pauses (≥ 200 ms). For debugging — not a coaching score. */
    val rawCount: Int get() = rawPauseDurationsMs.size

    companion object {
        fun empty(): PauseBuckets =
            PauseBuckets(
                rawPauseDurationsMs = emptyList(),
                briefCount = 0,
                mediumCount = 0,
                longCount = 0,
            )

        /**
         * Classifies raw internal pause durations into brief / medium / long.
         * Durations below [AudioMetricsConfig.MIN_SILENCE_SEGMENT_MS] are ignored.
         */
        fun fromDurations(
            durationsMs: List<Long>,
            minSilenceMs: Int = AudioMetricsConfig.MIN_SILENCE_SEGMENT_MS,
            significantMinMs: Int = AudioMetricsConfig.SIGNIFICANT_PAUSE_MIN_MS,
            longMinMs: Int = AudioMetricsConfig.LONG_PAUSE_THRESHOLD_MS,
        ): PauseBuckets {
            val raw = durationsMs.filter { it >= minSilenceMs }
            var brief = 0
            var medium = 0
            var long = 0
            for (ms in raw) {
                when (classify(ms, significantMinMs, longMinMs)) {
                    PauseBucket.Brief -> brief++
                    PauseBucket.Medium -> medium++
                    PauseBucket.Long -> long++
                }
            }
            return PauseBuckets(
                rawPauseDurationsMs = raw,
                briefCount = brief,
                mediumCount = medium,
                longCount = long,
            )
        }

        fun classify(
            durationMs: Long,
            significantMinMs: Int = AudioMetricsConfig.SIGNIFICANT_PAUSE_MIN_MS,
            longMinMs: Int = AudioMetricsConfig.LONG_PAUSE_THRESHOLD_MS,
        ): PauseBucket = when {
            durationMs >= longMinMs -> PauseBucket.Long
            durationMs >= significantMinMs -> PauseBucket.Medium
            else -> PauseBucket.Brief
        }
    }
}

/** Compact coaching summary labels (Italian). */
enum class AcquisitionSummary { Ottima, Sufficiente, Problematica }

enum class VolumeSummary { Buono, TroppoBasso, Clipping }

enum class LongPauseSummary { BenGestite, DaControllare }

/**
 * Presentation helpers for the local voice report.
 * Does not change VAD or capture logic — only formats / buckets metrics.
 */
object VoiceReportPresentation {

    fun pauseBuckets(metrics: AudioSessionMetrics): PauseBuckets =
        metrics.pauseBuckets ?: PauseBuckets.empty()

    fun acquisitionSummary(metrics: AudioSessionMetrics): AcquisitionSummary =
        when {
            metrics.insufficientData ||
                metrics.inputQuality == AudioInputQuality.INSUFFICIENT_AUDIO ||
                metrics.inputQuality == AudioInputQuality.RECORDING_ERROR ->
                AcquisitionSummary.Problematica
            metrics.inputQuality == AudioInputQuality.GOOD ->
                AcquisitionSummary.Ottima
            else ->
                AcquisitionSummary.Sufficiente
        }

    fun volumeSummary(metrics: AudioSessionMetrics): VolumeSummary =
        when (metrics.inputQuality) {
            AudioInputQuality.CLIPPING -> VolumeSummary.Clipping
            AudioInputQuality.TOO_QUIET -> VolumeSummary.TroppoBasso
            else -> VolumeSummary.Buono
        }

    fun longPauseSummary(metrics: AudioSessionMetrics): LongPauseSummary {
        val longCount = metrics.pauseBuckets?.longCount
            ?: metrics.pausesOver1500Ms
            ?: 0
        return if (longCount == 0) {
            LongPauseSummary.BenGestite
        } else {
            LongPauseSummary.DaControllare
        }
    }

    fun acquisitionLabel(summary: AcquisitionSummary): String =
        when (summary) {
            AcquisitionSummary.Ottima -> "Ottima"
            AcquisitionSummary.Sufficiente -> "Sufficiente"
            AcquisitionSummary.Problematica -> "Problematica"
        }

    fun volumeLabel(summary: VolumeSummary): String =
        when (summary) {
            VolumeSummary.Buono -> "Buono"
            VolumeSummary.TroppoBasso -> "Troppo basso"
            VolumeSummary.Clipping -> "Clipping"
        }

    fun longPauseLabel(summary: LongPauseSummary): String =
        when (summary) {
            LongPauseSummary.BenGestite -> "Ben gestite"
            LongPauseSummary.DaControllare -> "Da controllare"
        }

    /** Signed dBFS line for the report; never flips the sign. */
    fun formatMeanVolumeDbfs(meanSpeechDbfs: Double): String =
        "Volume medio: %.1f dBFS".format(meanSpeechDbfs)

    const val VOLUME_HINT: String = "più vicino a 0 = più forte"

    const val PAUSE_INTERPRETATION: String =
        "Molte pause brevi possono essere articolazione naturale; " +
            "le pause medie e lunghe sono più rilevanti per il coaching."
}
