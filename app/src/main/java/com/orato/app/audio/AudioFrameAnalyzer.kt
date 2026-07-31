package com.orato.app.audio

/**
 * Per-frame PCM analysis result (outside the Compose UI layer).
 */
data class AudioFrameResult(
    val rms: Double,
    val dbfs: Double,
    val clippedSampleCount: Int,
    val sampleCount: Int,
    val isSpeech: Boolean,
)

/**
 * Analyzes mono PCM frames of approximately 20–30 ms.
 * Delegates VAD to [VoiceActivityDetector]; RMS/dBFS/clipping to [PcmMath].
 */
class AudioFrameAnalyzer(
    sampleRateHz: Int,
    private val frameSampleCount: Int = PcmMath.frameSampleCount(sampleRateHz),
    private val vad: VoiceActivityDetector = VoiceActivityDetector(
        sampleRateHz = sampleRateHz,
        frameSampleCount = frameSampleCount,
    ),
    private val clippingThresholdRatio: Double = AudioMetricsConfig.CLIPPING_THRESHOLD_RATIO,
) {
    fun frameSampleCount(): Int = frameSampleCount

    fun noiseFloorDbfs(): Double = vad.noiseFloorDbfs()

    fun isSpeech(): Boolean = vad.isSpeech()

    fun reset() {
        vad.reset()
    }

    /**
     * Analyzes one frame of PCM samples.
     * @param samples source buffer
     * @param offset start index
     * @param length must equal [frameSampleCount] for normal operation; shorter
     *        trailing frames are still analyzed for RMS/clipping but VAD uses them.
     */
    fun analyze(samples: ShortArray, offset: Int, length: Int): AudioFrameResult {
        val usable = length.coerceAtLeast(0)
        val rms = PcmMath.rms(samples, offset, usable)
        val dbfs = PcmMath.rmsToDbfs(rms)
        val clipPct = PcmMath.clippingPercent(
            samples = samples,
            offset = offset,
            length = usable,
            thresholdRatio = clippingThresholdRatio,
        )
        val clipped = ((clipPct / 100.0) * usable).toInt()
        val speech = if (usable > 0) vad.processFrameDbfs(dbfs) else false
        return AudioFrameResult(
            rms = rms,
            dbfs = dbfs,
            clippedSampleCount = clipped,
            sampleCount = usable,
            isSpeech = speech,
        )
    }
}
