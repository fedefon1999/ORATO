package com.orato.app.audio

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import kotlin.io.path.createTempDirectory
import kotlin.math.PI
import kotlin.math.sin

private fun tempDir(prefix: String): File =
    createTempDirectory(prefix).toFile()

class PcmMathTest {

    @Test
    fun rms_ofSilenceIsZero() {
        val samples = ShortArray(160) { 0 }
        assertEquals(0.0, PcmMath.rms(samples), 1e-12)
    }

    @Test
    fun rms_ofConstantAmplitude() {
        val samples = ShortArray(100) { 1000 }
        assertEquals(1000.0, PcmMath.rms(samples), 1e-9)
    }

    @Test
    fun rms_emptyOrInvalidRangeIsZero() {
        assertEquals(0.0, PcmMath.rms(ShortArray(0)), 0.0)
        assertEquals(0.0, PcmMath.rms(ShortArray(10), offset = 5, length = 0), 0.0)
        assertEquals(0.0, PcmMath.rms(ShortArray(10), offset = 8, length = 5), 0.0)
    }

    @Test
    fun dbfs_silenceIsFiniteFloor() {
        val dbfs = PcmMath.rmsToDbfs(0.0)
        assertEquals(AudioMetricsConfig.SILENCE_DBFS, dbfs, 0.0)
        assertTrue(dbfs.isFinite())
        assertFalse(dbfs.isNaN())
    }

    @Test
    fun dbfs_fullScaleNearZero() {
        val dbfs = PcmMath.rmsToDbfs(AudioMetricsConfig.PCM_FULL_SCALE)
        assertEquals(0.0, dbfs, 0.01)
        assertTrue(dbfs.isFinite())
    }

    @Test
    fun dbfs_negativeForNormalSpeechLevels_neverPositiveFlip() {
        // Amplitude 8000 → RMS ≈ 8000 for DC; sine RMS lower — either way negative dBFS.
        val dbfs = PcmMath.rmsToDbfs(8_000.0)
        assertTrue(dbfs < 0.0)
        assertTrue(dbfs.isFinite())
        assertFalse(dbfs.isNaN())
    }

    @Test
    fun dbfs_neverNaNOrInfinityForEdgeInputs() {
        val inputs = doubleArrayOf(
            Double.NaN,
            Double.POSITIVE_INFINITY,
            Double.NEGATIVE_INFINITY,
            -1.0,
            0.0,
            1e-20,
        )
        for (rms in inputs) {
            val dbfs = PcmMath.rmsToDbfs(rms)
            assertTrue("rms=$rms → $dbfs", dbfs.isFinite())
            assertFalse(dbfs.isNaN())
        }
    }

    @Test
    fun clippingPercent_noneWhenQuiet() {
        val samples = ShortArray(100) { 100 }
        assertEquals(0.0, PcmMath.clippingPercent(samples), 1e-9)
    }

    @Test
    fun clippingPercent_countsNearFullScale() {
        val threshold = (AudioMetricsConfig.CLIPPING_THRESHOLD_RATIO * AudioMetricsConfig.PCM_FULL_SCALE).toInt()
        val samples = shortArrayOf(
            0,
            threshold.toShort(),
            (-threshold).toShort(),
            100,
            Short.MAX_VALUE,
        )
        val pct = PcmMath.clippingPercent(samples)
        assertEquals(60.0, pct, 1e-9)
    }

    @Test
    fun standardDeviation_nullForSingleValue() {
        assertNull(PcmMath.standardDeviation(listOf(1.0)))
        assertNull(PcmMath.standardDeviation(emptyList()))
    }

    @Test
    fun standardDeviation_finiteForValues() {
        val std = PcmMath.standardDeviation(listOf(1.0, 3.0, 5.0))
        assertNotNull(std)
        assertTrue(std!!.isFinite())
        assertTrue(std > 0.0)
    }

    @Test
    fun medianLong_oddAndEven() {
        assertEquals(2L, PcmMath.medianLong(listOf(3L, 1L, 2L)))
        assertEquals(2L, PcmMath.medianLong(listOf(1L, 2L, 3L, 4L)))
        assertNull(PcmMath.medianLong(emptyList()))
    }

    @Test
    fun frameSampleCount_at16kIs20ms() {
        assertEquals(320, PcmMath.frameSampleCount(16_000, 20))
    }

    @Test
    fun frameDurationMs_usesActualSamplesRateAndChannels() {
        // 320 mono samples @ 16 kHz → 20 ms
        assertEquals(20.0, PcmMath.frameDurationMs(320, 16_000, 1), 1e-9)
        // Partial frame: 160 samples → 10 ms
        assertEquals(10.0, PcmMath.frameDurationMs(160, 16_000, 1), 1e-9)
        // 441 samples @ 44.1 kHz mono → 10 ms
        assertEquals(10.0, PcmMath.frameDurationMs(441, 44_100, 1), 1e-9)
    }
}

class WavFileWriterTest {

