package com.orato.app.ui.practice

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.orato.app.audio.AudioRecorder
import com.orato.app.audio.AudioRecordingState
import com.orato.app.audio.AudioSessionMetrics
import com.orato.app.audio.LiveAudioDebug
import com.orato.app.audio.SessionPracticeReport
import com.orato.app.metrics.BodyMetricsEngine
import com.orato.app.metrics.LiveBodyMetrics
import com.orato.app.metrics.SessionBodyReport
import com.orato.app.pose.PoseDetectionStatus
import com.orato.app.pose.UpperBodyPoseFrame
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
    private val poseUpdatesEnabled = AtomicBoolean(true)
    private val metricsEngine = BodyMetricsEngine()
    private val audioRecorder = AudioRecorder(application.applicationContext)

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
    }

    fun onMicrophoneAvailabilityChanged(available: Boolean) {
        val wasAvailable = _uiState.value.microphoneAvailable
        _uiState.update { it.copy(microphoneAvailable = available) }
        if (!available && wasAvailable && _uiState.value.isRunning) {
            // Permission revoked mid-session — stop audio once; body session continues.
            audioRecorder.markUnavailable("Permesso microfono revocato")
            viewModelScope.launch {
                audioRecorder.stop(completed = false)
            }
        }
    }

    /** Called when the practice screen / app moves to background. */
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

    /**
     * Cancels the timer and clears metric accumulators so the next session
     * cannot inherit prior samples. Live preview continues without aggregation.
     * Stops audio exactly once and deletes the incomplete WAV.
     */
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

    /** Like [resetSession] but assumes audio stop was already requested. */
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
        )
    }

    private fun finishSession() {
        if (!finishing.compareAndSet(false, true)) return
        metricsEngine.stopAccumulation()
        val bodyReport = metricsEngine.buildReport()

        viewModelScope.launch {
            audioRecorder.stop(completed = true)
            val audioMetrics = audioRecorder.metrics.value
            _uiState.update {
                it.copy(
                    remainingSeconds = 0,
                    isRunning = false,
                    isFinished = true,
                    sessionReport = bodyReport,
                    audioMetrics = audioMetrics,
                )
            }
            _sessionCompleted.tryEmit(
                SessionPracticeReport(body = bodyReport, audio = audioMetrics),
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
                // Latched torso hysteresis from the metrics engine — never a single frame.
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
        super.onCleared()
    }
}
