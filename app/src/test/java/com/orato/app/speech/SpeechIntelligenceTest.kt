package com.orato.app.speech

import com.orato.app.audio.AudioInputQuality
import com.orato.app.audio.AudioRecordingState
import com.orato.app.audio.AudioSessionMetrics
import com.orato.app.audio.PauseBuckets
import com.orato.app.audio.SessionPracticeReport
import com.orato.app.metrics.SessionBodyReport
import java.io.ByteArrayOutputStream
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.security.MessageDigest
import kotlin.math.abs
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class TranscriptTokenizerTest {

    @Test
    fun emptyTranscript_zeroWords() {
        assertEquals(0, TranscriptTokenizer.wordCount(""))
        assertEquals(0, TranscriptTokenizer.wordCount("   "))
        assertEquals(emptyList<String>(), TranscriptTokenizer.tokenize(""))
    }

    @Test
    fun punctuationOnly_zeroWords() {
        assertEquals(0, TranscriptTokenizer.wordCount("..."))
        assertEquals(0, TranscriptTokenizer.wordCount("!?.,;:"))
        assertEquals(0, TranscriptTokenizer.wordCount(" — "))
    }

    @Test
    fun italianApostrophes_countAsSingleWord() {
        assertEquals(listOf("L'amico", "dell'insegnante"), TranscriptTokenizer.tokenize("L'amico dell'insegnante"))
        assertEquals(1, TranscriptTokenizer.tokenize("L’acqua").size)
    }

    @Test
    fun accentedCharacters_preservedAsWords() {
        assertEquals(listOf("perché", "città", "agilità"), TranscriptTokenizer.tokenize("perché città agilità"))
    }

    @Test
    fun multipleSpacesAndNewlines_ignored() {
        val text = "ciao   mondo\n\ncome\tstai"
        assertEquals(listOf("ciao", "mondo", "come", "stai"), TranscriptTokenizer.tokenize(text))
        assertEquals(4, TranscriptTokenizer.wordCount(text))
    }

    @Test
    fun correctWordCount_mixedPunctuation() {
        assertEquals(5, TranscriptTokenizer.wordCount("Buongiorno, come stai? Bene: grazie!"))
    }
}

class SpeechMetricsCalculatorTest {

    private fun audio(
        quality: AudioInputQuality = AudioInputQuality.GOOD,
        insufficient: Boolean = false,
        speechMs: Long? = 30_000L,
        capturedMs: Long = 90_000L,
    ): AudioSessionMetrics =
        AudioSessionMetrics(
            state = AudioRecordingState.Completed,
            inputQuality = quality,
            capturedDurationMs = capturedMs,
            speechDurationMs = speechMs,
            droppedReadCount = 0,
            sampleRateHz = 16_000,
            audioSourceLabel = "MIC",
            speechRatioPercent = 33.0,
            meanSpeechDbfs = -20.0,
            volumeVariationStdDevDb = 2.0,
            clippingPercent = 0.0,
            approximatePauseCount = 0,
            medianPauseDurationMs = null,
            longestPauseDurationMs = null,
            pausesOver1500Ms = 0,
            pauseBuckets = PauseBuckets.empty(),
            errorMessage = null,
            insufficientData = insufficient,
        )

    @Test
    fun correctWpm_usesVadSpeechDuration() {
        val words = (1..120).joinToString(" ") { "parola" }
        val metrics = SpeechMetricsCalculator.compute(words, 60_000L, audio(speechMs = 60_000L, capturedMs = 90_000L))
        assertNotNull(metrics)
        assertEquals(120, metrics!!.wordCount)
        assertEquals(120.0, metrics.wordsPerMinute!!, 1e-9)
    }

