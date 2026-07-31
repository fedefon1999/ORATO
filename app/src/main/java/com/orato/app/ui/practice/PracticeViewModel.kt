package com.orato.app.ui.practice

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.orato.app.metrics.BodyMetricsEngine
import com.orato.app.metrics.LiveBodyMetrics
import com.orato.app.metrics.SessionBodyReport
import com.orato.app.pose.PoseDetectionStatus
import com.orato.app.pose.PoseVisibility
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
}

class PracticeViewModel : ViewModel() {

    private val _uiState = MutableStateFlow(PracticeUiState())
    val uiState: StateFlow<PracticeUiState> = _uiState.asStateFlow()

    private val _sessionCompleted = MutableSharedFlow<SessionBodyReport>(extraBufferCapacity = 1)
    val sessionCompleted: SharedFlow<SessionBodyReport> = _sessionCompleted.asSharedFlow()

    private var timerJob: Job? = null
    private val poseUpdatesEnabled = AtomicBoolean(true)
    private val metricsEngine = BodyMetricsEngine()

    fun startSession() {
        if (_uiState.value.isRunning || _uiState.value.isFinished) return

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
     */
    fun resetSession() {
        timerJob?.cancel()
        timerJob = null
        metricsEngine.reset()
        metricsEngine.stopAccumulation()

        val poseStatus = _uiState.value.poseStatus
        val poseFrame = _uiState.value.poseFrame
        _uiState.value = PracticeUiState(
            poseStatus = when (poseStatus) {
                is PoseDetectionStatus.Error -> poseStatus
                PoseDetectionStatus.Initializing -> PoseDetectionStatus.Initializing
                else -> poseStatus
            },
            poseFrame = poseFrame,
            liveMetrics = metricsEngine.liveMetrics(),
            sessionReport = null,
        )
    }

    private fun finishSession() {
        metricsEngine.stopAccumulation()
        val report = metricsEngine.buildReport()
        _uiState.update {
            it.copy(
                remainingSeconds = 0,
                isRunning = false,
                isFinished = true,
                sessionReport = report,
            )
        }
        _sessionCompleted.tryEmit(report)
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
        val detected = PoseVisibility.hasSufficientTorsoVisibility(frame.landmarks)
        _uiState.update {
            it.copy(
                poseFrame = frame,
                poseStatus = if (detected) {
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
        super.onCleared()
    }
}
