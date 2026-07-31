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
        // threshold, -threshold, MAX_VALUE → 3/5
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

    @Test
    fun calibration_setsNoiseFloorFromInitialFrames() {
        val vad = detector()
        val frameMs = 20
        val framesNeeded = (AudioMetricsConfig.NOISE_FLOOR_CALIBRATION_MS + frameMs - 1) / frameMs
        repeat(framesNeeded) {
            assertFalse(vad.processFrameDbfs(-60.0))
        }
        assertEquals(-60.0, vad.noiseFloorDbfs(), 0.5)
        assertFalse(vad.isSpeech())
    }

    @Test
    fun attack_requiresConsecutiveCandidates() {
        val vad = detector()
        // Calibrate on silence.
        repeat(20) { vad.processFrameDbfs(-55.0) }
        val speechLevel = vad.noiseFloorDbfs() + AudioMetricsConfig.SPEECH_MARGIN_DB + 5.0

        // Fewer than attack frames → still not speech.
        repeat(AudioMetricsConfig.SPEECH_ATTACK_FRAMES - 1) {
            assertFalse(vad.processFrameDbfs(speechLevel))
        }
        // Attack completes.
        assertTrue(vad.processFrameDbfs(speechLevel))
        assertTrue(vad.isSpeech())
    }

    @Test
    fun release_requiresConsecutiveNonCandidates() {
        val vad = detector()
        repeat(20) { vad.processFrameDbfs(-55.0) }
        val speechLevel = vad.noiseFloorDbfs() + AudioMetricsConfig.SPEECH_MARGIN_DB + 5.0
        repeat(AudioMetricsConfig.SPEECH_ATTACK_FRAMES) {
            vad.processFrameDbfs(speechLevel)
        }
        assertTrue(vad.isSpeech())

        repeat(AudioMetricsConfig.SPEECH_RELEASE_FRAMES - 1) {
            assertTrue(vad.processFrameDbfs(-55.0))
        }
        assertFalse(vad.processFrameDbfs(-55.0))
        assertFalse(vad.isSpeech())
    }

    @Test
    fun shortNoiseSpikes_doNotBecomeSpeech() {
        val vad = detector()
        repeat(20) { vad.processFrameDbfs(-50.0) }
        val spike = vad.noiseFloorDbfs() + AudioMetricsConfig.SPEECH_MARGIN_DB + 20.0
        // Single-frame spike below attack length.
        assertFalse(vad.processFrameDbfs(spike))
        assertFalse(vad.processFrameDbfs(-50.0))
        assertFalse(vad.isSpeech())
    }

    @Test
    fun reset_clearsState() {
        val vad = detector()
        repeat(20) { vad.processFrameDbfs(-40.0) }
        vad.reset()
        assertEquals(AudioMetricsConfig.SILENCE_DBFS, vad.noiseFloorDbfs(), 0.0)
        assertFalse(vad.isSpeech())
    }
}

class AudioSessionAccumulatorTest {