    @Test
    fun buildHeader_hasRiffWaveAndFmt() {
        val header = WavFileWriter.buildHeader(
            sampleRateHz = 16_000,
            channelCount = 1,
            bitsPerSample = 16,
            dataSize = 0,
        )
        assertEquals(44, header.size)
        assertEquals("RIFF", header.copyOfRange(0, 4).toString(Charsets.US_ASCII))
        assertEquals("WAVE", header.copyOfRange(8, 12).toString(Charsets.US_ASCII))
        assertEquals("fmt ", header.copyOfRange(12, 16).toString(Charsets.US_ASCII))
        assertEquals("data", header.copyOfRange(36, 40).toString(Charsets.US_ASCII))
        assertEquals(0, WavFileWriter.readDataSize(header))
        assertEquals(36, WavFileWriter.readRiffChunkSize(header))
    }

    @Test
    fun finalize_patchesDataLength() {
        val dir = tempDir("orato_wav_test")
        try {
            val file = File(dir, "session.wav")
            val writer = WavFileWriter(file, sampleRateHz = 16_000)
            val samples = ShortArray(160) { 100 }
            writer.writeSamples(samples)
            writer.finalizeHeader()
            writer.close()

            val bytes = file.readBytes()
            assertTrue(bytes.size >= 44)
            val header = bytes.copyOfRange(0, 44)
            assertEquals(320, WavFileWriter.readDataSize(header))
            assertEquals(36 + 320, WavFileWriter.readRiffChunkSize(header))
            assertEquals(44 + 320, bytes.size)
        } finally {
            dir.deleteRecursively()
        }
    }

    @Test
    fun close_isIdempotentAndFinalizes() {
        val dir = tempDir("orato_wav_close")
        try {
            val file = File(dir, "a.wav")
            val writer = WavFileWriter(file, sampleRateHz = 44_100)
            writer.writeSamples(ShortArray(10) { 1 })
            writer.close()
            writer.close()
            val header = file.readBytes().copyOfRange(0, 44)
            assertEquals(20, WavFileWriter.readDataSize(header))
        } finally {
            dir.deleteRecursively()
        }
    }
}

class VoiceActivityDetectorTest {

    private fun detector(sampleRate: Int = 16_000): VoiceActivityDetector {
        val frame = PcmMath.frameSampleCount(sampleRate)
        return VoiceActivityDetector(sampleRate, frame)
    }

    private fun calibrate(vad: VoiceActivityDetector, noiseDbfs: Double = -55.0) {
        val frameMs = 20.0
        val framesNeeded =
            ((AudioMetricsConfig.NOISE_FLOOR_CALIBRATION_MS / frameMs).toInt() + 2)
        repeat(framesNeeded) {
            assertFalse(vad.processFrame(noiseDbfs, frameMs).isSpeech)
        }
        assertEquals(VadState.Silence, vad.vadState())
    }

    @Test
    fun calibration_setsNoiseFloorFromInitialFrames() {
        val vad = detector()
        calibrate(vad, -60.0)
        assertEquals(-60.0, vad.noiseFloorDbfs(), 0.5)
        assertFalse(vad.isSpeech())
    }

    @Test
    fun dualThresholds_onIsAboveOff() {
        val vad = detector()
        calibrate(vad, -50.0)
        assertTrue(vad.speechOnThresholdDbfs() > vad.speechOffThresholdDbfs())
        assertEquals(
            vad.noiseFloorDbfs() + AudioMetricsConfig.SPEECH_ON_MARGIN_DB,
            vad.speechOnThresholdDbfs(),
            1e-9,
        )
        assertEquals(
            vad.noiseFloorDbfs() + AudioMetricsConfig.SPEECH_OFF_MARGIN_DB,
            vad.speechOffThresholdDbfs(),
            1e-9,
        )
    }

    @Test
    fun attack_requiresConsecutiveAboveOnThreshold() {
        val vad = detector()
        calibrate(vad, -55.0)
        val speechLevel = vad.speechOnThresholdDbfs() + 5.0

        repeat(AudioMetricsConfig.SPEECH_ATTACK_FRAMES - 1) {
            assertFalse(vad.processFrame(speechLevel, 20.0).isSpeech)
        }
        val entered = vad.processFrame(speechLevel, 20.0)
        assertTrue(entered.isSpeech)
        assertTrue(entered.enteredSpeech)
        assertEquals(VadState.Speech, vad.vadState())
    }

    @Test
    fun release_returnsToSilenceAfterSpeech() {
        val vad = detector()
        calibrate(vad, -55.0)
        val speechLevel = vad.speechOnThresholdDbfs() + 5.0
        repeat(AudioMetricsConfig.SPEECH_ATTACK_FRAMES) {
            vad.processFrame(speechLevel, 20.0)
        }
        assertTrue(vad.isSpeech())

        val silenceLevel = vad.speechOffThresholdDbfs() - 3.0
        repeat(AudioMetricsConfig.SPEECH_RELEASE_FRAMES - 1) {
            assertTrue(vad.processFrame(silenceLevel, 20.0).isSpeech)
        }
        val left = vad.processFrame(silenceLevel, 20.0)
        assertFalse(left.isSpeech)
        assertTrue(left.enteredSilence)
        assertEquals(VadState.Silence, vad.vadState())
    }

