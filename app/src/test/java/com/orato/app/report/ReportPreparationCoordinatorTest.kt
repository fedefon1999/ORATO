package com.orato.app.report

import com.orato.app.audio.AudioInputQuality
import com.orato.app.audio.AudioRecordingState
import com.orato.app.audio.AudioSessionMetrics
import com.orato.app.audio.PauseBuckets
import com.orato.app.metrics.SessionBodyReport
import com.orato.app.speech.FakeSpeechTranscriber
import com.orato.app.speech.SpeechConfig
import com.orato.app.speech.SpeechTranscriber
import com.orato.app.speech.TranscriptionResult
import com.orato.app.speech.WhisperModelReadiness
import java.util.concurrent.atomic.AtomicLong
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class FakeWhisperModelManager(
    private val ready: Boolean = true,
) : WhisperModelReadiness {
    override fun isReady(): Boolean = ready
}

class ReportPreparationCoordinatorTest {

    @get:Rule
    val tempFolder = TemporaryFolder()

    private val clock = AtomicLong(1_000_000L)

    private fun audio(
        speechMs: Long? = 60_000L,
        longestSpeechMs: Long? = 12_000L,
        buckets: PauseBuckets = PauseBuckets.fromDurations(listOf(300L, 800L, 1_800L)),
        insufficient: Boolean = false,
    ): AudioSessionMetrics =
        AudioSessionMetrics(
            state = AudioRecordingState.Completed,
            inputQuality = if (insufficient) AudioInputQuality.INSUFFICIENT_AUDIO else AudioInputQuality.GOOD,
            capturedDurationMs = 90_000L,
            speechDurationMs = speechMs,
            longestSpeechSegmentMs = longestSpeechMs,
            droppedReadCount = 0,
            sampleRateHz = 16_000,
            audioSourceLabel = "MIC",
            speechRatioPercent = 66.0,
            meanSpeechDbfs = -20.0,
            volumeVariationStdDevDb = 2.0,
            clippingPercent = 0.0,
            approximatePauseCount = buckets.rawCount,
            medianPauseDurationMs = 800L,
            longestPauseDurationMs = 1_800L,
            pausesOver1500Ms = buckets.longCount,
            pauseBuckets = buckets,
            errorMessage = null,
            insufficientData = insufficient,
        )

    private fun coordinator(
        modelReady: Boolean = true,
        transcriber: SpeechTranscriber,
    ): ReportPreparationCoordinator {
        val model = FakeWhisperModelManager(ready = modelReady)
        return ReportPreparationCoordinator.forTests(
            modelManagerFactory = { model },
            transcriberFactory = { transcriber },
            clockMs = { clock.get() },
        )
    }

    private suspend fun awaitReady(
        coordinator: ReportPreparationCoordinator,
        timeoutMs: Long = 8_000L,
    ): ReportPreparationState.Ready =
        withTimeout(timeoutMs) {
            while (true) {
                when (val s = coordinator.state.value) {
                    is ReportPreparationState.Ready -> return@withTimeout s
                    is ReportPreparationState.Failed -> error("unexpected Failed: ${s.userSafeMessage}")
                    ReportPreparationState.Cancelled -> error("unexpected Cancelled")
                    else -> delay(25)
                }
            }
            error("unreachable")
        }

    private suspend fun awaitClosed(
        fake: FakeSpeechTranscriber,
        timeoutMs: Long = 5_000L,
    ) {
        withTimeout(timeoutMs) {
            while (!fake.closed) delay(20)
        }
    }

    @Test
    fun whisperSuccess_readyWithLinguisticAvailable() = runBlocking {
        val wav = tempFolder.newFile("ok.wav")
        wav.writeBytes(ByteArray(0))
        val fake = FakeSpeechTranscriber(
            result = TranscriptionResult(
                transcript = "cioè ciao mondo praticamente",
                detectedLanguage = "it",
                processingDurationMs = 5,
            ),
        )
        val c = coordinator(transcriber = fake)
        c.start(
            scenarioName = "Demo",
            totalSessionDurationMs = 90_000L,
            body = SessionBodyReport.emptyInsufficient(),
            audio = audio(),
            wavFile = wav,
            modelReady = true,
            sessionId = "s-success",
        )
        val ready = awaitReady(c)
        assertEquals("s-success", ready.report.sessionId)
        assertTrue(ready.report.rhythmAndFluency.linguisticAvailable)
        assertNotNull(ready.report.rhythmAndFluency.wordCount)
        assertTrue(ready.report.rhythmAndFluency.wordCount!! > 0)
        assertNotNull(ready.report.rhythmAndFluency.discourseMarkers)
        assertTrue(ready.report.rhythmAndFluency.discourseMarkers!!.totalCount >= 2)
        assertFalse(ReportSection.RHYTHM_AND_FLUENCY in ready.report.unavailableSections)
        assertEquals(-20.0, ready.report.voice.meanSpeechDbfs!!, 1e-9)
        assertTrue(ready.report.body.session.hasInsufficientData)
    }

