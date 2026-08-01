package com.orato.app.ui.practice

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.orato.app.audio.AudioRecorder
import com.orato.app.audio.AudioRecordingState
import com.orato.app.audio.AudioSessionMetrics
import com.orato.app.audio.LiveAudioDebug
import com.orato.app.audio.PendingSessionReport
import com.orato.app.audio.SessionPracticeReport
import com.orato.app.metrics.BodyMetricsEngine
import com.orato.app.metrics.LiveBodyMetrics
import com.orato.app.metrics.SessionBodyReport
import com.orato.app.pose.PoseDetectionStatus
import com.orato.app.pose.UpperBodyPoseFrame
import com.orato.app.speech.SpeechConfig
import com.orato.app.speech.SpeechMetricsCalculator
import com.orato.app.speech.SpeechSessionResult
import com.orato.app.speech.TranscriptionCancelledException
import com.orato.app.speech.TranscriptionFailedException
import com.orato.app.speech.TranscriptionState
import com.orato.app.speech.WhisperCppTranscriber
import com.orato.app.speech.WhisperModelManager
import com.orato.app.speech.WhisperModelState
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

private const val SESSION_DURATION_SECONDS = 90

data class PracticeUiState(
    val remainingSeconds: Int = SESSION_DURATION_SECONDS,
    val isRunning: Boolean = false,
    val isFinished: Boolean = false,
    val poseStatus: PoseDetectionStatus = PoseDetectionStatus.Initializing,
    val poseFrame: UpperBodyPoseFrame? = null,
    val liveMetrics: LiveBodyMetrics = LiveBodyMetrics(),
    val sessionReport: SessionBodyReport? = null,
    val audioDebug: LiveAudioDebug = LiveAudioDebug(),
    val audioMetrics: AudioSessionMetrics = AudioSessionMetrics.idle(),
    /** When false, body session remains usable but voice metrics are unavailable. */
    val microphoneAvailable: Boolean = true,
    val whisperModelState: WhisperModelState = WhisperModelState.Checking,
    val transcriptionState: TranscriptionState = TranscriptionState.NotRequested,
    val speechResult: SpeechSessionResult = SpeechSessionResult.NotAttempted,
) {
    val progress: Float
        get() = 1f - (remainingSeconds.toFloat() / SESSION_DURATION_SECONDS.toFloat())

    val formattedTime: String
        get() {
            val minutes = remainingSeconds / 60
            val seconds = remainingSeconds % 60
            return "%d:%02d".format(minutes, seconds)
        }

    val poseStatusLabel: String
        get() = when (val status = poseStatus) {
            PoseDetectionStatus.Initializing -> "Inizializzazione rilevamento..."
            PoseDetectionStatus.Detected -> "Posizione rilevata"
            PoseDetectionStatus.Insufficient -> "Posizionati davanti alla fotocamera"
            is PoseDetectionStatus.Error -> status.message
        }

    val audioState: AudioRecordingState
        get() = audioDebug.state
}

class PracticeViewModel(application: Application) : AndroidViewModel(application) {

    private val _uiState = MutableStateFlow(PracticeUiState())
    val uiState: StateFlow<PracticeUiState> = _uiState.asStateFlow()

    private val _sessionCompleted = MutableSharedFlow<SessionPracticeReport>(extraBufferCapacity = 1)
    val sessionCompleted: SharedFlow<SessionPracticeReport> = _sessionCompleted.asSharedFlow()

    private var timerJob: Job? = null
    private var transcriptionJob: Job? = null
    private val poseUpdatesEnabled = AtomicBoolean(true)
    private val metricsEngine = BodyMetricsEngine()
    private val audioRecorder = AudioRecorder(application.applicationContext)
    private val modelManager = WhisperModelManager(application.applicationContext)
    private val speechTranscriber = WhisperCppTranscriber(modelManager)

    private val finishing = AtomicBoolean(false)

    init {
        viewModelScope.launch {
            audioRecorder.liveDebug.collect { debug ->
                _uiState.update { it.copy(audioDebug = debug) }
            }
        }
        viewModelScope.launch {
            audioRecorder.metrics.collect { metrics ->
                _uiState.update { it.copy(audioMetrics = metrics) }
            }
        }
        viewModelScope.launch {
            modelManager.state.collect { modelState ->
                _uiState.update { it.copy(whisperModelState = modelState) }
            }
        }
        viewModelScope.launch {
            modelManager.refresh()
        }
    }

    /** Explicit user action — never called implicitly on screen entry. */
    fun downloadWhisperModel() {
        viewModelScope.launch {
            modelManager.download()
        }
    }

    fun cancelWhisperModelDownload() {
        modelManager.cancelDownload()
    }