    @Test
    fun peakDrop_unlocksSpeechAfterQuietCalibration() {
        val vad = detector()
        // Very quiet calibration (digital silence-like).
        calibrate(vad, -95.0)
        val speechLevel = -25.0
        repeat(AudioMetricsConfig.SPEECH_ATTACK_FRAMES) {
            vad.processFrame(speechLevel, 20.0)
        }
        assertTrue(vad.isSpeech())

        // Ambient pause at -45: still above floor+off (−90), but dropped from peak.
        val ambient = -45.0
        repeat(AudioMetricsConfig.SPEECH_RELEASE_FRAMES) {
            vad.processFrame(ambient, 20.0)
        }
        assertFalse("VAD should leave speech via peak-drop", vad.isSpeech())
        assertEquals(VadState.Silence, vad.vadState())
    }

    @Test
    fun shortNoiseSpikes_doNotBecomeSpeech() {
        val vad = detector()
        calibrate(vad, -50.0)
        val spike = vad.speechOnThresholdDbfs() + 20.0
        assertFalse(vad.processFrame(spike, 20.0).isSpeech)
        assertFalse(vad.processFrame(-50.0, 20.0).isSpeech)
        assertFalse(vad.isSpeech())
    }

    @Test
    fun noiseFloor_doesNotRiseDuringActiveSpeech() {
        val vad = detector()
        calibrate(vad, -55.0)
        val floorBefore = vad.noiseFloorDbfs()
        val speechLevel = vad.speechOnThresholdDbfs() + 10.0
        repeat(AudioMetricsConfig.SPEECH_ATTACK_FRAMES) {
            vad.processFrame(speechLevel, 20.0)
        }
        repeat(50) {
            vad.processFrame(speechLevel, 20.0)
        }
        assertTrue(vad.isSpeech())
        // Floor must not climb toward speech level while latched in speech.
        assertTrue(vad.noiseFloorDbfs() <= floorBefore + 1.0)
    }

    @Test
    fun reset_clearsState() {
        val vad = detector()
        calibrate(vad, -40.0)
        vad.reset()
        assertEquals(AudioMetricsConfig.SILENCE_DBFS, vad.noiseFloorDbfs(), 0.0)
        assertFalse(vad.isSpeech())
        assertEquals(VadState.Calibrating, vad.vadState())
    }
}

class AudioSessionAccumulatorTest {

    private fun makeAnalyzerAndAcc(
        sampleRate: Int = 16_000,
    ): Pair<AudioFrameAnalyzer, AudioSessionAccumulator> {
        val frame = PcmMath.frameSampleCount(sampleRate)
        val analyzer = AudioFrameAnalyzer(sampleRate, frame)
        val acc = AudioSessionAccumulator(sampleRate, frame)
        return analyzer to acc
    }

    private fun feedPcm(
        analyzer: AudioFrameAnalyzer,
        acc: AudioSessionAccumulator,
        samples: ShortArray,
    ) {
        val frame = analyzer.frameSampleCount()
        var offset = 0
        while (offset + frame <= samples.size) {
            val result = analyzer.analyze(samples, offset, frame)
            acc.acceptFrame(result)
            offset += frame
        }
    }

    private fun sineTone(
        sampleRate: Int,
        durationMs: Int,
        amplitude: Int,
        freqHz: Double = 220.0,
    ): ShortArray {
        val n = (sampleRate.toLong() * durationMs / 1000L).toInt()
        return ShortArray(n) { i ->
            val t = i.toDouble() / sampleRate
            (amplitude * sin(2.0 * PI * freqHz * t)).toInt().coerceIn(-32767, 32767).toShort()
        }
    }

    private fun silence(sampleRate: Int, durationMs: Int): ShortArray {
        val n = (sampleRate.toLong() * durationMs / 1000L).toInt()
        return ShortArray(n) { 0 }
    }

    private fun ambientNoise(sampleRate: Int, durationMs: Int, amplitude: Int = 80): ShortArray {
        // Quiet broadband-ish noise for realistic floor adaptation between phrases.
        val n = (sampleRate.toLong() * durationMs / 1000L).toInt()
        var seed = 1234567
        return ShortArray(n) {
            seed = seed * 1103515245 + 12345
            val r = ((seed shr 16) and 0x7fff) / 32767.0
            ((r * 2 - 1) * amplitude).toInt().toShort()
        }
    }

    @Test
    fun completeSilence_isInsufficientAndFinite() {
        val (analyzer, acc) = makeAnalyzerAndAcc()
        feedPcm(analyzer, acc, silence(16_000, 2_000))
        acc.finalizeOpenSegment()
        val metrics = acc.buildMetrics(
            state = AudioRecordingState.Completed,
            audioSourceLabel = "MIC",
            errorMessage = null,
        )
        assertTrue(metrics.insufficientData)
        assertEquals(AudioInputQuality.INSUFFICIENT_AUDIO, metrics.inputQuality)
        assertNull(metrics.meanSpeechDbfs)
        assertTrue(metrics.capturedDurationMs >= 1_500)
    }