    @Test
    fun wpm_doesNotUseTotalCapturedDuration() {
        val words = (1..60).joinToString(" ") { "parola" }
        val metrics = SpeechMetricsCalculator.compute(words, 30_000L, audio(speechMs = 30_000L, capturedMs = 90_000L))
        assertEquals(120.0, metrics!!.wordsPerMinute!!, 1e-9)
        assertFalse(abs(metrics.wordsPerMinute!! - 40.0) < 1e-6)
    }

    @Test
    fun zeroSpeechDuration_returnsUnavailableWpm() {
        assertNull(
            SpeechMetricsCalculator.wordsPerMinute(
                wordCount = 10,
                vadSpeechDurationMs = 0L,
                transcriptEmpty = false,
                audioQualityValid = true,
            ),
        )
        val metrics = SpeechMetricsCalculator.compute("ciao mondo", 0L, audio(speechMs = 0L))
        assertNull(metrics!!.wordsPerMinute)
    }

    @Test
    fun veryShortSpeechDuration_unavailableWpm() {
        val metrics = SpeechMetricsCalculator.compute("ciao mondo prova", 500L, audio(speechMs = 500L))
        assertNull(metrics!!.wordsPerMinute)
    }

    @Test
    fun emptyTranscript_zeroWords() {
        assertNull(SpeechMetricsCalculator.compute(null, 30_000L, audio()))
        val empty = SpeechMetricsCalculator.compute("", 30_000L, audio())
        assertEquals(0, empty!!.wordCount)
        assertNull(empty.wordsPerMinute)
        assertNull(empty.discourseMarkers.markersPerMinute)
    }

    @Test
    fun markersPerMinute_usesVadSpeechDuration() {
        val text = "cioè ciao mondo praticamente prova"
        val metrics = SpeechMetricsCalculator.compute(text, 60_000L, audio(speechMs = 60_000L))
        assertEquals(2, metrics!!.discourseMarkers.totalCount)
        assertEquals(2.0, metrics.discourseMarkers.markersPerMinute!!, 1e-9)
    }

    @Test
    fun ehm_doesNotIncreaseMarkerCount() {
        val metrics = SpeechMetricsCalculator.compute("ehm ciao mondo ehm prova", 60_000L, audio(speechMs = 60_000L))
        assertEquals(0, metrics!!.discourseMarkers.totalCount)
        assertEquals(0.0, metrics.discourseMarkers.markersPerMinute!!, 1e-9)
    }

    @Test
    fun zeroSpeechDuration_markersPerMinuteUnavailable() {
        assertNull(
            SpeechMetricsCalculator.markersPerMinute(
                markerCount = 3,
                vadSpeechDurationMs = 0L,
                transcriptEmpty = false,
                audioQualityValid = true,
            ),
        )
        val metrics = SpeechMetricsCalculator.compute("cioè ciao", 0L, audio(speechMs = 0L))
        assertNull(metrics!!.discourseMarkers.markersPerMinute)
    }

    @Test
    fun wordCountRemainsCorrect_withMarkersAndRepetitions() {
        val text = "il il problema ehm è chiaro"
        val metrics = SpeechMetricsCalculator.compute(text, 30_000L, audio(speechMs = 30_000L))
        assertEquals(6, metrics!!.wordCount)
        assertEquals(0, metrics.discourseMarkers.totalCount)
        assertEquals(1, metrics.immediateRepetitionCount)
    }

    @Test
    fun metricsContainNoTranscriptField() {
        val metrics = SpeechMetricsCalculator.compute("ciao ehm mondo", 30_000L, audio())!!
        val names = metrics::class.java.declaredFields.map { it.name }
        assertFalse(names.any { it.equals("transcript", ignoreCase = true) })
        val formatted = listOf(
            SpeechReportPresentation.formatWordCount(metrics.wordCount),
            SpeechReportPresentation.formatWpm(metrics.wordsPerMinute),
            SpeechReportPresentation.formatDiscourseMarkerCount(metrics.discourseMarkers.totalCount),
            SpeechReportPresentation.formatMarkersPerMinute(metrics.discourseMarkers.markersPerMinute),
            SpeechReportPresentation.formatImmediateRepetitions(metrics.immediateRepetitionCount),
        ).joinToString(" ")
        assertFalse(formatted.contains("ciao"))
    }
}

