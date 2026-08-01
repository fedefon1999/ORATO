package com.orato.app.report

import android.content.Context
import android.util.Log
import com.orato.app.audio.AudioSessionMetrics
import com.orato.app.metrics.SessionBodyReport
import com.orato.app.speech.SpeechConfig
import com.orato.app.speech.SpeechMetricsCalculator
import com.orato.app.speech.SpeechTranscriber
import com.orato.app.speech.TranscriptionCancelledException
import com.orato.app.speech.TranscriptionFailedException
import com.orato.app.speech.WhisperCppTranscriber
import com.orato.app.speech.WhisperModelManager
import com.orato.app.speech.WhisperModelReadiness
import java.io.File
import java.util.UUID
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

enum class ReportPreparationStage {
    FINALIZING_RECORDING,
    FINALIZING_BODY_ANALYSIS,
    FINALIZING_VOICE_ANALYSIS,
    PREPARING_AUDIO,
    ANALYZING_RHYTHM,
    CALCULATING_METRICS,
    BUILDING_REPORT,
}

sealed interface ReportPreparationState {
    data object Idle : ReportPreparationState

    data class Processing(
        val sessionId: String,
        val stage: ReportPreparationStage,
        val realProgress: Float?,
        val elapsedMs: Long,
    ) : ReportPreparationState

    data class Ready(
        val report: CompletedSessionReport,
    ) : ReportPreparationState

    data class Failed(
        val sessionId: String,
        val userSafeMessage: String,
        val recoverable: Boolean,
    ) : ReportPreparationState

    data object Cancelled : ReportPreparationState
}

object ReportPreparationTimeouts {
    const val WAV_FINALIZATION_MS: Long = 15_000L
    const val BODY_FINALIZATION_MS: Long = 5_000L
    const val VOICE_FINALIZATION_MS: Long = 5_000L
    const val WHISPER_INIT_MS: Long = 60_000L
    const val WHISPER_INFERENCE_MS: Long = 180_000L
    const val AGGREGATION_MS: Long = 10_000L
}

/**
 * Coordinates post-session finalization outside Composables.
 * Survives PracticeScreen disposal so Whisper is not cancelled by navigation.
 */