    @Test
    fun tenSecondsSpeech_twoSecondsSilence_tenSecondsSpeech() {
        val (analyzer, acc) = makeAnalyzerAndAcc()
        // Quiet ambient calibration window.
        feedPcm(analyzer, acc, ambientNoise(16_000, 500, amplitude = 60))
        feedPcm(analyzer, acc, sineTone(16_000, 10_000, amplitude = 10_000))
        feedPcm(analyzer, acc, ambientNoise(16_000, 2_000, amplitude = 60))
        feedPcm(analyzer, acc, sineTone(16_000, 10_000, amplitude = 10_000))
        acc.finalizeOpenSegment()

        val metrics = acc.buildMetrics(
            state = AudioRecordingState.Completed,
            audioSourceLabel = "MIC",
            errorMessage = null,
        )
        assertFalse("expected sufficient metrics, got $metrics", metrics.insufficientData)
        assertNotNull(metrics.speechRatioPercent)
        // ~20 s speech / ~22.5 s total ≈ 89%; allow tolerance for VAD edges.
        assertTrue(
            "speech ratio ${metrics.speechRatioPercent}",
            metrics.speechRatioPercent!! in 70.0..98.0,
        )
        assertEquals(1, metrics.approximatePauseCount)
        assertNotNull(metrics.longestPauseDurationMs)
        assertTrue(metrics.longestPauseDurationMs!! in 1_500..2_500)
        assertTrue(metrics.pausesOver1500Ms!! >= 1)
        assertNotNull(metrics.pauseBuckets)
        assertEquals(1, metrics.pauseBuckets!!.significantCount)
        assertEquals(1, metrics.pauseBuckets!!.longCount)
        assertEquals(0, metrics.pauseBuckets!!.briefCount)
        assertNotNull(metrics.meanSpeechDbfs)
        assertTrue(metrics.meanSpeechDbfs!! < 0.0)
        assertTrue(metrics.meanSpeechDbfs!!.isFinite())
    }

    @Test
    fun twoInternalPauses_ofTwoAndThreeSeconds() {
        val (analyzer, acc) = makeAnalyzerAndAcc()
        feedPcm(analyzer, acc, ambientNoise(16_000, 500, amplitude = 60))
        feedPcm(analyzer, acc, sineTone(16_000, 3_000, amplitude = 10_000))
        feedPcm(analyzer, acc, ambientNoise(16_000, 2_000, amplitude = 60))
        feedPcm(analyzer, acc, sineTone(16_000, 3_000, amplitude = 10_000))
        feedPcm(analyzer, acc, ambientNoise(16_000, 3_000, amplitude = 60))
        feedPcm(analyzer, acc, sineTone(16_000, 3_000, amplitude = 10_000))
        acc.finalizeOpenSegment()

        val metrics = acc.buildMetrics(
            state = AudioRecordingState.Completed,
            audioSourceLabel = "MIC",
            errorMessage = null,
        )
        assertFalse("expected sufficient metrics, got $metrics", metrics.insufficientData)
        assertEquals(2, metrics.approximatePauseCount)
        assertNotNull(metrics.medianPauseDurationMs)
        assertNotNull(metrics.longestPauseDurationMs)
        assertTrue(metrics.longestPauseDurationMs!! >= 2_500)
        assertEquals(2, metrics.pausesOver1500Ms)
        assertNotNull(metrics.pauseBuckets)
        assertEquals(2, metrics.pauseBuckets!!.longCount)
        assertEquals(2, metrics.pauseBuckets!!.significantCount)
        assertEquals(0, metrics.pauseBuckets!!.briefCount)
        // Median of ~2s and ~3s ≈ 2.5s
        assertTrue(metrics.medianPauseDurationMs!! in 1_800..3_200)
    }

    @Test
    fun leadingAndTrailingSilence_excludedFromPauseCount() {
        val (analyzer, acc) = makeAnalyzerAndAcc()
        feedPcm(analyzer, acc, ambientNoise(16_000, 1_500, amplitude = 60))
        feedPcm(analyzer, acc, sineTone(16_000, 4_000, amplitude = 10_000))
        feedPcm(analyzer, acc, ambientNoise(16_000, 1_500, amplitude = 60))
        acc.finalizeOpenSegment()

        val metrics = acc.buildMetrics(
            state = AudioRecordingState.Completed,
            audioSourceLabel = "MIC",
            errorMessage = null,
        )
        assertFalse(metrics.insufficientData)
        assertEquals(0, metrics.approximatePauseCount)
        assertNull(metrics.medianPauseDurationMs)
        assertNull(metrics.longestPauseDurationMs)
        assertEquals(0, metrics.pausesOver1500Ms)
    }