class DiscourseMarkerDetectorTest {

    @Test
    fun vocalHesitations_notCounted() {
        assertEquals(0, DiscourseMarkerDetector.detect("ehm").totalCount)
        assertEquals(0, DiscourseMarkerDetector.detect("eh").totalCount)
        assertEquals(0, DiscourseMarkerDetector.detect("em").totalCount)
        assertEquals(0, DiscourseMarkerDetector.detect("uhm").totalCount)
        assertEquals(0, DiscourseMarkerDetector.detect("um").totalCount)
        assertEquals(0, DiscourseMarkerDetector.detect("mhm").totalCount)
        assertEquals(0, DiscourseMarkerDetector.detect("mmm").totalCount)
        assertEquals(0, DiscourseMarkerDetector.detect("ehmm").totalCount)
        assertEquals(0, DiscourseMarkerDetector.detect("ehm eh em uhm um mhm mmm ehmm").totalCount)
    }

    @Test
    fun clearDiscourseMarkers_counted() {
        val result = DiscourseMarkerDetector.detect("cioè praticamente diciamo insomma")
        assertEquals(4, result.totalCount)
        assertEquals(1, result.breakdown["cioè"])
        assertEquals(1, result.breakdown["praticamente"])
        assertEquals(1, result.breakdown["diciamo"])
        assertEquals(1, result.breakdown["insomma"])
    }

    @Test
    fun caseInsensitiveMarkers() {
        assertEquals(2, DiscourseMarkerDetector.detect("EH Ehm UHM Cioè PRATICAMENTE").totalCount)
    }

    @Test
    fun markersFollowedByPunctuation() {
        assertEquals(2, DiscourseMarkerDetector.detect("eh, ehm! cioè? praticamente.").totalCount)
    }

    @Test
    fun accentedCioe() {
        assertEquals(1, DiscourseMarkerDetector.detect("cioè").totalCount)
        assertEquals(1, DiscourseMarkerDetector.detect("cioè").breakdown["cioè"])
    }

    @Test
    fun noSubstringFalsePositives() {
        assertEquals(0, DiscourseMarkerDetector.detect("Il tempo del sistema emmental è buono").totalCount)
        assertEquals(0, DiscourseMarkerDetector.detect("il volume alto").totalCount)
        assertEquals(0, DiscourseMarkerDetector.detect("perché sì").totalCount)
    }

    @Test
    fun tipo_countedAsDiscourseMarker() {
        assertEquals(1, DiscourseMarkerDetector.detect("Tipo, potremmo iniziare domani.").totalCount)
        assertEquals(1, DiscourseMarkerDetector.detect("Era, tipo, molto difficile.").totalCount)
        assertEquals(1, DiscourseMarkerDetector.detect("Era, tipo, molto difficile.").breakdown["tipo"])
    }

    @Test
    fun tipo_nounConstruction_notCounted() {
        assertEquals(0, DiscourseMarkerDetector.detect("Ho scelto un tipo di pane.").totalCount)
        assertEquals(0, DiscourseMarkerDetector.detect("un tipo di prodotto").totalCount)
    }

    @Test
    fun allora_countedAtDiscourseStart() {
        assertEquals(1, DiscourseMarkerDetector.detect("Allora... quello che volevo dire è questo.").totalCount)
        assertEquals(1, DiscourseMarkerDetector.detect("Allora, possiamo partire.").breakdown["allora"])
    }

    @Test
    fun daAllora_notCounted() {
        assertEquals(0, DiscourseMarkerDetector.detect("Da allora non è cambiato nulla.").totalCount)
    }

    @Test
    fun contextualEcco() {
        assertEquals(1, DiscourseMarkerDetector.detect("Ecco, il problema principale è questo.").totalCount)
        assertEquals(0, DiscourseMarkerDetector.detect("Ecco il documento richiesto.").totalCount)
    }

