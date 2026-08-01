package com.orato.app.speech

import android.os.ParcelFileDescriptor
import com.orato.app.audio.AudioInputQuality
import com.orato.app.audio.AudioRecordingState
import com.orato.app.audio.AudioSessionMetrics
import com.orato.app.audio.PauseBuckets
import java.util.Locale
import kotlin.math.abs
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

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
        val tokens = TranscriptTokenizer.tokenize("L'amico dell'insegnante")
        assertEquals(listOf("L'amico", "dell'insegnante"), tokens)
        assertEquals(2, tokens.size)

        val curly = TranscriptTokenizer.tokenize("L’acqua")
        assertEquals(1, curly.size)
        assertEquals("L’acqua", curly[0])
    }

    @Test
    fun accentedCharacters_preservedAsWords() {
        val tokens = TranscriptTokenizer.tokenize("perché città agilità")
        assertEquals(listOf("perché", "città", "agilità"), tokens)
        assertEquals(3, tokens.size)
    }

    @Test
    fun multipleSpacesAndNewlines_ignored() {
        val text = "ciao   mondo\n\ncome\tstai"
        assertEquals(listOf("ciao", "mondo", "come", "stai"), TranscriptTokenizer.tokenize(text))
        assertEquals(4, TranscriptTokenizer.wordCount(text))
    }

    @Test
    fun correctWordCount_mixedPunctuation() {
        val text = "Buongiorno, come stai? Bene: grazie!"
        assertEquals(5, TranscriptTokenizer.wordCount(text))
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
        // 120 words in 60_000 ms speech → 120 WPM
        val words = (1..120).joinToString(" ") { "parola" }
        val metrics = SpeechMetricsCalculator.compute(
            transcript = words,
            vadSpeechDurationMs = 60_000L,
            audio = audio(speechMs = 60_000L, capturedMs = 90_000L),
        )
        assertNotNull(metrics)
        assertEquals(120, metrics!!.wordCount)
        assertEquals(120.0, metrics.wordsPerMinute!!, 1e-9)
    }

    @Test
    fun wpm_doesNotUseTotalCapturedDuration() {
        // 60 words, 30 s speech, 90 s capture.
        // If wrongly using capture: 60 / 1.5 = 40 WPM
        // Correct with speech: 60 / 0.5 = 120 WPM
        val words = (1..60).joinToString(" ") { "parola" }
        val metrics = SpeechMetricsCalculator.compute(
            transcript = words,
            vadSpeechDurationMs = 30_000L,
            audio = audio(speechMs = 30_000L, capturedMs = 90_000L),
        )
        assertNotNull(metrics)
        assertEquals(120.0, metrics!!.wordsPerMinute!!, 1e-9)
        assertFalse(abs(metrics.wordsPerMinute!! - 40.0) < 1e-6)
    }

    @Test
    fun zeroSpeechDuration_returnsUnavailableWpm() {
        val wpm = SpeechMetricsCalculator.wordsPerMinute(
            wordCount = 10,
            vadSpeechDurationMs = 0L,
            transcriptEmpty = false,
            audioQualityValid = true,
        )
        assertNull(wpm)

        val metrics = SpeechMetricsCalculator.compute(
            transcript = "ciao mondo",
            vadSpeechDurationMs = 0L,
            audio = audio(speechMs = 0L),
        )
        assertNotNull(metrics)
        assertNull(metrics!!.wordsPerMinute)
    }

    @Test
    fun emptyTranscript_nullMetricsFromNullInput_andZeroWords() {
        assertNull(
            SpeechMetricsCalculator.compute(
                transcript = null,
                vadSpeechDurationMs = 30_000L,
                audio = audio(),
            ),
        )
        val empty = SpeechMetricsCalculator.compute(
            transcript = "",
            vadSpeechDurationMs = 30_000L,
            audio = audio(),
        )
        assertNotNull(empty)
        assertEquals(0, empty!!.wordCount)
        assertNull(empty.wordsPerMinute)
    }

    @Test
    fun invalidAudioQuality_unavailableWpm() {
        val words = (1..20).joinToString(" ") { "parola" }
        val metrics = SpeechMetricsCalculator.compute(
            transcript = words,
            vadSpeechDurationMs = 30_000L,
            audio = audio(quality = AudioInputQuality.INSUFFICIENT_AUDIO, insufficient = true),
        )
        assertNotNull(metrics)
        assertNull(metrics!!.wordsPerMinute)
    }
}