    @Test
    fun speechRatio_basedOnActualSpeechDuration_notCaptureLength() {
        val (analyzer, acc) = makeAnalyzerAndAcc()
        feedPcm(analyzer, acc, ambientNoise(16_000, 500, amplitude = 60))
        feedPcm(analyzer, acc, sineTone(16_000, 5_000, amplitude = 10_000))
        feedPcm(analyzer, acc, ambientNoise(16_000, 5_000, amplitude = 60))
        acc.finalizeOpenSegment()

        val metrics = acc.buildMetrics(
            state = AudioRecordingState.Completed,
            audioSourceLabel = "MIC",
            errorMessage = null,
        )
        assertFalse(metrics.insufficientData)
        // ~5 s speech / ~10.5 s total ≈ 48%
        assertTrue(
            "ratio=${metrics.speechRatioPercent} must reflect speech≠capture",
            metrics.speechRatioPercent!! in 35.0..65.0,
        )
        assertTrue(metrics.speechRatioPercent!! < 90.0)
    }

    @Test
    fun openSegment_finalizedWhenRecordingStops() {
        val frame = PcmMath.frameSampleCount(16_000)
        val analyzer = AudioFrameAnalyzer(16_000, frame)
        val acc = AudioSessionAccumulator(16_000, frame)
        feedPcm(analyzer, acc, ambientNoise(16_000, 500, amplitude = 60))
        feedPcm(analyzer, acc, sineTone(16_000, 2_000, amplitude = 10_000))
        // Still in an open speech segment — not yet finalized as Speech.
        assertEquals(0, acc.finalizedSpeechSegmentCount())
        acc.finalizeOpenSegment()
        assertTrue(
            "open speech must become a finalized segment on stop",
            acc.finalizedSpeechSegmentCount() >= 1,
        )
        val metrics = acc.buildMetrics(
            state = AudioRecordingState.Completed,
            audioSourceLabel = "MIC",
            errorMessage = null,
        )
        assertFalse(metrics.insufficientData)
        assertTrue(metrics.speechRatioPercent!! > 50.0)
    }

    @Test
    fun meanSpeechDbfs_remainsNegativeAndFinite() {
        val (analyzer, acc) = makeAnalyzerAndAcc()
        feedPcm(analyzer, acc, ambientNoise(16_000, 400, amplitude = 60))
        feedPcm(analyzer, acc, sineTone(16_000, 3_000, amplitude = 8_000))
        acc.finalizeOpenSegment()
        val metrics = acc.buildMetrics(
            state = AudioRecordingState.Completed,
            audioSourceLabel = "MIC",
            errorMessage = null,
        )
        assertFalse(metrics.insufficientData)
        val mean = metrics.meanSpeechDbfs
        assertNotNull(mean)
        assertTrue(mean!!.isFinite())
        assertFalse(mean.isNaN())
        assertTrue("dBFS must stay signed/negative, was $mean", mean < 0.0)
        // Display string must preserve the minus sign.
        val label = "Volume medio: %.1f dBFS".format(mean)
        assertTrue(label.contains("-"))
        assertFalse(label.contains("Volume medio: +"))
    }

    @Test
    fun quietSpeechLikeSignal_mayBeTooQuietOrGood() {
        val (analyzer, acc) = makeAnalyzerAndAcc()
        feedPcm(analyzer, acc, ambientNoise(16_000, 400, amplitude = 40))
        feedPcm(analyzer, acc, sineTone(16_000, 2_000, amplitude = 400))
        acc.finalizeOpenSegment()
        val metrics = acc.buildMetrics(
            state = AudioRecordingState.Completed,
            audioSourceLabel = "MIC",
            errorMessage = null,
        )
        if (!metrics.insufficientData) {
            assertNotNull(metrics.meanSpeechDbfs)
            assertTrue(metrics.meanSpeechDbfs!!.isFinite())
            assertTrue(
                metrics.inputQuality == AudioInputQuality.TOO_QUIET ||
                    metrics.inputQuality == AudioInputQuality.GOOD,
            )
        }
    }

    @Test
    fun normalSpeechLikeSignal_producesSpeechMetrics() {
        val (analyzer, acc) = makeAnalyzerAndAcc()
        feedPcm(analyzer, acc, ambientNoise(16_000, 400, amplitude = 60))
        feedPcm(analyzer, acc, sineTone(16_000, 2_500, amplitude = 8_000))
        acc.finalizeOpenSegment()
        val metrics = acc.buildMetrics(
            state = AudioRecordingState.Completed,
            audioSourceLabel = "VOICE_RECOGNITION",
            errorMessage = null,
        )
        assertFalse(metrics.insufficientData)
        assertNotNull(metrics.speechRatioPercent)
        assertTrue(metrics.speechRatioPercent!! > 0.0)
        assertNotNull(metrics.meanSpeechDbfs)
        assertTrue(metrics.meanSpeechDbfs!!.isFinite())
        assertTrue(metrics.meanSpeechDbfs!! < 0.0)
    }

    @Test
    fun clippedSignal_reportsClippingQuality() {
        val (analyzer, acc) = makeAnalyzerAndAcc()
        feedPcm(analyzer, acc, ambientNoise(16_000, 400, amplitude = 60))
        feedPcm(analyzer, acc, sineTone(16_000, 2_000, amplitude = 32_767))
        acc.finalizeOpenSegment()
        val metrics = acc.buildMetrics(
            state = AudioRecordingState.Completed,
            audioSourceLabel = "MIC",
            errorMessage = null,
        )
        assertNotNull(metrics.clippingPercent)
        if (!metrics.insufficientData) {
            assertTrue(metrics.clippingPercent!! > 0.0)
            assertEquals(AudioInputQuality.CLIPPING, metrics.inputQuality)
        }
    }