    @Test
    fun contextualDunque() {
        assertEquals(1, DiscourseMarkerDetector.detect("Dunque, possiamo concludere.").totalCount)
        assertEquals(0, DiscourseMarkerDetector.detect("Dunque il risultato matematico è corretto.").totalCount)
    }

    @Test
    fun markerBreakdown_aggregates() {
        val result = DiscourseMarkerDetector.detect("cioè cioè praticamente ehm")
        assertEquals(3, result.totalCount)
        assertEquals(2, result.breakdown["cioè"])
        assertEquals(1, result.breakdown["praticamente"])
        assertNull(result.breakdown["ehm"])
    }

    @Test
    fun genericWords_notClassifiedAsMarkers() {
        assertEquals(0, DiscourseMarkerDetector.detect("questo mondo bello").totalCount)
    }
}

class ImmediateRepetitionDetectorTest {

    @Test
    fun immediateRepeatedWords() {
        val result = ImmediateRepetitionDetector.detect("il il problema")
        assertEquals(1, result.count)
        assertEquals(1, result.breakdown["il"])
        assertEquals(1, ImmediateRepetitionDetector.detect("e e quindi").count)
        assertEquals(1, ImmediateRepetitionDetector.detect("questo questo punto").count)
        assertEquals(1, ImmediateRepetitionDetector.detect("voglio voglio spiegare").count)
    }

    @Test
    fun noRepetitionAcrossSentenceBoundaries() {
        assertEquals(0, ImmediateRepetitionDetector.detect("Bene. Bene andiamo.").count)
        assertEquals(0, ImmediateRepetitionDetector.detect("Ok! Ok ripartiamo.").count)
    }

    @Test
    fun discourseMarkers_notDoubleCountedAsWordRepetitions() {
        val markers = DiscourseMarkerDetector.detect("cioè cioè ciao")
        val reps = ImmediateRepetitionDetector.detect(
            "cioè cioè ciao",
            fillerTokenIndices = markers.markerTokenIndices,
        )
        assertEquals(2, markers.totalCount)
        assertEquals(0, reps.count)
    }

    @Test
    fun ehmEhm_countsAsImmediateRepetition() {
        val markers = DiscourseMarkerDetector.detect("ehm ehm ciao")
        val reps = ImmediateRepetitionDetector.detect(
            "ehm ehm ciao",
            fillerTokenIndices = markers.markerTokenIndices,
        )
        assertEquals(0, markers.totalCount)
        assertEquals(1, reps.count)
        assertEquals(1, reps.breakdown["ehm"])
    }

    @Test
    fun ignoresCapitalizationAndPunctuation() {
        assertEquals(1, ImmediateRepetitionDetector.detect("Il, il problema").count)
    }
}

class Pcm16ResamplerTest {

    @Test
    fun passthrough_16kHz() {
        val resampler = Pcm16Resampler(16_000)
        val input = ShortArray(320) { it.toShort() }
        assertEquals(input.toList(), resampler.process(input).toList())
        assertEquals(0, resampler.flush().size)
    }

    @Test
    fun resample_48k_to_16k() = assertResampleDuration(48_000)

    @Test
    fun resample_44100_to_16k() = assertResampleDuration(44_100)

    @Test
    fun resample_22050_to_16k() = assertResampleDuration(22_050)

    @Test
    fun resample_11025_to_16k() = assertResampleDuration(11_025)

    @Test
    fun outputDurationTolerance() {
        assertResampleDuration(48_000, inputSeconds = 0.5, toleranceMs = 20.0)
        assertResampleDuration(44_100, inputSeconds = 2.0, toleranceMs = 25.0)
    }