    fun removeWhisperModel() {
        viewModelScope.launch {
            cancelTranscription()
            try {
                speechTranscriber.close()
            } catch (_: Throwable) {
            }
            modelManager.removeModel()
        }
    }

    fun onMicrophoneAvailabilityChanged(available: Boolean) {
        val wasAvailable = _uiState.value.microphoneAvailable
        _uiState.update { it.copy(microphoneAvailable = available) }
        if (!available && wasAvailable && _uiState.value.isRunning) {
            audioRecorder.markUnavailable("Permesso microfono revocato")
            viewModelScope.launch {
                audioRecorder.stop(completed = false)
            }
        }
    }

    fun onLeaveForeground() {
        val running = _uiState.value.isRunning
        val capturing = _uiState.value.audioState == AudioRecordingState.Recording ||
            _uiState.value.audioState == AudioRecordingState.Initializing
        if (running || capturing) {
            viewModelScope.launch {
                audioRecorder.stop(completed = false)
            }
            if (running) {
                resetSessionKeepingAudioStop()
            }
        }
    }

    fun startSession() {
        if (_uiState.value.isRunning || _uiState.value.isFinished) return

        finishing.set(false)
        metricsEngine.reset()
        cancelTranscription()

        _uiState.update {
            it.copy(
                remainingSeconds = SESSION_DURATION_SECONDS,
                isRunning = true,
                isFinished = false,
                liveMetrics = LiveBodyMetrics(),
                sessionReport = null,
                speechResult = SpeechSessionResult.NotAttempted,
                transcriptionState = TranscriptionState.NotRequested,
            )
        }

        viewModelScope.launch {
            audioRecorder.reset()
            if (_uiState.value.microphoneAvailable) {
                audioRecorder.start(microphonePermissionGranted = true)
            } else {
                audioRecorder.markUnavailable(
                    "Microfono non disponibile — metriche vocali assenti",
                )
            }
        }

        timerJob?.cancel()
        timerJob = viewModelScope.launch {
            while (isActive) {
                delay(1_000)
                val current = _uiState.value.remainingSeconds
                if (current <= 1) {
                    finishSession()
                    break
                } else {
                    _uiState.update { it.copy(remainingSeconds = current - 1) }
                }
            }
        }
    }

    fun resetSession() {
        timerJob?.cancel()
        timerJob = null
        finishing.set(false)
        cancelTranscription()
        metricsEngine.reset()
        metricsEngine.stopAccumulation()
        viewModelScope.launch {
            audioRecorder.reset()
        }
        restoreIdleUiPreservingPose()
    }

    private fun resetSessionKeepingAudioStop() {
        timerJob?.cancel()
        timerJob = null
        finishing.set(false)
        cancelTranscription()
        metricsEngine.reset()
        metricsEngine.stopAccumulation()
        viewModelScope.launch {
            audioRecorder.reset()
        }
        restoreIdleUiPreservingPose()
    }

    private fun restoreIdleUiPreservingPose() {
        val poseStatus = _uiState.value.poseStatus
        val poseFrame = _uiState.value.poseFrame
        val micOk = _uiState.value.microphoneAvailable
        val modelState = _uiState.value.whisperModelState
        _uiState.value = PracticeUiState(
            poseStatus = when (poseStatus) {
                is PoseDetectionStatus.Error -> poseStatus
                PoseDetectionStatus.Initializing -> PoseDetectionStatus.Initializing
                else -> poseStatus
            },
            poseFrame = poseFrame,
            liveMetrics = metricsEngine.liveMetrics(),
            sessionReport = null,
            microphoneAvailable = micOk,
            audioDebug = LiveAudioDebug(),
            audioMetrics = AudioSessionMetrics.idle(),
            whisperModelState = modelState,
            transcriptionState = TranscriptionState.NotRequested,
            speechResult = SpeechSessionResult.NotAttempted,
        )
    }

    private fun finishSession() {
        if (!finishing.compareAndSet(false, true)) return
        metricsEngine.stopAccumulation()
        val bodyReport = metricsEngine.buildReport()

        viewModelScope.launch {
            audioRecorder.stop(completed = true)
            val audioMetrics = audioRecorder.metrics.value
            val wav = audioRecorder.completedWavFile()

            val initialSpeech: SpeechSessionResult = when {
                !modelManager.isReady() ->
                    SpeechSessionResult.Unavailable(SpeechConfig.METRICS_UNAVAILABLE_REPORT)
                wav == null || audioMetrics.insufficientData ->
                    SpeechSessionResult.Unavailable(SpeechConfig.METRICS_UNAVAILABLE_REPORT)
                else ->
                    SpeechSessionResult.Processing(TranscriptionState.PreparingAudio)
            }

            val report = SessionPracticeReport(
                body = bodyReport,
                audio = audioMetrics,
                speech = initialSpeech,
            )
            _uiState.update {
                it.copy(
                    remainingSeconds = 0,
                    isRunning = false,
                    isFinished = true,
                    sessionReport = bodyReport,
                    audioMetrics = audioMetrics,
                    speechResult = initialSpeech,
                    transcriptionState = when (initialSpeech) {
                        is SpeechSessionResult.Processing -> TranscriptionState.PreparingAudio
                        is SpeechSessionResult.Unavailable -> TranscriptionState.ModelUnavailable
                        else -> TranscriptionState.NotRequested
                    },
                )
            }
            PendingSessionReport.set(report)
            _sessionCompleted.tryEmit(report)

            if (initialSpeech is SpeechSessionResult.Processing && wav != null) {
                startPostSessionTranscription(wav, audioMetrics)
            }
        }
    }