    @Test
    fun shortNoiseSpikes_doNotCreateSpeechSegments() {
        val (analyzer, acc) = makeAnalyzerAndAcc()
        feedPcm(analyzer, acc, ambientNoise(16_000, 400, amplitude = 60))
        feedPcm(analyzer, acc, sineTone(16_000, 40, amplitude = 20_000))
        feedPcm(analyzer, acc, ambientNoise(16_000, 1_500, amplitude = 60))
        acc.finalizeOpenSegment()
        val metrics = acc.buildMetrics(
            state = AudioRecordingState.Completed,
            audioSourceLabel = "MIC",
            errorMessage = null,
        )
        assertTrue(metrics.insufficientData)
        assertEquals(AudioInputQuality.INSUFFICIENT_AUDIO, metrics.inputQuality)
    }

    @Test
    fun minSpeechDuration_filtersBriefLatch() {
        val frame = PcmMath.frameSampleCount(16_000)
        val acc = AudioSessionAccumulator(16_000, frame)
        val frameMs = PcmMath.frameDurationMs(frame, 16_000, 1)
        val briefFrames =
            (AudioMetricsConfig.MIN_SPEECH_SEGMENT_MS / frameMs).toInt().coerceAtLeast(1) - 1
        val silenceVad = VadFrameResult(
            isSpeech = false,
            vadState = VadState.Silence,
            noiseFloorDbfs = -80.0,
            speechOnThresholdDbfs = -70.0,
            speechOffThresholdDbfs = -75.0,
            currentSpeechSegmentMs = 0,
            currentSilenceSegmentMs = 100,
            enteredSpeech = false,
            enteredSilence = false,
        )
        val speechVad = silenceVad.copy(
            isSpeech = true,
            vadState = VadState.Speech,
            currentSpeechSegmentMs = 40,
            currentSilenceSegmentMs = 0,
        )
        repeat(10) {
            acc.acceptFrame(
                AudioFrameResult(
                    rms = 0.0,
                    dbfs = -80.0,
                    clippedSampleCount = 0,
                    sampleCount = frame,
                    durationMs = frameMs,
                    isSpeech = false,
                    vad = silenceVad,
                ),
            )
        }
        repeat(briefFrames.coerceAtLeast(1)) {
            acc.acceptFrame(
                AudioFrameResult(
                    rms = 1000.0,
                    dbfs = -20.0,
                    clippedSampleCount = 0,
                    sampleCount = frame,
                    durationMs = frameMs,
                    isSpeech = true,
                    vad = speechVad,
                ),
            )
        }
        repeat(50) {
            acc.acceptFrame(
                AudioFrameResult(
                    rms = 0.0,
                    dbfs = -80.0,
                    clippedSampleCount = 0,
                    sampleCount = frame,
                    durationMs = frameMs,
                    isSpeech = false,
                    vad = silenceVad,
                ),
            )
        }
        acc.finalizeOpenSegment()
        val metrics = acc.buildMetrics(
            state = AudioRecordingState.Completed,
            audioSourceLabel = "MIC",
            errorMessage = null,
        )
        assertTrue(metrics.insufficientData)
    }

    @Test
    fun recordingError_doesNotFabricateZerosAsSuccess() {
        val (_, acc) = makeAnalyzerAndAcc()
        val metrics = acc.buildMetrics(
            state = AudioRecordingState.Error,
            audioSourceLabel = "MIC",
            errorMessage = "dead object",
        )
        assertEquals(AudioInputQuality.RECORDING_ERROR, metrics.inputQuality)
        assertTrue(metrics.insufficientData)
        assertNull(metrics.speechRatioPercent)
        assertEquals("dead object", metrics.errorMessage)
    }

    @Test
    fun reset_clearsSessionDataBetweenExercises() {
        val (analyzer, acc) = makeAnalyzerAndAcc()
        feedPcm(analyzer, acc, ambientNoise(16_000, 400, amplitude = 60))
        feedPcm(analyzer, acc, sineTone(16_000, 2_000, amplitude = 10_000))
        assertTrue(acc.capturedDurationMs() > 0)
        acc.reset()
        analyzer.reset()
        assertEquals(0L, acc.capturedDurationMs())
        assertEquals(0, acc.droppedReadCount())
        val metrics = acc.buildMetrics(
            state = AudioRecordingState.Completed,
            audioSourceLabel = null,
            errorMessage = null,
        )
        assertTrue(metrics.insufficientData)
        assertEquals(0L, metrics.capturedDurationMs)
    }