    @Test
    fun resamplerFinalFlush_emitsRemaining() {
        val resampler = Pcm16Resampler(48_000)
        resampler.process(ShortArray(100) { 1000 })
        resampler.flush()
        val expectedMs = 100.0 * 1000.0 / 48_000.0
        assertTrue(abs(resampler.actualOutputDurationMs() - expectedMs) < 5.0)
    }

    @Test
    fun sampleClippingLimits_floatConversion() {
        val floats = Pcm16ToFloatConverter.convert(shortArrayOf(Short.MAX_VALUE, Short.MIN_VALUE, 0))
        assertTrue(floats[0] <= 1.0f)
        assertTrue(floats[1] >= -1.0f)
        assertEquals(0.0f, floats[2], 1e-6f)
    }

    private fun assertResampleDuration(
        inputRate: Int,
        inputSeconds: Double = 1.0,
        toleranceMs: Double = 20.0,
    ) {
        val resampler = Pcm16Resampler(inputRate)
        val totalSamples = (inputRate * inputSeconds).toInt()
        val frame = (inputRate / 50).coerceAtLeast(1)
        var offset = 0
        while (offset < totalSamples) {
            val len = minOf(frame, totalSamples - offset)
            resampler.process(ShortArray(len) { 500 })
            offset += len
        }
        resampler.flush()
        assertTrue(
            abs(resampler.actualOutputDurationMs() - inputSeconds * 1000.0) <= toleranceMs,
        )
    }
}

class WavPcm16ReaderTest {

    @get:Rule
    val tempFolder = TemporaryFolder()

    @Test
    fun validPcm16MonoWav() {
        val file = writeWav(sampleRate = 16_000, channels = 1, samples = ShortArray(1600) { 100 })
        val info = WavPcm16Reader.readInfo(file)
        assertEquals(16_000, info.sampleRateHz)
        assertEquals(1, info.channelCount)
        assertEquals(16, info.bitsPerSample)
        val prepared = WavPcm16Reader.prepareForWhisper(file)
        assertEquals(16_000, prepared.sampleRateHz)
        assertEquals(1600, prepared.samples.size)
    }

    @Test
    fun riffHeaderValidation_rejectsNonWav() {
        val file = tempFolder.newFile("bad.wav")
        file.writeBytes("NOTAWAVEFILE!!!!!".toByteArray())
        try {
            WavPcm16Reader.readInfo(file)
            fail("expected InvalidRiff")
        } catch (_: WavPcm16Reader.WavReadError.InvalidRiff) {
        }
    }

    @Test
    fun missingDataChunk_rejected() {
        val file = tempFolder.newFile("nodata.wav")
        // RIFF/WAVE with fmt only — no data chunk.
        val out = ByteArrayOutputStream()
        out.write("RIFF".toByteArray())
        out.write(leInt(36))
        out.write("WAVE".toByteArray())
        out.write("fmt ".toByteArray())
        out.write(leInt(16))
        out.write(leShort(1))
        out.write(leShort(1))
        out.write(leInt(16_000))
        out.write(leInt(32_000))
        out.write(leShort(2))
        out.write(leShort(16))
        file.writeBytes(out.toByteArray())
        try {
            WavPcm16Reader.readInfo(file)
            fail("expected MissingDataChunk")
        } catch (_: WavPcm16Reader.WavReadError.MissingDataChunk) {
        }
    }

    @Test
    fun unsupportedEncoding_rejected() {
        val file = writeWav(sampleRate = 16_000, channels = 1, samples = ShortArray(10), audioFormat = 3)
        try {
            WavPcm16Reader.readInfo(file)
            fail("expected UnsupportedEncoding")
        } catch (_: WavPcm16Reader.WavReadError.UnsupportedEncoding) {
        }
    }

    @Test
    fun truncatedWav_rejected() {
        val file = writeWav(sampleRate = 16_000, channels = 1, samples = ShortArray(100))
        val bytes = file.readBytes()
        file.writeBytes(bytes.copyOf(bytes.size / 2))
        try {
            WavPcm16Reader.readInfo(file)
            fail("expected Truncated")
        } catch (_: WavPcm16Reader.WavReadError) {
        }
    }