    private fun startPostSessionTranscription(
        wav: java.io.File,
        audioMetrics: AudioSessionMetrics,
    ) {
        transcriptionJob?.cancel()
        transcriptionJob = viewModelScope.launch {
            updateSpeech(
                SpeechSessionResult.Processing(TranscriptionState.Transcribing),
                TranscriptionState.Transcribing,
            )
            try {
                val result = speechTranscriber.transcribe(
                    audioFile = wav,
                    languageCode = SpeechConfig.DEFAULT_LANGUAGE_CODE,
                )
                val metrics = SpeechMetricsCalculator.compute(
                    transcript = result.transcript,
                    vadSpeechDurationMs = audioMetrics.speechDurationMs ?: 0L,
                    audio = audioMetrics,
                )
                val speech = if (metrics == null || result.transcript.isBlank()) {
                    SpeechSessionResult.Unavailable(SpeechConfig.METRICS_UNAVAILABLE_REPORT)
                } else {
                    SpeechSessionResult.Ready(metrics)
                }
                updateSpeech(
                    speech,
                    TranscriptionState.Completed(result),
                )
            } catch (_: TranscriptionCancelledException) {
                updateSpeech(
                    SpeechSessionResult.Unavailable(SpeechConfig.METRICS_UNAVAILABLE_REPORT),
                    TranscriptionState.Cancelled,
                )
            } catch (e: TranscriptionFailedException) {
                updateSpeech(
                    SpeechSessionResult.Unavailable(e.userSafeMessage),
                    TranscriptionState.Error(e.userSafeMessage),
                )
            } catch (_: Throwable) {
                updateSpeech(
                    SpeechSessionResult.Unavailable(SpeechConfig.USER_SAFE_TRANSCRIPTION_ERROR),
                    TranscriptionState.Error(SpeechConfig.USER_SAFE_TRANSCRIPTION_ERROR),
                )
            } finally {
                // Release native context after each session to free memory during camera use.
                try {
                    speechTranscriber.close()
                } catch (_: Throwable) {
                }
            }
        }
    }

    private fun updateSpeech(speech: SpeechSessionResult, transcriptionState: TranscriptionState) {
        _uiState.update {
            it.copy(speechResult = speech, transcriptionState = transcriptionState)
        }
        PendingSessionReport.updateSpeech(speech)
    }

    private fun cancelTranscription() {
        speechTranscriber.requestCancellation()
        transcriptionJob?.cancel()
        transcriptionJob = null
    }

    /** Called when leaving the report screen so in-flight work can stop. */
    fun onLeaveReport() {
        cancelTranscription()
    }

    fun onPoseStatus(status: PoseDetectionStatus) {
        if (!poseUpdatesEnabled.get()) return
        _uiState.update { state ->
            if (state.poseStatus is PoseDetectionStatus.Error && status !is PoseDetectionStatus.Error) {
                state
            } else {
                state.copy(poseStatus = status)
            }
        }
    }

    fun onPoseFrame(frame: UpperBodyPoseFrame) {
        if (!poseUpdatesEnabled.get()) return
        if (_uiState.value.poseStatus is PoseDetectionStatus.Error) return

        val live = metricsEngine.processFrame(frame)
        _uiState.update {
            it.copy(
                poseFrame = frame,
                poseStatus = if (live.validDetection) {
                    PoseDetectionStatus.Detected
                } else {
                    PoseDetectionStatus.Insufficient
                },
                liveMetrics = live,
            )
        }
    }

    fun consumeSessionReport(): SessionBodyReport? = _uiState.value.sessionReport

    override fun onCleared() {
        poseUpdatesEnabled.set(false)
        timerJob?.cancel()
        cancelTranscription()
        metricsEngine.release()
        audioRecorder.resetAsync()
        viewModelScope.launch {
            try {
                speechTranscriber.shutdown()
            } catch (_: Throwable) {
            }
        }
        super.onCleared()
    }
}
