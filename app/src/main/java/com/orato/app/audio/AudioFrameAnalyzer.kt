package com.orato.app.audio

/**
 * Per-frame PCM analysis result (outside the Compose UI layer).
 */
data class AudioFrameResult(
    val rms: Double,
    val dbfs: Double,
    val clippedSampleCount: Int,
    val sampleCount: Int,
    /** Actual frame duration derived from samples / sampleRate / channels. */
    val durationMs: Double,
    val isSpeech: Boolean,
    val vad: VadFrameResult,
)

/**
 * Analyzes mono PCM frames of approximately 20–30 ms.
 * Delegates VAD to [VoiceActivityDetector]; RMS/dBFS/clipping to [PcmMath].
 */
class AudioFrameAnalyzer(
    private val sampleRateHz: Int,
    private val frameSampleCount: Int = PcmMath.frameSampleCount(sampleRateHz),
    private val channelCount: Int = AudioMetricsConfig.CHANNEL_COUNT,
    private val vad: VoiceActivityDetector = VoiceActivityDetector(
        sampleRateHz = sampleRateHz,
        nominalFrameSampleCount = frameSampleCount,
    ),
    private val clippingThresholdRatio: Double = AudioMetricsConfig.CLIPPING_THRESHOLD_RATIO,
) {
    fun frameSampleCount(): Int = frameSampleCount

    fun noiseFloorDbfs(): Double = vad.noiseFloorDbfs()

    fun isSpeech(): Boolean = vad.isSpeech()

    fun vad(): VoiceActivityDetector = vad

    fun reset() {
        vad.reset()
    }

    /**
     * Analyzes one frame of PCM samples.
     * Frame duration is computed from the actual [length] read, [sampleRateHz]
     * and [channelCount] — never assumed equal to the nominal target duration.
     */
    fun analyze(samples: ShortArray, offset: Int, length: Int): AudioFrameResult {
        val usable = length.coerceAtLeast(0)
        val durationMs = PcmMath.frameDurationMs(usable, sampleRateHz, channelCount)
        val rms = PcmMath.rms(samples, offset, usable)
        val dbfs = PcmMath.rmsToDbfs(rms)
        val clipPct = PcmMath.clippingPercent(
            samples = samples,
            offset = offset,
            length = usable,
            thresholdRatio = clippingThresholdRatio,
        )
        val clipped = ((clipPct / 100.0) * usable).toInt()
        val vadResult = if (usable > 0) {
            vad.processFrame(dbfs, durationMs)
        } else {
            VadFrameResult(
                isSpeech = false,
                vadState = vad.vadState(),
                noiseFloorDbfs = vad.noiseFloorDbfs(),
                speechOnThresholdDbfs = vad.speechOnThresholdDbfs(),
                speechOffThresholdDbfs = vad.speechOffThresholdDbfs(),
                currentSpeechSegmentMs = vad.currentSpeechSegmentMs(),
                currentSilenceSegmentMs = vad.currentSilenceSegmentMs(),
                enteredSpeech = false,
                enteredSilence = false,
            )
        }
        return AudioFrameResult(
            rms = rms,
            dbfs = dbfs,
            clippedSampleCount = clipped,
            sampleCount = usable,
            durationMs = durationMs,
            isSpeech = vadResult.isSpeech,
            vad = vadResult,
        )
    }
}
