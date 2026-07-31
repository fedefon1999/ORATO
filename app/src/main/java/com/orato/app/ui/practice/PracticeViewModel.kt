package com.orato.app.ui.practice

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.orato.app.pose.PoseDetectionStatus
import com.orato.app.pose.PoseVisibility
import com.orato.app.pose.UpperBodyPoseFrame
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
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

    private var timerJob: Job? = null
    private val poseUpdatesEnabled = AtomicBoolean(true)

    fun startSession() {
        if (_uiState.value.isRunning || _uiState.value.isFinished) return

        _uiState.update {
            it.copy(
                remainingSeconds = SESSION_DURATION_SECONDS,
                isRunning = true,
                isFinished = false,
            )
        }

        timerJob?.cancel()
        timerJob = viewModelScope.launch {
            while (isActive) {
                delay(1_000)
                val current = _uiState.value.remainingSeconds
                if (current <= 1) {
                    _uiState.update {
                        it.copy(
                            remainingSeconds = 0,
                            isRunning = false,
                            isFinished = true,
                        )
                    }
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
        val poseStatus = _uiState.value.poseStatus
        val poseFrame = _uiState.value.poseFrame
        _uiState.value = PracticeUiState(
            poseStatus = when (poseStatus) {
                is PoseDetectionStatus.Error -> poseStatus
                PoseDetectionStatus.Initializing -> PoseDetectionStatus.Initializing
                else -> poseStatus
            },
            poseFrame = poseFrame,
        )
    }

    fun onPoseStatus(status: PoseDetectionStatus) {
        if (!poseUpdatesEnabled.get()) return
        _uiState.update { state ->
            // Keep a hard error until the screen is recreated.
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

        val detected = PoseVisibility.hasSufficientTorsoVisibility(frame.landmarks)
        _uiState.update {
            it.copy(
                poseFrame = frame,
                poseStatus = if (detected) {
                    PoseDetectionStatus.Detected
                } else {
                    PoseDetectionStatus.Insufficient
                },
            )
        }
    }

    override fun onCleared() {
        poseUpdatesEnabled.set(false)
        timerJob?.cancel()
        super.onCleared()
    }
}