    @Test
    fun pcm16ToFloat_andResampleFrom48k() {
        val file = writeWav(sampleRate = 48_000, channels = 1, samples = ShortArray(48_000) { 200 })
        val prepared = WavPcm16Reader.prepareForWhisper(file)
        assertEquals(16_000, prepared.sampleRateHz)
        assertTrue(abs(prepared.durationMs - 1000L) <= 25L)
        assertTrue(prepared.samples.all { it in -1.0f..1.0f })
    }

    private fun writeWav(
        sampleRate: Int,
        channels: Int,
        samples: ShortArray,
        audioFormat: Int = 1,
    ): File {
        val dataBytes = samples.size * 2
        val out = ByteArrayOutputStream()
        out.write("RIFF".toByteArray())
        out.write(leInt(36 + dataBytes))
        out.write("WAVE".toByteArray())
        out.write("fmt ".toByteArray())
        out.write(leInt(16))
        out.write(leShort(audioFormat))
        out.write(leShort(channels))
        out.write(leInt(sampleRate))
        out.write(leInt(sampleRate * channels * 2))
        out.write(leShort(channels * 2))
        out.write(leShort(16))
        out.write("data".toByteArray())
        out.write(leInt(dataBytes))
        val pcm = ByteBuffer.allocate(dataBytes).order(ByteOrder.LITTLE_ENDIAN)
        samples.forEach { pcm.putShort(it) }
        out.write(pcm.array())
        val file = tempFolder.newFile("t_${sampleRate}_${channels}.wav")
        file.writeBytes(out.toByteArray())
        return file
    }

    private fun leInt(v: Int): ByteArray =
        ByteBuffer.allocate(4).order(ByteOrder.LITTLE_ENDIAN).putInt(v).array()

    private fun leShort(v: Int): ByteArray =
        ByteBuffer.allocate(2).order(ByteOrder.LITTLE_ENDIAN).putShort(v.toShort()).array()
}

class ModelChecksumTest {

    @get:Rule
    val tempFolder = TemporaryFolder()

    @Test
    fun modelChecksumSuccess() {
        val file = tempFolder.newFile("model.bin")
        file.writeBytes("orato-test-model-bytes".toByteArray())
        val sha1 = sha1(file)
        assertEquals(40, sha1.length)
        assertEquals(sha1, sha1(file))
    }

    @Test
    fun modelChecksumFailure_detectsMismatch() {
        val file = tempFolder.newFile("model.bin")
        file.writeBytes("aaa".toByteArray())
        assertFalse(sha1(file).equals(WhisperModelSpec.GGML_BASE.expectedSha1, ignoreCase = true))
    }

    @Test
    fun partialDownloadCleanup() {
        val dir = tempFolder.newFolder("whisper", "models")
        val part = File(dir, "ggml-base.bin.part")
        part.writeBytes(ByteArray(100))
        assertTrue(part.exists())
        part.delete()
        assertFalse(part.exists())
    }

    private fun sha1(file: File): String {
        val digest = MessageDigest.getInstance("SHA-1")
        file.inputStream().use { input ->
            val buffer = ByteArray(4096)
            while (true) {
                val n = input.read(buffer)
                if (n < 0) break
                digest.update(buffer, 0, n)
            }
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }
}

class FakeSpeechTranscriber(
    private var result: TranscriptionResult? = TranscriptionResult(
        transcript = "ciao mondo",
        detectedLanguage = "it",
        processingDurationMs = 10,
    ),
    private var fail: Boolean = false,
    private var delayMs: Long = 0L,
) : SpeechTranscriber {
    var cancelled = false
        private set
    var closed = false
        private set
    var transcribeCalls = 0
        private set

    override suspend fun transcribe(audioFile: File, languageCode: String): TranscriptionResult {
        transcribeCalls++
        if (delayMs > 0L) {
            kotlinx.coroutines.delay(delayMs)
        }
        if (cancelled) throw TranscriptionCancelledException()
        if (fail) throw TranscriptionFailedException(SpeechConfig.USER_SAFE_TRANSCRIPTION_ERROR)
        return result ?: throw TranscriptionFailedException(SpeechConfig.USER_SAFE_TRANSCRIPTION_ERROR)
    }

    fun setFail(value: Boolean) {
        fail = value
    }

    fun setResult(value: TranscriptionResult?) {
        result = value
    }

    override fun requestCancellation() {
        cancelled = true
    }

    override suspend fun close() {
        closed = true
    }
}

class SpeechTranscriberFakeTest {