class FillerDetectorTest {

    @Test
    fun eachVocalFiller_detected() {
        for (filler in FillerDetector.VOCAL_FILLERS) {
            val result = FillerDetector.detect(filler)
            assertEquals("failed for $filler", 1, result.fillerCount)
            assertEquals(1, result.breakdown[filler])
        }
    }

    @Test
    fun eachDiscourseFiller_detected() {
        for (filler in FillerDetector.DISCOURSE_FILLERS) {
            val result = FillerDetector.detect(filler)
            assertEquals("failed for $filler", 1, result.fillerCount)
            assertEquals(1, result.breakdown[filler])
        }
    }

    @Test
    fun caseInsensitiveFillers() {
        val result = FillerDetector.detect("EH Ehm UHM Cioè PRATICAMENTE")
        assertEquals(5, result.fillerCount)
    }

    @Test
    fun fillersFollowedByPunctuation() {
        val result = FillerDetector.detect("eh, ehm! cioè? praticamente.")
        assertEquals(4, result.fillerCount)
    }

    @Test
    fun noSubstringFalsePositives() {
        // "em" must not match inside "tempo" / "sistema" / "emmental"
        val result = FillerDetector.detect("Il tempo del sistema emmental è buono")
        assertEquals(0, result.fillerCount)

        // "um" must not match inside "volume"
        assertEquals(0, FillerDetector.detect("il volume alto").fillerCount)

        // "eh" must not match inside "perché"
        assertEquals(0, FillerDetector.detect("perché sì").fillerCount)
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
        assertEquals(0, FillerDetector.detect("tipo allora questo").fillerCount)
    }
}

class Pcm16ResamplerTest {

    @Test
    fun passthrough_16kHz() {
        val resampler = Pcm16Resampler(16_000)
        val input = ShortArray(320) { it.toShort() }
        val out = resampler.process(input)
        assertEquals(input.toList(), out.toList())
        assertEquals(0, resampler.flush().size)
        assertEquals(320L, resampler.outputSampleCount)
    }

    @Test
    fun resample_48k_to_16k() {
        assertResampleDuration(48_000, inputSeconds = 1.0)
    }

    @Test
    fun resample_44100_to_16k() {
        assertResampleDuration(44_100, inputSeconds = 1.0)
    }

    @Test
    fun resample_22050_to_16k() {
        assertResampleDuration(22_050, inputSeconds = 1.0)
    }

    @Test
    fun outputDurationTolerance() {
        assertResampleDuration(48_000, inputSeconds = 0.5, toleranceMs = 20.0)
        assertResampleDuration(44_100, inputSeconds = 2.0, toleranceMs = 25.0)
    }

    @Test
    fun resamplerFinalFlush_emitsRemaining() {
        val resampler = Pcm16Resampler(48_000)
        // Odd-sized chunk so fractional cursor remains.
        val chunk = ShortArray(100) { 1000 }
        resampler.process(chunk)
        val flushed = resampler.flush()
        // After flush, duration should be close to input duration.
        val expectedMs = 100.0 * 1000.0 / 48_000.0
        val actualMs = resampler.actualOutputDurationMs()
        assertTrue(
            "expected≈$expectedMs actual=$actualMs flush=${flushed.size}",
            abs(actualMs - expectedMs) < 5.0,
        )
    }

    @Test
    fun doesNotMutateInput() {
        val input = ShortArray(480) { 42 }
        val copy = input.copyOf()
        Pcm16Resampler(48_000).process(input)
        assertEquals(copy.toList(), input.toList())
    }

    private fun assertResampleDuration(
        inputRate: Int,
        inputSeconds: Double,
        toleranceMs: Double = 20.0,
    ) {
        val resampler = Pcm16Resampler(inputRate)
        val totalSamples = (inputRate * inputSeconds).toInt()
        // Process in 20 ms frames.
        val frame = inputRate / 50
        var offset = 0
        while (offset < totalSamples) {
            val len = minOf(frame, totalSamples - offset)
            val chunk = ShortArray(len) { 500 }
            resampler.process(chunk)
            offset += len
        }
        resampler.flush()
        val expectedMs = inputSeconds * 1000.0
        val actualMs = resampler.actualOutputDurationMs()
        assertTrue(
            "rate=$inputRate expected=${expectedMs}ms actual=${actualMs}ms",
            abs(actualMs - expectedMs) <= toleranceMs,
        )
    }
}

