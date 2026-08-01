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
        assertNull(empty.fillersPerMinute)
    }

    @Test
    fun fillersPerMinute_usesVadSpeechDuration() {
        val text = "ehm ciao mondo ehm prova"
        val metrics = SpeechMetricsCalculator.compute(text, 60_000L, audio(speechMs = 60_000L))
        assertEquals(2, metrics!!.fillerCount)
        assertEquals(2.0, metrics.fillersPerMinute!!, 1e-9)
    }

    @Test
    fun zeroSpeechDuration_fillersPerMinuteUnavailable() {
        assertNull(
            SpeechMetricsCalculator.fillersPerMinute(
                fillerCount = 3,
                vadSpeechDurationMs = 0L,
                transcriptEmpty = false,
                audioQualityValid = true,
            ),
        )
        val metrics = SpeechMetricsCalculator.compute("ehm ciao", 0L, audio(speechMs = 0L))
        assertNull(metrics!!.fillersPerMinute)
    }

    @Test
    fun wordCountRemainsCorrect_withFillersAndRepetitions() {
        val text = "il il problema ehm è chiaro"
        val metrics = SpeechMetricsCalculator.compute(text, 30_000L, audio(speechMs = 30_000L))
        assertEquals(6, metrics!!.wordCount)
        assertEquals(1, metrics.fillerCount)
        assertEquals(1, metrics.immediateRepetitionCount)
    }

    @Test
    fun metricsContainNoTranscriptField() {
        val metrics = SpeechMetricsCalculator.compute("ciao ehm mondo", 30_000L, audio())!!
        val names = metrics::class.java.declaredFields.map { it.name }
        assertFalse(names.any { it.equals("transcript", ignoreCase = true) })
        val presentation = SpeechReportPresentation.rhythmFluency(metrics)
        assertFalse(presentation.toString().contains("ciao"))
    }
}

class FillerDetectorTest {

    @Test
    fun ehm_detected() {
        assertEquals(1, FillerDetector.detect("ehm").fillerCount)
        assertEquals(1, FillerDetector.detect("ehm").breakdown["ehm"])
    }

    @Test
    fun eh_detected() {
        assertEquals(1, FillerDetector.detect("eh").fillerCount)
    }

    @Test
    fun em_detected() {
        assertEquals(1, FillerDetector.detect("em").fillerCount)
    }

    @Test
    fun repeatedLetterVocalVariants_normalized() {
        assertEquals(1, FillerDetector.detect("ehmm").fillerCount)
        assertEquals(1, FillerDetector.detect("ehmm").breakdown["ehm"])
        assertEquals(1, FillerDetector.detect("ehmmm").breakdown["ehm"])
        assertEquals(1, FillerDetector.detect("uhmm").breakdown["uhm"])
        assertEquals(1, FillerDetector.detect("mmmm").breakdown["mmm"])
    }

    @Test
    fun uhmUmMhmMmm_detected() {
        val result = FillerDetector.detect("uhm um mhm mmm")
        assertEquals(4, result.fillerCount)
        assertEquals(1, result.breakdown["uhm"])
        assertEquals(1, result.breakdown["um"])
        assertEquals(1, result.breakdown["mhm"])
        assertEquals(1, result.breakdown["mmm"])
    }

    @Test
    fun clearDiscourseFillers() {
        val result = FillerDetector.detect("cioè praticamente diciamo insomma")
        assertEquals(4, result.fillerCount)
    }

    @Test
    fun caseInsensitiveFillers() {
        assertEquals(5, FillerDetector.detect("EH Ehm UHM Cioè PRATICAMENTE").fillerCount)
    }

    @Test
    fun fillersFollowedByPunctuation() {
        assertEquals(4, FillerDetector.detect("eh, ehm! cioè? praticamente.").fillerCount)
    }

    @Test
    fun accentedCioe() {
        assertEquals(1, FillerDetector.detect("cioè").fillerCount)
        assertEquals(1, FillerDetector.detect("cioè").breakdown["cioè"])
    }

    @Test
    fun noSubstringFalsePositives() {
        assertEquals(0, FillerDetector.detect("Il tempo del sistema emmental è buono").fillerCount)
        assertEquals(0, FillerDetector.detect("il volume alto").fillerCount)
        assertEquals(0, FillerDetector.detect("perché sì").fillerCount)
    }

    @Test
    fun tipo_countedAsDiscourseMarker() {
        assertEquals(1, FillerDetector.detect("Tipo, potremmo iniziare domani.").fillerCount)
        assertEquals(1, FillerDetector.detect("Era, tipo, molto difficile.").fillerCount)
        assertEquals(1, FillerDetector.detect("Era, tipo, molto difficile.").breakdown["tipo"])
    }

    @Test
    fun tipo_nounConstruction_notCounted() {
        assertEquals(0, FillerDetector.detect("Ho scelto un tipo di pane.").fillerCount)
        assertEquals(0, FillerDetector.detect("un tipo di prodotto").fillerCount)
    }

    @Test
    fun allora_countedAtDiscourseStart() {
        assertEquals(1, FillerDetector.detect("Allora... quello che volevo dire è questo.").fillerCount)
        assertEquals(1, FillerDetector.detect("Allora, possiamo partire.").breakdown["allora"])
    }