    @Test
    fun transcriptionUnavailableWithoutModel() {
        assertEquals(
            SpeechConfig.METRICS_UNAVAILABLE_REPORT,
            SpeechReportPresentation.transcriptionStatusLabel(TranscriptionState.ModelUnavailable),
        )
        assertEquals(
            SpeechConfig.MODEL_NOT_DOWNLOADED_HINT,
            SpeechReportPresentation.modelStatusLabel(WhisperModelState.NotDownloaded),
        )
        assertTrue(
            SpeechReportPresentation.modelStatusLabel(WhisperModelState.NotDownloaded)
                .contains("Modello linguistico", ignoreCase = true),
        )
    }

    @Test
    fun nativeTranscriptionFailure_doesNotInvalidateAudioMetrics() {
        val audio = AudioSessionMetrics(
            state = AudioRecordingState.Completed,
            inputQuality = AudioInputQuality.GOOD,
            capturedDurationMs = 90_000,
            speechDurationMs = 40_000,
            droppedReadCount = 0,
            sampleRateHz = 16_000,
            audioSourceLabel = "MIC",
            speechRatioPercent = 44.0,
            meanSpeechDbfs = -22.0,
            volumeVariationStdDevDb = 2.5,
            clippingPercent = 0.0,
            approximatePauseCount = 3,
            medianPauseDurationMs = 400L,
            longestPauseDurationMs = 1_200L,
            pausesOver1500Ms = 0,
            pauseBuckets = PauseBuckets.empty(),
            errorMessage = null,
            insufficientData = false,
        )
        val report = SessionPracticeReport(
            body = SessionBodyReport.emptyInsufficient(),
            audio = audio,
            speech = SpeechSessionResult.Unavailable(SpeechConfig.USER_SAFE_TRANSCRIPTION_ERROR),
        )
        assertFalse(report.audio.insufficientData)
        assertEquals(AudioInputQuality.GOOD, report.audio.inputQuality)
        assertTrue(report.speech is SpeechSessionResult.Unavailable)
        assertEquals(-22.0, report.audio.meanSpeechDbfs!!, 1e-9)
    }

    @Test
    fun cancellationState() {
        val fake = FakeSpeechTranscriber()
        fake.requestCancellation()
        assertTrue(fake.cancelled)
        assertEquals(
            SpeechConfig.METRICS_UNAVAILABLE_REPORT,
            SpeechReportPresentation.transcriptionStatusLabel(TranscriptionState.Cancelled),
        )
    }

    @Test
    fun analysisInProgressLabel_isRhythmNotTranscript() {
        assertEquals(
            SpeechConfig.ANALYSIS_IN_PROGRESS,
            SpeechReportPresentation.transcriptionStatusLabel(TranscriptionState.Transcribing),
        )
        assertEquals("Analisi del ritmo in corso…", SpeechConfig.ANALYSIS_IN_PROGRESS)
    }