class SpeechReportPresentationTest {

    @Test
    fun italianDecimalFormatting_remainsCorrect() {
        val text = SpeechReportPresentation.formatDecimal(-24.5, 1)
        assertEquals("-24,5", text)
        assertEquals(Locale.ITALY, Locale.ITALY)
        // Also verify existing voice formatter stays Italian.
        val dbfs = com.orato.app.audio.VoiceReportPresentation.formatMeanVolumeDbfs(-24.5)
        assertEquals("Volume medio: -24,5 dBFS", dbfs)
    }

    @Test
    fun wpmRoundedForDisplay_domainKeepsDouble() {
        assertEquals("132 parole/min", SpeechReportPresentation.formatWpm(132.4))
        assertEquals("133 parole/min", SpeechReportPresentation.formatWpm(132.6))
        assertEquals("—", SpeechReportPresentation.formatWpm(null))
    }
}

class FakeSpeechTranscriber(
    private var availability: TranscriptionAvailability = TranscriptionAvailability.Ready,
) : SpeechTranscriber {
    var prepareCalled = false
        private set
    var startCalled = false
        private set
    var stopCalled = false
        private set
    var closed = false
        private set
    var failRecognition = false

    fun setAvailability(value: TranscriptionAvailability) {
        availability = value
    }

    override suspend fun checkAvailability(): TranscriptionAvailability = availability

    override suspend fun prepareModel(): TranscriptionAvailability {
        prepareCalled = true
        if (availability is TranscriptionAvailability.DownloadRequired) {
            availability = TranscriptionAvailability.Ready
        }
        return availability
    }

    override fun startRecognition(readPfd: ParcelFileDescriptor): Flow<TranscriptUpdate> {
        startCalled = true
        return flow {
            if (failRecognition) {
                emit(TranscriptUpdate.Failed(SpeechConfig.USER_SAFE_RECOGNITION_ERROR))
            } else {
                emit(TranscriptUpdate.Partial("ciao"))
                emit(TranscriptUpdate.Final("ciao mondo"))
                emit(TranscriptUpdate.Completed)
            }
        }
    }

    override suspend fun stop() {
        stopCalled = true
    }

    override fun close() {
        closed = true
    }
}

class SpeechTranscriberFakeTest {

    @Test
    fun unavailableTranscriptionState() {
        val fake = FakeSpeechTranscriber(
            TranscriptionAvailability.Unavailable(UnavailableReason.UnsupportedApiLevel),
        )
        // Synchronous check via runBlocking-free: just assert mapping helpers.
        val availability = TranscriptionAvailability.Unavailable(UnavailableReason.FeatureUnavailable)
        assertTrue(availability is TranscriptionAvailability.Unavailable)
        assertEquals(
            SpeechConfig.USER_SAFE_UNAVAILABLE,
            SpeechReportPresentation.statusLabel(
                TranscriptionState.Unavailable(UnavailableReason.DeviceUnsupported),
            ),
        )
    }

    @Test
    fun recognitionError_doesNotInvalidateAudioMetrics() {
        // Audio metrics remain valid independently of transcription failure.
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
        val speech = SpeechSessionResult.Unavailable(SpeechConfig.USER_SAFE_RECOGNITION_ERROR)
        // Combined report keeps audio intact.
        val report = com.orato.app.audio.SessionPracticeReport(
            body = com.orato.app.metrics.SessionBodyReport.emptyInsufficient(),
            audio = audio,
            speech = speech,
        )
        assertFalse(report.audio.insufficientData)
        assertEquals(AudioInputQuality.GOOD, report.audio.inputQuality)
        assertTrue(report.speech is SpeechSessionResult.Unavailable)
        // Computing metrics with null transcript yields null — audio untouched.
        assertNull(
            SpeechMetricsCalculator.compute(
                transcript = null,
                vadSpeechDurationMs = audio.speechDurationMs ?: 0L,
                audio = audio,
            ),
        )
        assertEquals(90_000L, audio.capturedDurationMs)
        assertEquals(40_000L, audio.speechDurationMs)
    }
}