class ReportPreparationCoordinator private constructor(
    private val modelManagerFactory: () -> WhisperModelReadiness,
    private val transcriberFactory: (WhisperModelReadiness) -> SpeechTranscriber,
    private val clockMs: () -> Long,
) {
    constructor(
        context: Context,
        modelManagerFactory: (Context) -> WhisperModelManager = { WhisperModelManager(it) },
        transcriberFactory: (WhisperModelManager) -> SpeechTranscriber =
            { WhisperCppTranscriber(it) },
        clockMs: () -> Long = { System.currentTimeMillis() },
    ) : this(
        modelManagerFactory = { modelManagerFactory(context.applicationContext) },
        transcriberFactory = { readiness ->
            val manager = readiness as? WhisperModelManager
                ?: error("Production transcriber factory requires WhisperModelManager")
            transcriberFactory(manager)
        },
        clockMs = clockMs,
    )

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val mutex = Mutex()
    private val activeSessionId = AtomicReference<String?>(null)
    private var job: Job? = null
    private var elapsedTicker: Job? = null
    private var transcriber: SpeechTranscriber? = null
    private var startedAtMs: Long = 0L

    private val _state = MutableStateFlow<ReportPreparationState>(ReportPreparationState.Idle)
    val state: StateFlow<ReportPreparationState> = _state.asStateFlow()

    data class DebugTimings(
        val totalMs: Long = 0L,
        val metricsMs: Long = 0L,
        val aggregationMs: Long = 0L,
        val whisperProcessingMs: Long = 0L,
    )

    @Volatile
    var lastDebugTimings: DebugTimings? = null
        private set

    fun start(
        scenarioName: String,
        totalSessionDurationMs: Long,
        body: SessionBodyReport,
        audio: AudioSessionMetrics,
        wavFile: File?,
        modelReady: Boolean,
        sessionId: String = UUID.randomUUID().toString(),
    ) {
        scope.launch {
            mutex.withLock {
                cancelInternal(releaseTranscriber = true)
                activeSessionId.set(sessionId)
                startedAtMs = clockMs()
                emitProcessing(sessionId, ReportPreparationStage.FINALIZING_RECORDING, null)
                startElapsedTicker(sessionId)
                job = scope.launch {
                    runPipeline(
                        sessionId = sessionId,
                        scenarioName = scenarioName,
                        totalSessionDurationMs = totalSessionDurationMs,
                        body = body,
                        audio = audio,
                        wavFile = wavFile,
                        modelReady = modelReady,
                    )
                }
            }
        }
    }

    fun requestCancel() {
        scope.launch {
            mutex.withLock {
                val sid = activeSessionId.get()
                cancelInternal(releaseTranscriber = true)
                if (sid != null) {
                    _state.value = ReportPreparationState.Cancelled
                }
            }
        }
    }

    fun clear() {
        scope.launch {
            mutex.withLock {
                cancelInternal(releaseTranscriber = true)
                activeSessionId.set(null)
                _state.value = ReportPreparationState.Idle
            }
        }
    }

    private suspend fun runPipeline(
        sessionId: String,
        scenarioName: String,
        totalSessionDurationMs: Long,
        body: SessionBodyReport,
        audio: AudioSessionMetrics,
        wavFile: File?,
        modelReady: Boolean,
    ) {
        if (wavFile != null) {
            com.orato.app.audio.SessionWavRetention.retain(wavFile)
        }
        try {
            emitProcessing(sessionId, ReportPreparationStage.FINALIZING_RECORDING, 0.05f)
            delay(40)
            if (!isActiveSession(sessionId)) return

            emitProcessing(sessionId, ReportPreparationStage.FINALIZING_BODY_ANALYSIS, 0.15f)
            delay(40)
            if (!isActiveSession(sessionId)) return

            emitProcessing(sessionId, ReportPreparationStage.FINALIZING_VOICE_ANALYSIS, 0.25f)
            delay(40)
            if (!isActiveSession(sessionId)) return

            val linguistic = runLinguistic(
                sessionId = sessionId,
                audio = audio,
                wavFile = wavFile,
                modelReady = modelReady,
            )
            if (!isActiveSession(sessionId)) return

            emitProcessing(sessionId, ReportPreparationStage.CALCULATING_METRICS, 0.85f)
            val metricsStart = clockMs()
            // Metrics already computed inside runLinguistic when successful.
            val metricsMs = clockMs() - metricsStart
            if (!isActiveSession(sessionId)) return

            emitProcessing(sessionId, ReportPreparationStage.BUILDING_REPORT, 0.95f)
            val aggStart = clockMs()
            val report = CompletedSessionReportFactory.build(
                sessionId = sessionId,
                scenarioName = scenarioName,
                completedAtEpochMs = clockMs(),
                totalSessionDurationMs = totalSessionDurationMs,
                body = body,
                audio = audio,
                linguistic = linguistic.metrics,
                linguisticUnavailableMessage = linguistic.unavailableMessage,
                linguisticUnavailableReason = linguistic.reason,
            )
            val aggregationMs = clockMs() - aggStart
            if (!isActiveSession(sessionId)) return

            lastDebugTimings = DebugTimings(
                totalMs = clockMs() - startedAtMs,
                metricsMs = metricsMs,
                aggregationMs = aggregationMs,
                whisperProcessingMs = linguistic.whisperMs,
            )
            elapsedTicker?.cancel()
            _state.value = ReportPreparationState.Ready(report)
            PendingCompletedReport.set(report)
        } catch (_: TranscriptionCancelledException) {
            if (isActiveSession(sessionId)) {
                _state.value = ReportPreparationState.Cancelled
            }
        } catch (t: CancellationException) {
            // Job cancelled by a newer start / requestCancel — do not mark Failed.
            throw t
        } catch (t: Throwable) {
            logDebug("pipeline failed: ${t.javaClass.simpleName}")
            if (isActiveSession(sessionId)) {
                _state.value = ReportPreparationState.Failed(
                    sessionId = sessionId,
                    userSafeMessage = "Impossibile completare il report di questa sessione.",
                    recoverable = true,
                )
            }
        } finally {
            releaseTranscriber()
            com.orato.app.audio.SessionWavRetention.release(wavFile)
        }
    }

    private data class LinguisticOutcome(
        val metrics: com.orato.app.speech.SpeechIntelligenceMetrics?,
        val unavailableMessage: String?,
        val whisperMs: Long,
        val reason: com.orato.app.speech.LinguisticUnavailableReason? = null,
        val diagnostics: com.orato.app.speech.LinguisticDiagnostics? = null,
    )

    @Volatile
    var lastLinguisticDiagnostics: com.orato.app.speech.LinguisticDiagnostics? = null
        private set

    private suspend fun runLinguistic(
        sessionId: String,
        audio: AudioSessionMetrics,
        wavFile: File?,
        modelReady: Boolean,
    ): LinguisticOutcome {
        fun fail(
            reason: com.orato.app.speech.LinguisticUnavailableReason,
            whisperMs: Long = 0L,
            extra: com.orato.app.speech.LinguisticDiagnostics.() -> com.orato.app.speech.LinguisticDiagnostics = { this },
        ): LinguisticOutcome {
            val diag = com.orato.app.speech.LinguisticDiagnostics(
                reason = reason,
                sanitizedMessage = SpeechConfig.METRICS_UNAVAILABLE_REPORT,
                modelReady = modelReady,
                wavSizeBytes = wavFile?.takeIf { it.isFile }?.length(),
                sessionId = sessionId,
            ).extra()
            lastLinguisticDiagnostics = diag
            logDebug("linguistic unavailable reason=${reason.debugLabel} session=$sessionId")
            return LinguisticOutcome(
                metrics = null,
                unavailableMessage = SpeechConfig.METRICS_UNAVAILABLE_REPORT,
                whisperMs = whisperMs,
                reason = reason,
                diagnostics = diag,
            )
        }

        if (audio.insufficientData) {
            emitProcessing(sessionId, ReportPreparationStage.ANALYZING_RHYTHM, 0.7f)
            return fail(com.orato.app.speech.LinguisticUnavailableReason.INSUFFICIENT_AUDIO)
        }
        if (!modelReady) {
            emitProcessing(sessionId, ReportPreparationStage.ANALYZING_RHYTHM, 0.7f)
            return fail(com.orato.app.speech.LinguisticUnavailableReason.MODEL_NOT_DOWNLOADED)
        }
        if (wavFile == null) {
            emitProcessing(sessionId, ReportPreparationStage.ANALYZING_RHYTHM, 0.7f)
            return fail(com.orato.app.speech.LinguisticUnavailableReason.WAV_NOT_FOUND)
        }
        if (!wavFile.exists() || !wavFile.isFile) {
            emitProcessing(sessionId, ReportPreparationStage.ANALYZING_RHYTHM, 0.7f)
            return fail(com.orato.app.speech.LinguisticUnavailableReason.WAV_NOT_FOUND)
        }
        if (wavFile.length() <= 44L) {
            emitProcessing(sessionId, ReportPreparationStage.ANALYZING_RHYTHM, 0.7f)
            return fail(com.orato.app.speech.LinguisticUnavailableReason.WAV_INVALID)
        }

        emitProcessing(sessionId, ReportPreparationStage.PREPARING_AUDIO, 0.4f)
        val manager = modelManagerFactory()
        val ready = try {
            manager.ensureReadyFromDisk()
        } catch (_: Throwable) {
            false
        }
        if (!ready) {
            val reason = when {
                manager.isReady() -> com.orato.app.speech.LinguisticUnavailableReason.UNKNOWN
                else -> com.orato.app.speech.LinguisticUnavailableReason.MODEL_NOT_DOWNLOADED
            }
            return fail(reason)
        }

        val localTranscriber = transcriberFactory(manager)
        transcriber = localTranscriber

        emitProcessing(sessionId, ReportPreparationStage.ANALYZING_RHYTHM, 0.55f)
        val whisperStart = clockMs()
        var timedOut = false
        val result = try {
            withTimeoutOrNull(ReportPreparationTimeouts.WHISPER_INFERENCE_MS) {
                localTranscriber.transcribe(wavFile, SpeechConfig.DEFAULT_LANGUAGE_CODE)
            }.also { if (it == null) timedOut = true }
        } catch (_: TranscriptionCancelledException) {
            throw TranscriptionCancelledException()
        } catch (e: TranscriptionFailedException) {
            logDebug("whisper failed: ${e.userSafeMessage}")
            null
        } catch (t: Throwable) {
            logDebug("whisper failed: ${t.javaClass.simpleName}")
            null
        }
        val whisperMs = clockMs() - whisperStart

        if (!isActiveSession(sessionId)) {
            throw TranscriptionCancelledException()
        }

        if (result == null) {
            return fail(
                reason = if (timedOut) {
                    com.orato.app.speech.LinguisticUnavailableReason.INFERENCE_TIMEOUT
                } else {
                    com.orato.app.speech.LinguisticUnavailableReason.INFERENCE_FAILED
                },
                whisperMs = whisperMs,
            ) {
                copy(inferenceMs = whisperMs)
            }
        }

        emitProcessing(sessionId, ReportPreparationStage.CALCULATING_METRICS, 0.8f)
        if (result.transcript.isBlank()) {
            return fail(
                reason = com.orato.app.speech.LinguisticUnavailableReason.EMPTY_TRANSCRIPT,
                whisperMs = whisperMs,
            ) {
                copy(
                    inferenceMs = whisperMs,
                    transcriptCharCount = 0,
                )
            }
        }

        val metrics = try {
            SpeechMetricsCalculator.compute(
                transcript = result.transcript,
                vadSpeechDurationMs = audio.speechDurationMs ?: 0L,
                audio = audio,
            )
        } catch (t: Throwable) {
            logDebug("metrics failed: ${t.javaClass.simpleName}")
            null
        }

        return if (metrics == null) {
            fail(
                reason = com.orato.app.speech.LinguisticUnavailableReason.UNKNOWN,
                whisperMs = whisperMs,
            ) {
                copy(
                    inferenceMs = whisperMs,
                    transcriptCharCount = result.transcript.length,
                )
            }
        } else {
            lastLinguisticDiagnostics = com.orato.app.speech.LinguisticDiagnostics(
                reason = null,
                sanitizedMessage = null,
                modelReady = true,
                wavSizeBytes = wavFile.length(),
                inferenceMs = whisperMs,
                transcriptCharCount = result.transcript.length,
                sessionId = sessionId,
            )
            logDebug(
                "linguistic ok session=$sessionId chars=${result.transcript.length} " +
                    "words=${metrics.wordCount} inferMs=$whisperMs",
            )
            LinguisticOutcome(
                metrics = metrics,
                unavailableMessage = null,
                whisperMs = whisperMs,
            )
        }
    }

    private fun emitProcessing(
        sessionId: String,
        stage: ReportPreparationStage,
        progress: Float?,
    ) {
        if (!isActiveSession(sessionId)) return
        _state.value = ReportPreparationState.Processing(
            sessionId = sessionId,
            stage = stage,
            realProgress = progress,
            elapsedMs = (clockMs() - startedAtMs).coerceAtLeast(0L),
        )
    }

    private fun startElapsedTicker(sessionId: String) {
        elapsedTicker?.cancel()
        elapsedTicker = scope.launch {
            while (isActive && isActiveSession(sessionId)) {
                delay(250)
                val current = _state.value
                if (current is ReportPreparationState.Processing && current.sessionId == sessionId) {
                    _state.value = current.copy(elapsedMs = (clockMs() - startedAtMs).coerceAtLeast(0L))
                }
            }
        }
    }

    private fun isActiveSession(sessionId: String): Boolean =
        activeSessionId.get() == sessionId

    private fun cancelInternal(releaseTranscriber: Boolean) {
        elapsedTicker?.cancel()
        elapsedTicker = null
        job?.cancel()
        job = null
        try {
            transcriber?.requestCancellation()
        } catch (_: Throwable) {
        }
        if (releaseTranscriber) {
            // Fire-and-forget close on scope
            val t = transcriber
            transcriber = null
            if (t != null) {
                scope.launch {
                    try {
                        t.close()
                    } catch (_: Throwable) {
                    }
                }
            }
        }
    }

    private fun releaseTranscriber() {
        val t = transcriber
        transcriber = null
        if (t != null) {
            scope.launch {
                try {
                    t.close()
                } catch (_: Throwable) {
                }
            }
        }
    }

    private fun logDebug(message: String) {
        try {
            if (Log.isLoggable(TAG, Log.DEBUG)) {
                Log.d(TAG, message)
            }
        } catch (_: Throwable) {
            // android.util.Log is unavailable / unmocked on JVM unit tests.
        }
    }

    companion object {
        private const val TAG = "ReportPrepCoordinator"

        @Volatile
        private var instance: ReportPreparationCoordinator? = null

        fun get(context: Context): ReportPreparationCoordinator {
            val existing = instance
            if (existing != null) return existing
            return synchronized(this) {
                instance ?: ReportPreparationCoordinator(context).also { instance = it }
            }
        }

        /**
         * JVM unit-test factory — no Android [Context]; inject readiness + transcriber fakes.
         */
        fun forTests(
            modelManagerFactory: () -> WhisperModelReadiness,
            transcriberFactory: (WhisperModelReadiness) -> SpeechTranscriber,
            clockMs: () -> Long = { System.currentTimeMillis() },
        ): ReportPreparationCoordinator =
            ReportPreparationCoordinator(
                modelManagerFactory = modelManagerFactory,
                transcriberFactory = transcriberFactory,
                clockMs = clockMs,
            )

        /** Test-only: replace / clear singleton. */
        fun replaceForTests(coordinator: ReportPreparationCoordinator?) {
            synchronized(this) {
                instance = coordinator
            }
        }
    }
}

object PendingCompletedReport {
    private val _report = MutableStateFlow<CompletedSessionReport?>(null)
    val report: StateFlow<CompletedSessionReport?> = _report.asStateFlow()

    fun set(value: CompletedSessionReport) {
        _report.value = value
    }

    fun peek(): CompletedSessionReport? = _report.value

    fun clear() {
        _report.value = null
    }
}