    @Test
    fun daAllora_notCounted() {
        assertEquals(0, FillerDetector.detect("Da allora non è cambiato nulla.").fillerCount)
    }

    @Test
    fun contextualEcco() {
        assertEquals(1, FillerDetector.detect("Ecco, il problema principale è questo.").fillerCount)
        assertEquals(0, FillerDetector.detect("Ecco il documento richiesto.").fillerCount)
    }

    @Test
    fun contextualDunque() {
        assertEquals(1, FillerDetector.detect("Dunque, possiamo concludere.").fillerCount)
        assertEquals(0, FillerDetector.detect("Dunque il risultato matematico è corretto.").fillerCount)
    }

    @Test
    fun fillerBreakdown_aggregates() {
        val result = FillerDetector.detect("eh eh cioè ehm cioè")
        assertEquals(5, result.fillerCount)
        assertEquals(2, result.breakdown["eh"])
        assertEquals(2, result.breakdown["cioè"])
        assertEquals(1, result.breakdown["ehm"])
    }

    @Test
    fun genericWords_notClassifiedAsFillers() {
        assertEquals(0, FillerDetector.detect("questo mondo bello").fillerCount)
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
    fun fillerRepetitions_notDoubleCountedAsWordRepetitions() {
        val fillers = FillerDetector.detect("ehm ehm ciao")
        val reps = ImmediateRepetitionDetector.detect(
            "ehm ehm ciao",
            fillerTokenIndices = fillers.fillerTokenIndices,
        )
        assertEquals(2, fillers.fillerCount)
        assertEquals(0, reps.count)
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
) : SpeechTranscriber {
    var cancelled = false
        private set
    var closed = false
        private set
    var transcribeCalls = 0
        private set

    override suspend fun transcribe(audioFile: File, languageCode: String): TranscriptionResult {
        transcribeCalls++
        if (cancelled) throw TranscriptionCancelledException()
        if (fail) throw TranscriptionFailedException(SpeechConfig.USER_SAFE_TRANSCRIPTION_ERROR)
        return result ?: throw TranscriptionFailedException(SpeechConfig.USER_SAFE_TRANSCRIPTION_ERROR)
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
        assertTrue(
            SpeechReportPresentation.modelStatusLabel(WhisperModelState.NotDownloaded)
                .contains("modello offline"),
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
    fun reportPresentation_hasNoMostraTrascrizione() {
        val metrics = SpeechIntelligenceMetrics(
            wordCount = 10,
            vadSpeechDurationMs = 30_000,
            wordsPerMinute = 20.0,
            fillerCount = 1,
            fillersPerMinute = 2.0,
            fillerBreakdown = mapOf("ehm" to 1),
            immediateRepetitionCount = 0,
            immediateRepetitionBreakdown = emptyMap(),
        )
        val presentation = SpeechReportPresentation.rhythmFluency(metrics)
        val blob = listOf(
            presentation.wordCountLabel,
            presentation.wpmLabel,
            presentation.fillerCountLabel,
            presentation.fillersPerMinuteLabel,
            presentation.immediateRepetitionLabel,
            SpeechReportPresentation.formatFillerBreakdown(metrics.fillerBreakdown),
        ).joinToString(" ")
        assertFalse(blob.contains("Mostra trascrizione", ignoreCase = true))
        assertFalse(blob.contains("Trascrizione", ignoreCase = true))
        // Source UI must not expose transcript actions either.
        val candidates = listOf(
            java.io.File("src/main/java/com/orato/app/ui/report/BodyReportScreen.kt"),
            java.io.File("app/src/main/java/com/orato/app/ui/report/BodyReportScreen.kt"),
        )
        val uiSource = candidates.firstOrNull { it.exists() }
        assertNotNull("BodyReportScreen.kt should be readable for UI contract test", uiSource)
        val text = uiSource!!.readText()
        assertFalse(text.contains("Mostra trascrizione"))
        assertFalse(text.contains("Trascrizione: Mostra"))
        assertTrue(text.contains("Ritmo e fluidità"))
        assertFalse(text.contains("Ritmo e trascrizione"))
        assertFalse(text.contains("metrics.transcript"))
    }

    @Test
    fun fakeSpeechTranscriberIntegration() = runBlocking {
        val fake = FakeSpeechTranscriber()
        val result = fake.transcribe(File("unused.wav"), "it")
        assertEquals("ciao mondo", result.transcript)
        assertEquals(1, fake.transcribeCalls)
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
    fun whisperConfig_suppressNstFalseAndCarryPrompt() {
        assertFalse(SpeechConfig.WHISPER_SUPPRESS_NST)
        assertTrue(SpeechConfig.WHISPER_CARRY_INITIAL_PROMPT)
        assertFalse(SpeechConfig.ENABLE_VAD_SILENCE_COLLAPSE)
        assertFalse(SpeechConfig.ENABLE_DEBUG_TRANSCRIPT_PREVIEW)
        assertTrue(SpeechConfig.WHISPER_INITIAL_PROMPT.contains("ehm"))
    }
}