    @Test
    fun whisperFailure_readyWithLinguisticUnavailable_bodyVoicePreserved() = runBlocking {
        val wav = tempFolder.newFile("fail.wav")
        wav.writeBytes(ByteArray(0))
        val fake = FakeSpeechTranscriber(fail = true)
        val c = coordinator(transcriber = fake)
        val body = SessionBodyReport.emptyInsufficient()
        val audioMetrics = audio()
        c.start(
            scenarioName = "Demo",
            totalSessionDurationMs = 90_000L,
            body = body,
            audio = audioMetrics,
            wavFile = wav,
            modelReady = true,
            sessionId = "s-fail",
        )
        val ready = awaitReady(c)
        assertFalse(ready.report.rhythmAndFluency.linguisticAvailable)
        assertEquals(
            SpeechConfig.METRICS_UNAVAILABLE_REPORT,
            ready.report.rhythmAndFluency.linguisticUnavailableMessage,
        )
        assertTrue(ReportSection.RHYTHM_AND_FLUENCY in ready.report.unavailableSections)
        assertEquals(audioMetrics.meanSpeechDbfs, ready.report.voice.meanSpeechDbfs)
        assertEquals(audioMetrics.speechRatioPercent, ready.report.voice.speechRatioPercent)
        assertFalse(ready.report.voice.insufficientData)
        assertEquals(body.hasInsufficientData, ready.report.body.session.hasInsufficientData)
    }

    @Test
    fun duplicateStart_replacesOld_noStaleReady() = runBlocking {
        val wav = tempFolder.newFile("dup.wav")
        wav.writeBytes(ByteArray(0))
        val slow = FakeSpeechTranscriber(
            result = TranscriptionResult("vecchia sessione cioè", "it", 5),
            delayMs = 2_000L,
        )
        val fast = FakeSpeechTranscriber(
            result = TranscriptionResult("nuova sessione praticamente", "it", 5),
        )
        var useSlow = true
        val model = FakeWhisperModelManager(true)
        val c = ReportPreparationCoordinator.forTests(
            modelManagerFactory = { model },
            transcriberFactory = {
                if (useSlow) slow else fast
            },
            clockMs = { clock.get() },
        )
        c.start(
            scenarioName = "A",
            totalSessionDurationMs = 60_000L,
            body = SessionBodyReport.emptyInsufficient(),
            audio = audio(),
            wavFile = wav,
            modelReady = true,
            sessionId = "old",
        )
        delay(80)
        useSlow = false
        c.start(
            scenarioName = "B",
            totalSessionDurationMs = 60_000L,
            body = SessionBodyReport.emptyInsufficient(),
            audio = audio(),
            wavFile = wav,
            modelReady = true,
            sessionId = "new",
        )
        val ready = awaitReady(c)
        assertEquals("new", ready.report.sessionId)
        assertEquals("B", ready.report.scenarioName)
        // Stale "old" must not overwrite after "new" completed.
        delay(2_500L)
        val still = c.state.value
        assertTrue(still is ReportPreparationState.Ready)
        assertEquals("new", (still as ReportPreparationState.Ready).report.sessionId)
    }

    @Test
    fun staleSession_ignoredAfterCancel() = runBlocking {
        val wav = tempFolder.newFile("stale.wav")
        wav.writeBytes(ByteArray(0))
        val slow = FakeSpeechTranscriber(
            result = TranscriptionResult("dovrebbe essere ignorata", "it", 5),
            delayMs = 1_500L,
        )
        val c = coordinator(transcriber = slow)
        c.start(
            scenarioName = "Stale",
            totalSessionDurationMs = 60_000L,
            body = SessionBodyReport.emptyInsufficient(),
            audio = audio(),
            wavFile = wav,
            modelReady = true,
            sessionId = "stale-1",
        )
        delay(60)
        c.requestCancel()
        withTimeout(5_000L) {
            while (c.state.value !is ReportPreparationState.Cancelled) delay(20)
        }
        delay(2_000L)
        assertTrue(c.state.value is ReportPreparationState.Cancelled)
    }

