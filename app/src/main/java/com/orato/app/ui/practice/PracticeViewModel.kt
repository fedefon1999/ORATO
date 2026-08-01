package com.orato.app.ui.practice

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.orato.app.audio.AudioRecorder
import com.orato.app.audio.AudioRecordingState
import com.orato.app.audio.AudioSessionMetrics
import com.orato.app.audio.LiveAudioDebug
import com.orato.app.metrics.BodyMetricsEngine
import com.orato.app.metrics.LiveBodyMetrics
import com.orato.app.metrics.SessionBodyReport
import com.orato.app.pose.PoseDetectionStatus
import com.orato.app.pose.UpperBodyPoseFrame
import com.orato.app.report.ReportPreparationCoordinator
import com.orato.app.speech.WhisperModelManager
import com.orato.app.speech.WhisperModelState
import java.util.UUID
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

/** Navigation signal after timer finalization — never carries a partial report. */
data class SessionEndedNavigation(
    val sessionId: String,
    val scenarioRouteArg: String,
)

class PracticeViewModel(application: Application) : AndroidViewModel(application) {

    private val _uiState = MutableStateFlow(PracticeUiState())
    val uiState: StateFlow<PracticeUiState> = _uiState.asStateFlow()

    private val _sessionEnded = MutableSharedFlow<SessionEndedNavigation>(extraBufferCapacity = 1)
    val sessionEnded: SharedFlow<SessionEndedNavigation> = _sessionEnded.asSharedFlow()

    private var timerJob: Job? = null
    private val poseUpdatesEnabled = AtomicBoolean(true)
    private val metricsEngine = BodyMetricsEngine()
    private val audioRecorder = AudioRecorder(application.applicationContext)
    private val modelManager = WhisperModelManager(application.applicationContext)
    private val reportPrep = ReportPreparationCoordinator.get(application.applicationContext)

    private val finishing = AtomicBoolean(false)
    private var activeScenarioRouteArg: String = ""
    private var activeScenarioDisplayName: String = ""

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

    fun bindScenario(routeArg: String, displayName: String) {
        activeScenarioRouteArg = routeArg
        activeScenarioDisplayName = displayName
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

        _uiState.update {
            it.copy(
                remainingSeconds = SESSION_DURATION_SECONDS,
                isRunning = true,
                isFinished = false,
                liveMetrics = LiveBodyMetrics(),
                sessionReport = null,
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
        )
    }

    /**
     * Stops capture exactly once, finalizes body/audio, starts report preparation,
     * then emits navigation to ReportPreparationScreen — never a partial final report.
     */
    private fun finishSession() {
        if (!finishing.compareAndSet(false, true)) return
        metricsEngine.stopAccumulation()
        val bodyReport = metricsEngine.buildReport()

        viewModelScope.launch {
            audioRecorder.stop(completed = true)
            val audioMetrics = audioRecorder.metrics.value
            val wav = audioRecorder.completedWavFile()
            val sessionId = UUID.randomUUID().toString()

            _uiState.update {
                it.copy(
                    remainingSeconds = 0,
                    isRunning = false,
                    isFinished = true,
                    sessionReport = bodyReport,
                    audioMetrics = audioMetrics,
                )
            }

            reportPrep.start(
                scenarioName = activeScenarioDisplayName.ifBlank { "Sessione" },
                totalSessionDurationMs = SESSION_DURATION_SECONDS * 1_000L,
                body = bodyReport,
                audio = audioMetrics,
                wavFile = wav,
                modelReady = modelManager.isReady(),
                sessionId = sessionId,
            )

            _sessionEnded.tryEmit(
                SessionEndedNavigation(
                    sessionId = sessionId,
                    scenarioRouteArg = activeScenarioRouteArg,
                ),
            )
        }
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
        metricsEngine.release()
        audioRecorder.resetAsync()
        // Report preparation continues on the application-scoped coordinator.
        super.onCleared()
    }
}