    @Test
    fun silentInput_metricsNeverNaNOrInfinity() {
        val (analyzer, acc) = makeAnalyzerAndAcc()
        feedPcm(analyzer, acc, silence(16_000, 1_200))
        val live = acc.liveSnapshot(AudioRecordingState.Recording, "MIC")
        assertTrue(live.currentDbfs == null || live.currentDbfs!!.isFinite())
        assertTrue(live.noiseFloorDbfs == null || live.noiseFloorDbfs!!.isFinite())
        assertTrue(live.rawFrameDbfs == null || live.rawFrameDbfs!!.isFinite())
        acc.finalizeOpenSegment()
        val metrics = acc.buildMetrics(
            state = AudioRecordingState.Completed,
            audioSourceLabel = "MIC",
            errorMessage = null,
        )
        metrics.meanSpeechDbfs?.let {
            assertTrue(it.isFinite())
            assertFalse(it.isNaN())
        }
        metrics.volumeVariationStdDevDb?.let {
            assertTrue(it.isFinite())
            assertFalse(it.isNaN())
        }
        metrics.clippingPercent?.let {
            assertTrue(it.isFinite())
            assertFalse(it.isNaN())
        }
    }
}

class AudioSessionCacheTest {

    @Test
    fun prepareSessionDir_deletesPriorWavFiles() {
        val cache = tempDir("orato_cache")
        try {
            val dir = AudioSessionCache.sessionDir(cache)
            dir.mkdirs()
            val old = File(dir, "old-session.wav")
            old.writeBytes(ByteArray(10))
            assertTrue(old.exists())
            AudioSessionCache.prepareSessionDir(cache)
            assertFalse(old.exists())
            assertTrue(dir.exists())
        } finally {
            cache.deleteRecursively()
        }
    }

    @Test
    fun deleteSessionFile_removesTargetOnly() {
        val cache = tempDir("orato_cache2")
        try {
            val a = AudioSessionCache.sessionFile(cache, "aaa")
            val b = AudioSessionCache.sessionFile(cache, "bbb")
            a.parentFile?.mkdirs()
            a.writeBytes(ByteArray(4))
            b.writeBytes(ByteArray(4))
            AudioSessionCache.deleteSessionFile(cache, "aaa")
            assertFalse(a.exists())
            assertTrue(b.exists())
        } finally {
            cache.deleteRecursively()
        }
    }
}

class AudioMetricsConfigTest {

    @Test
    fun thresholds_areCentralizedAndSane() {
        assertEquals(16_000, AudioMetricsConfig.PREFERRED_SAMPLE_RATE_HZ)
        assertTrue(AudioMetricsConfig.FRAME_DURATION_MS in 20..30)
        assertTrue(AudioMetricsConfig.SPEECH_ON_MARGIN_DB > AudioMetricsConfig.SPEECH_OFF_MARGIN_DB)
        assertTrue(AudioMetricsConfig.SPEECH_OFF_MARGIN_DB > 0)
        assertTrue(AudioMetricsConfig.SPEECH_ATTACK_FRAMES >= 1)
        assertTrue(AudioMetricsConfig.SPEECH_RELEASE_FRAMES >= 1)
        assertTrue(AudioMetricsConfig.MIN_SPEECH_SEGMENT_MS > 0)
        assertEquals(200, AudioMetricsConfig.MIN_SILENCE_SEGMENT_MS)
        assertEquals(500, AudioMetricsConfig.SIGNIFICANT_PAUSE_MIN_MS)
        assertEquals(1_500, AudioMetricsConfig.LONG_PAUSE_THRESHOLD_MS)
        assertEquals("orato_sessions", AudioMetricsConfig.CACHE_SUBDIR)
    }
}

class PauseBucketsTest {

    @Test
    fun classify_briefMediumLongBoundaries() {
        assertEquals(PauseBucket.Brief, PauseBuckets.classify(200))
        assertEquals(PauseBucket.Brief, PauseBuckets.classify(499))
        assertEquals(PauseBucket.Medium, PauseBuckets.classify(500))
        assertEquals(PauseBucket.Medium, PauseBuckets.classify(1_499))
        assertEquals(PauseBucket.Long, PauseBuckets.classify(1_500))
        assertEquals(PauseBucket.Long, PauseBuckets.classify(3_000))
    }

    @Test
    fun fromDurations_keepsRawAndBucketsCounts() {
        val buckets = PauseBuckets.fromDurations(
            listOf(150L, 250L, 400L, 800L, 1_200L, 1_500L, 2_500L),
        )
        // 150 ms below min silence → dropped from raw
        assertEquals(listOf(250L, 400L, 800L, 1_200L, 1_500L, 2_500L), buckets.rawPauseDurationsMs)
        assertEquals(2, buckets.briefCount) // 250, 400
        assertEquals(2, buckets.mediumCount) // 800, 1200
        assertEquals(2, buckets.longCount) // 1500, 2500
        assertEquals(4, buckets.significantCount) // medium + long
        assertEquals(6, buckets.rawCount)
    }

    @Test
    fun fromDurations_emptyWhenOnlySubMinimumGaps() {
        val buckets = PauseBuckets.fromDurations(listOf(50L, 199L))
        assertTrue(buckets.rawPauseDurationsMs.isEmpty())
        assertEquals(0, buckets.significantCount)
        assertEquals(0, buckets.longCount)
    }
}

class VoiceReportPresentationTest {