    @Test
    fun cancellation_releasesTranscriber() = runBlocking {
        val wav = tempFolder.newFile("cancel.wav")
        wav.writeBytes(ByteArray(0))
        val slow = FakeSpeechTranscriber(
            result = TranscriptionResult("ciao", "it", 5),
            delayMs = 5_000L,
        )
        val c = coordinator(transcriber = slow)
        c.start(
            scenarioName = "Cancel",
            totalSessionDurationMs = 60_000L,
            body = SessionBodyReport.emptyInsufficient(),
            audio = audio(),
            wavFile = wav,
            modelReady = true,
            sessionId = "c1",
        )
        // Wait until Whisper is actually running so cancelInternal has a transcriber to release.
        withTimeout(5_000L) {
            while (slow.transcribeCalls == 0) delay(20)
        }
        c.requestCancel()
        awaitClosed(slow)
        assertTrue(slow.cancelled)
        assertTrue(slow.closed)
    }

    @Test
    fun completedSessionReport_hasNoTranscriptField() = runBlocking {
        val wav = tempFolder.newFile("no-tx.wav")
        wav.writeBytes(ByteArray(0))
        val fake = FakeSpeechTranscriber(
            result = TranscriptionResult("testo segreto della trascrizione", "it", 5),
        )
        val c = coordinator(transcriber = fake)
        c.start(
            scenarioName = "NoTx",
            totalSessionDurationMs = 60_000L,
            body = SessionBodyReport.emptyInsufficient(),
            audio = audio(),
            wavFile = wav,
            modelReady = true,
            sessionId = "no-tx",
        )
        val ready = awaitReady(c)
        val names = ready.report::class.java.declaredFields.map { it.name }
        assertFalse(names.any { it.equals("transcript", ignoreCase = true) })
        val rhythmNames = ready.report.rhythmAndFluency::class.java.declaredFields.map { it.name }
        assertFalse(rhythmNames.any { it.equals("transcript", ignoreCase = true) })
        assertFalse(ready.report.toString().contains("testo segreto"))
    }

    @Test
    fun factory_significantPausesPerMinute_andLongestContinuousSpeech() {
        val buckets = PauseBuckets.fromDurations(listOf(300L, 800L, 1_800L))
        // significant = medium(800) + long(1800) = 2; speech 60s → 2/min
        val report = CompletedSessionReportFactory.build(
            sessionId = "factory",
            scenarioName = "Pauses",
            completedAtEpochMs = 1L,
            totalSessionDurationMs = 90_000L,
            body = SessionBodyReport.emptyInsufficient(),
            audio = audio(
                speechMs = 60_000L,
                longestSpeechMs = 12_345L,
                buckets = buckets,
            ),
            linguistic = null,
            linguisticUnavailableMessage = SpeechConfig.METRICS_UNAVAILABLE_REPORT,
        )
        assertEquals(2, report.rhythmAndFluency.significantPauseCount)
        assertEquals(2.0, report.rhythmAndFluency.significantPausesPerMinute!!, 1e-9)
        assertEquals(12_345L, report.rhythmAndFluency.longestContinuousSpeechMs)
    }

    @Test
    fun aiCoachingNotAvailable_meansSectionHidden() {
        val report = CompletedSessionReportFactory.build(
            sessionId = "ai",
            scenarioName = "AI",
            completedAtEpochMs = 1L,
            totalSessionDurationMs = 30_000L,
            body = SessionBodyReport.emptyInsufficient(),
            audio = audio(),
            linguistic = null,
        )
        assertTrue(report.aiCoachingState is AiCoachingState.NotAvailable)
        // Mirrors FinalReportScreen AiCoachingSection: NotAvailable → no Consigli card.
        val showCoaching = when (report.aiCoachingState) {
            is AiCoachingState.Ready -> true
            AiCoachingState.NotAvailable,
            AiCoachingState.Loading,
            is AiCoachingState.Error,
            -> false
        }
        assertFalse(showCoaching)
    }

    @Test
    fun modelNotReady_linguisticUnavailable() = runBlocking {
        val wav = tempFolder.newFile("nomodel.wav")
        wav.writeBytes(ByteArray(0))
        val fake = FakeSpeechTranscriber()
        val c = coordinator(modelReady = false, transcriber = fake)
        c.start(
            scenarioName = "NoModel",
            totalSessionDurationMs = 60_000L,
            body = SessionBodyReport.emptyInsufficient(),
            audio = audio(),
            wavFile = wav,
            modelReady = true, // pipeline arg true, but manager.isReady() false
            sessionId = "no-model",
        )
        val ready = awaitReady(c)
        assertFalse(ready.report.rhythmAndFluency.linguisticAvailable)
        assertEquals(0, fake.transcribeCalls)
    }
}