    private fun makeAnalyzerAndAcc(sampleRate: Int = 16_000): Pair<AudioFrameAnalyzer, AudioSessionAccumulator> {
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
            acc.acceptFrame(result, analyzer.noiseFloorDbfs())
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

    @Test
    fun completeSilence_isInsufficientAndFinite() {
        val (analyzer, acc) = makeAnalyzerAndAcc()
        feedPcm(analyzer, acc, silence(16_000, 2_000))
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
    fun quietSpeechLikeSignal_mayBeTooQuietOrGood() {
        val (analyzer, acc) = makeAnalyzerAndAcc()
        // Calibration silence then quiet tone.
        feedPcm(analyzer, acc, silence(16_000, 400))
        feedPcm(analyzer, acc, sineTone(16_000, 2_000, amplitude = 200))
        val metrics = acc.buildMetrics(
            state = AudioRecordingState.Completed,
            audioSourceLabel = "MIC",
            errorMessage = null,
        )
        // Either insufficient (if VAD never latches) or TOO_QUIET / GOOD with finite values.
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
        feedPcm(analyzer, acc, silence(16_000, 400))
        feedPcm(analyzer, acc, sineTone(16_000, 2_500, amplitude = 8_000))
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
        assertFalse(metrics.meanSpeechDbfs!!.isNaN())
        assertTrue(
            metrics.inputQuality == AudioInputQuality.GOOD ||
                metrics.inputQuality == AudioInputQuality.TOO_QUIET,
        )
    }

    @Test
    fun clippedSignal_reportsClippingQuality() {
        val (analyzer, acc) = makeAnalyzerAndAcc()
        feedPcm(analyzer, acc, silence(16_000, 400))
        feedPcm(analyzer, acc, sineTone(16_000, 2_000, amplitude = 32_767))
        val metrics = acc.buildMetrics(
            state = AudioRecordingState.Completed,
            audioSourceLabel = "MIC",
            errorMessage = null,
        )
        assertNotNull(metrics.clippingPercent)
        if (!metrics.insufficientData) {
            assertTrue(metrics.clippingPercent!! > 0.0)
            // Strong near-full-scale sine should trip clipping threshold.
            assertEquals(AudioInputQuality.CLIPPING, metrics.inputQuality)
        } else {
            // Still report clipping percent when samples exist.
            assertTrue(metrics.clippingPercent!! >= 0.0)
        }
    }

    @Test
    fun alternatingSpeechAndSilence_detectsInternalPauses() {
        val (analyzer, acc) = makeAnalyzerAndAcc()
        // Leading silence (ignored for pauses).
        feedPcm(analyzer, acc, silence(16_000, 400))
        // Speech
        feedPcm(analyzer, acc, sineTone(16_000, 800, amplitude = 10_000))
        // Internal pause ~600 ms
        feedPcm(analyzer, acc, silence(16_000, 600))
        // Speech
        feedPcm(analyzer, acc, sineTone(16_000, 800, amplitude = 10_000))
        // Long internal pause ~1800 ms
        feedPcm(analyzer, acc, silence(16_000, 1_800))
        // Speech
        feedPcm(analyzer, acc, sineTone(16_000, 800, amplitude = 10_000))
        // Trailing silence (ignored)
        feedPcm(analyzer, acc, silence(16_000, 500))

        val metrics = acc.buildMetrics(
            state = AudioRecordingState.Completed,
            audioSourceLabel = "MIC",
            errorMessage = null,
        )
        assertFalse("expected sufficient speech, got $metrics", metrics.insufficientData)
        assertNotNull(metrics.approximatePauseCount)
        assertTrue(metrics.approximatePauseCount!! >= 2)
        assertNotNull(metrics.medianPauseDurationMs)
        assertNotNull(metrics.longestPauseDurationMs)
        assertTrue(metrics.longestPauseDurationMs!! >= 1_500)
        assertNotNull(metrics.pausesOver1500Ms)
        assertTrue(metrics.pausesOver1500Ms!! >= 1)
    }

    @Test
    fun leadingAndTrailingSilence_excludedFromPauseCount() {
        val (analyzer, acc) = makeAnalyzerAndAcc()
        feedPcm(analyzer, acc, silence(16_000, 1_000)) // leading
        feedPcm(analyzer, acc, sineTone(16_000, 1_500, amplitude = 10_000))
        feedPcm(analyzer, acc, silence(16_000, 1_000)) // trailing only — no internal pause
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
    fun shortNoiseSpikes_doNotCreateSpeechSegments() {
        val (analyzer, acc) = makeAnalyzerAndAcc()
        feedPcm(analyzer, acc, silence(16_000, 400))
        // ~20–40 ms spike — below min speech duration even if attack somehow passed.
        val spike = sineTone(16_000, 40, amplitude = 20_000)
        feedPcm(analyzer, acc, spike)
        feedPcm(analyzer, acc, silence(16_000, 1_500))
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
        // Directly drive accumulator with crafted frame results to unit-test segment filter.
        val frame = PcmMath.frameSampleCount(16_000)
        val acc = AudioSessionAccumulator(16_000, frame)
        val frameMs = frame * 1000.0 / 16_000.0
        val briefFrames = (AudioMetricsConfig.MIN_SPEECH_SEGMENT_MS / frameMs).toInt().coerceAtLeast(1) - 1
        // Leading silence frames
        repeat(10) {
            acc.acceptFrame(
                AudioFrameResult(rms = 0.0, dbfs = -80.0, clippedSampleCount = 0, sampleCount = frame, isSpeech = false),
                noiseFloorDbfs = -80.0,
            )
        }
        repeat(briefFrames.coerceAtLeast(1)) {
            acc.acceptFrame(
                AudioFrameResult(rms = 1000.0, dbfs = -20.0, clippedSampleCount = 0, sampleCount = frame, isSpeech = true),
                noiseFloorDbfs = -80.0,
            )
        }
        repeat(50) {
            acc.acceptFrame(
                AudioFrameResult(rms = 0.0, dbfs = -80.0, clippedSampleCount = 0, sampleCount = frame, isSpeech = false),
                noiseFloorDbfs = -80.0,
            )
        }
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
        feedPcm(analyzer, acc, silence(16_000, 400))
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
        assertTrue(AudioMetricsConfig.SPEECH_MARGIN_DB > 0)
        assertTrue(AudioMetricsConfig.SPEECH_ATTACK_FRAMES >= 1)
        assertTrue(AudioMetricsConfig.SPEECH_RELEASE_FRAMES >= 1)
        assertTrue(AudioMetricsConfig.MIN_SPEECH_SEGMENT_MS > 0)
        assertTrue(AudioMetricsConfig.MIN_SILENCE_SEGMENT_MS > 0)
        assertEquals(1_500, AudioMetricsConfig.LONG_PAUSE_THRESHOLD_MS)
        assertEquals("orato_sessions", AudioMetricsConfig.CACHE_SUBDIR)
    }
}