    private fun metrics(
        quality: AudioInputQuality = AudioInputQuality.GOOD,
        insufficient: Boolean = false,
        meanDbfs: Double? = -24.5,
        buckets: PauseBuckets? = PauseBuckets.empty(),
        longCount: Int? = buckets?.longCount,
    ): AudioSessionMetrics =
        AudioSessionMetrics(
            state = AudioRecordingState.Completed,
            inputQuality = quality,
            capturedDurationMs = 90_000,
            droppedReadCount = 0,
            sampleRateHz = 16_000,
            audioSourceLabel = "MIC",
            speechRatioPercent = 55.0,
            meanSpeechDbfs = meanDbfs,
            volumeVariationStdDevDb = 3.0,
            clippingPercent = 0.1,
            approximatePauseCount = buckets?.rawCount,
            medianPauseDurationMs = 600L,
            longestPauseDurationMs = 2_000L,
            pausesOver1500Ms = longCount,
            pauseBuckets = buckets,
            errorMessage = null,
            insufficientData = insufficient,
        )

    @Test
    fun formatMeanVolumeDbfs_keepsNegativeSign() {
        val text = VoiceReportPresentation.formatMeanVolumeDbfs(-24.5)
        assertEquals("Volume medio: -24,5 dBFS", text)
        assertTrue(text.contains("-24,5"))
        assertFalse(text.contains("Volume medio: +"))
        assertFalse(text.contains("Volume medio: 24,5"))
    }

    @Test
    fun formatMeanVolumeDbfs_neverNaNOrInfinity() {
        val finite = VoiceReportPresentation.formatMeanVolumeDbfs(-18.0)
        assertTrue(finite.contains("dBFS"))
        assertFalse(finite.contains("NaN", ignoreCase = true))
        assertFalse(finite.contains("Infinity", ignoreCase = true))
    }

    @Test
    fun acquisitionSummary_mapsQuality() {
        assertEquals(
            AcquisitionSummary.Ottima,
            VoiceReportPresentation.acquisitionSummary(metrics(AudioInputQuality.GOOD)),
        )
        assertEquals(
            AcquisitionSummary.Sufficiente,
            VoiceReportPresentation.acquisitionSummary(metrics(AudioInputQuality.TOO_QUIET)),
        )
        assertEquals(
            AcquisitionSummary.Sufficiente,
            VoiceReportPresentation.acquisitionSummary(metrics(AudioInputQuality.CLIPPING)),
        )
        assertEquals(
            AcquisitionSummary.Problematica,
            VoiceReportPresentation.acquisitionSummary(
                metrics(AudioInputQuality.INSUFFICIENT_AUDIO, insufficient = true, meanDbfs = null, buckets = null),
            ),
        )
    }

    @Test
    fun volumeSummary_mapsQuality() {
        assertEquals(
            VolumeSummary.Buono,
            VoiceReportPresentation.volumeSummary(metrics(AudioInputQuality.GOOD)),
        )
        assertEquals(
            VolumeSummary.TroppoBasso,
            VoiceReportPresentation.volumeSummary(metrics(AudioInputQuality.TOO_QUIET)),
        )
        assertEquals(
            VolumeSummary.Clipping,
            VoiceReportPresentation.volumeSummary(metrics(AudioInputQuality.CLIPPING)),
        )
    }

    @Test
    fun longPauseSummary_benGestiteWhenNone() {
        val none = PauseBuckets.fromDurations(listOf(300L, 800L))
        assertEquals(
            LongPauseSummary.BenGestite,
            VoiceReportPresentation.longPauseSummary(metrics(buckets = none)),
        )
        assertEquals(
            "Ben gestite",
            VoiceReportPresentation.longPauseLabel(LongPauseSummary.BenGestite),
        )
    }

    @Test
    fun longPauseSummary_daControllareWhenPresent() {
        val withLong = PauseBuckets.fromDurations(listOf(300L, 1_800L))
        assertEquals(
            LongPauseSummary.DaControllare,
            VoiceReportPresentation.longPauseSummary(metrics(buckets = withLong)),
        )
        assertEquals(
            "Da controllare",
            VoiceReportPresentation.longPauseLabel(LongPauseSummary.DaControllare),
        )
    }

    @Test
    fun reportLabels_areItalian() {
        assertEquals("Ottima", VoiceReportPresentation.acquisitionLabel(AcquisitionSummary.Ottima))
        assertEquals("Sufficiente", VoiceReportPresentation.acquisitionLabel(AcquisitionSummary.Sufficiente))
        assertEquals("Problematica", VoiceReportPresentation.acquisitionLabel(AcquisitionSummary.Problematica))
        assertEquals("Buono", VoiceReportPresentation.volumeLabel(VolumeSummary.Buono))
        assertEquals("Troppo basso", VoiceReportPresentation.volumeLabel(VolumeSummary.TroppoBasso))
        assertEquals("Clipping", VoiceReportPresentation.volumeLabel(VolumeSummary.Clipping))
        assertTrue(VoiceReportPresentation.PAUSE_INTERPRETATION.contains("articolazione"))
        assertEquals("Più vicino a 0 = volume più alto.", VoiceReportPresentation.VOLUME_HINT)
    }
}