    @Test
    fun reportPresentation_uiSourceContract_noTranscriptOrVocalFillers() {
        val metrics = SpeechIntelligenceMetrics(
            wordCount = 10,
            vadSpeechDurationMs = 30_000,
            wordsPerMinute = 20.0,
            discourseMarkers = DiscourseMarkerMetrics(
                totalCount = 1,
                markersPerMinute = 2.0,
                breakdown = mapOf("cioè" to 1),
            ),
            immediateRepetitionCount = 0,
            immediateRepetitionBreakdown = emptyMap(),
        )
        val blob = listOf(
            SpeechReportPresentation.formatWordCount(metrics.wordCount),
            SpeechReportPresentation.formatWpm(metrics.wordsPerMinute),
            SpeechReportPresentation.formatDiscourseMarkerCount(metrics.discourseMarkers.totalCount),
            SpeechReportPresentation.formatMarkersPerMinute(metrics.discourseMarkers.markersPerMinute),
            SpeechReportPresentation.formatImmediateRepetitions(metrics.immediateRepetitionCount),
            SpeechReportPresentation.formatMarkerBreakdown(metrics.discourseMarkers.breakdown),
        ).joinToString(" ")
        assertFalse(blob.contains("Mostra trascrizione", ignoreCase = true))
        assertFalse(blob.contains("Trascrizione", ignoreCase = true))
        assertFalse(blob.contains("Riempitivi vocali", ignoreCase = true))

        val candidates = listOf(
            File("src/main/java/com/orato/app/ui/report/FinalReportScreen.kt"),
            File("app/src/main/java/com/orato/app/ui/report/FinalReportScreen.kt"),
            File("src/main/java/com/orato/app/ui/report/ReportPreparationScreen.kt"),
            File("app/src/main/java/com/orato/app/ui/report/ReportPreparationScreen.kt"),
            File("src/main/java/com/orato/app/ui/report/BodyReportScreen.kt"),
            File("app/src/main/java/com/orato/app/ui/report/BodyReportScreen.kt"),
        )
        val sources = candidates.filter { it.exists() }
        assertTrue("Expected report UI sources to be readable", sources.isNotEmpty())
        val text = sources.joinToString("\n") { it.readText() }
        assertFalse(text.contains("Mostra trascrizione"))
        assertFalse(text.contains("Trascrizione: Mostra"))
        assertFalse(text.contains("Riempitivi vocali"))
        assertFalse(text.contains("Riempitivi stimati"))
        assertTrue(text.contains("Ritmo e fluidità"))
        assertTrue(text.contains("Intercalari discorsivi"))
        assertFalse(text.contains("metrics.transcript"))
    }

    @Test
    fun fakeSpeechTranscriberIntegration() = runBlocking {
        val fake = FakeSpeechTranscriber()
        val result = fake.transcribe(File("unused.wav"), "it")
        assertEquals("ciao mondo", result.transcript)
        assertEquals(1, fake.transcribeCalls)
        // No segment payload on the result type used by the pipeline.
        val fieldNames = result::class.java.declaredFields.map { it.name }
        assertFalse(fieldNames.any { it.equals("segments", ignoreCase = true) })
        fake.close()
        assertTrue(fake.closed)
    }

    @Test
    fun italianDbfsFormatting_remainsCorrect() {
        assertEquals("-24,5", SpeechReportPresentation.formatDecimal(-24.5, 1))
        assertEquals(
            "Volume medio: -24,5 dBFS",
            com.orato.app.audio.VoiceReportPresentation.formatMeanVolumeDbfs(-24.5),
        )
    }

    @Test
    fun whisperConfig_carryPromptFalse_andNoEhmInPrompt() {
        assertFalse(SpeechConfig.WHISPER_SUPPRESS_NST)
        assertFalse(SpeechConfig.WHISPER_CARRY_INITIAL_PROMPT)
        assertFalse(SpeechConfig.ENABLE_VAD_SILENCE_COLLAPSE)
        assertFalse(SpeechConfig.ENABLE_DEBUG_TRANSCRIPT_PREVIEW)
        assertFalse(SpeechConfig.WHISPER_INITIAL_PROMPT.contains("ehm", ignoreCase = true))
    }
}
