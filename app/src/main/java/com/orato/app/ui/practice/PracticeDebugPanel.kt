package com.orato.app.ui.practice

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.TabRowDefaults
import androidx.compose.material3.TabRowDefaults.tabIndicatorOffset
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.orato.app.audio.AudioRecordingState
import com.orato.app.audio.LiveAudioDebug
import com.orato.app.audio.VadState
import com.orato.app.domain.model.VisualAnalysisMode
import com.orato.app.face.LiveFaceMetrics
import com.orato.app.metrics.HandDebugInfo
import com.orato.app.metrics.LandmarkDebugInfo
import com.orato.app.metrics.LiveBodyMetrics
import com.orato.app.pose.PoseDetectionStatus

/**
 * Single compact collapsible development overlay for body + face + audio live metrics.
 * Collapsed by default; the whole panel is capped to ~25% of screen height.
 */
@Composable
fun PracticeDebugPanel(
    poseStatus: PoseDetectionStatus,
    poseStatusLabel: String,
    bodyMetrics: LiveBodyMetrics,
    audioDebug: LiveAudioDebug,
    modifier: Modifier = Modifier,
    visualAnalysisMode: VisualAnalysisMode = VisualAnalysisMode.BODY_ONLY,
    faceMetrics: LiveFaceMetrics = LiveFaceMetrics(),
    faceStatusLabel: String = "",
) {
    if (!PracticeDebugConfig.SHOW_DEBUG_PANEL) return

    var expanded by remember { mutableStateOf(false) }
    var selectedTab by remember { mutableIntStateOf(0) }
    val maxPanelHeight = (LocalConfiguration.current.screenHeightDp * 0.25f).dp
    val showBody = visualAnalysisMode != VisualAnalysisMode.FACE_ONLY
    val showFace = visualAnalysisMode != VisualAnalysisMode.BODY_ONLY
    val tabs = buildList {
        if (showBody) add("Corpo")
        if (showFace) add("Viso")
        add("Audio")
    }

    Surface(
        modifier = modifier.fillMaxWidth(0.92f),
        shape = RoundedCornerShape(12.dp),
        color = MaterialTheme.colorScheme.surface.copy(alpha = 0.82f),
        tonalElevation = 2.dp,
        shadowElevation = 2.dp,
    ) {
        Column(
            modifier = Modifier
                .heightIn(max = maxPanelHeight)
                .padding(horizontal = 10.dp, vertical = 8.dp),
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { expanded = !expanded },
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = "Debug",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    if (!expanded) {
                        CollapsedSummary(
                            poseStatusLabel = poseStatusLabel,
                            bodyMetrics = bodyMetrics,
                            audioDebug = audioDebug,
                            faceStatusLabel = faceStatusLabel,
                            showFace = showFace,
                        )
                    }
                }
                Icon(
                    imageVector = if (expanded) Icons.Filled.ExpandLess else Icons.Filled.ExpandMore,
                    contentDescription = if (expanded) "Comprimi debug" else "Espandi debug",
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            AnimatedVisibility(visible = expanded) {
                Column(modifier = Modifier.fillMaxWidth()) {
                    TabRow(
                        selectedTabIndex = selectedTab.coerceIn(0, tabs.lastIndex),
                        containerColor = MaterialTheme.colorScheme.surface.copy(alpha = 0f),
                        contentColor = MaterialTheme.colorScheme.onSurface,
                        indicator = { tabPositions ->
                            TabRowDefaults.SecondaryIndicator(
                                modifier = Modifier.tabIndicatorOffset(
                                    tabPositions[selectedTab.coerceIn(0, tabs.lastIndex)],
                                ),
                                color = MaterialTheme.colorScheme.primary,
                            )
                        },
                        divider = {},
                    ) {
                        tabs.forEachIndexed { index, title ->
                            Tab(
                                selected = selectedTab == index,
                                onClick = { selectedTab = index },
                                text = {
                                    Text(title, style = MaterialTheme.typography.labelMedium)
                                },
                            )
                        }
                    }

                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .heightIn(max = maxPanelHeight - 72.dp)
                            .verticalScroll(rememberScrollState())
                            .padding(top = 6.dp),
                        verticalArrangement = Arrangement.spacedBy(2.dp),
                    ) {
                        when (tabs.getOrNull(selectedTab)) {
                            "Corpo" -> BodyDebugContent(
                                poseStatus = poseStatus,
                                poseStatusLabel = poseStatusLabel,
                                metrics = bodyMetrics,
                            )
                            "Viso" -> FaceDebugContent(
                                faceStatusLabel = faceStatusLabel,
                                metrics = faceMetrics,
                            )
                            else -> AudioDebugContent(debug = audioDebug)
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun CollapsedSummary(
    poseStatusLabel: String,
    bodyMetrics: LiveBodyMetrics,
    audioDebug: LiveAudioDebug,
    faceStatusLabel: String = "",
    showFace: Boolean = false,
) {
    val hands = visibleHandsLabel(bodyMetrics)
    val speech = if (audioDebug.isSpeech) "sì" else "no"
    val dbfs = audioDebug.currentDbfs?.let { "%.1f dBFS".format(it) } ?: "—"
    val captured = formatDurationMmSs(audioDebug.capturedDurationMs)
    Text(
        text = buildString {
            if (showFace && faceStatusLabel.isNotBlank()) {
                append(shortPoseLabel(faceStatusLabel))
                append(" · ")
            }
            append(shortPoseLabel(poseStatusLabel))
            append(" · torso ")
            append(if (bodyMetrics.torsoValid) "ok" else "no")
            append(" · mani ")
            append(hands)
            append(" · ")
            append(audioStateLabelIt(audioDebug.state))
            append(" · parlato ")
            append(speech)
            append(" · ")
            append(dbfs)
            append(" · ")
            append(captured)
        },
        style = MaterialTheme.typography.labelSmall,
        color = MaterialTheme.colorScheme.onSurface,
        maxLines = 2,
        overflow = TextOverflow.Ellipsis,
    )
}

@Composable
private fun FaceDebugContent(
    faceStatusLabel: String,
    metrics: LiveFaceMetrics,
) {
    DebugLine("Stato viso", faceStatusLabel)
    DebugLine("Confidenza", "%.2f".format(metrics.confidence))
    DebugLine("Validità", metrics.validityReason.name)
    DebugLine("Sguardo", metrics.gazeState.name)
    DebugLine("Scala viso", metrics.faceScale?.let { "%.3f".format(it) } ?: "—")
    DebugLine("Yaw rel", metrics.relativeYawDeg?.let { "%.1f°".format(it) } ?: "—")
    DebugLine("Pitch rel", metrics.relativePitchDeg?.let { "%.1f°".format(it) } ?: "—")
    DebugLine("Roll rel", metrics.relativeRollDeg?.let { "%.1f°".format(it) } ?: "—")
    DebugLine("Iris S H/V", "${metrics.leftIrisHorizontalRatio ?: "—"} / ${metrics.leftIrisVerticalRatio ?: "—"}")
    DebugLine("Iris D H/V", "${metrics.rightIrisHorizontalRatio ?: "—"} / ${metrics.rightIrisVerticalRatio ?: "—"}")
}

@Composable
private fun BodyDebugContent(
    poseStatus: PoseDetectionStatus,
    poseStatusLabel: String,
    metrics: LiveBodyMetrics,
) {
    DebugLine("Stato posa", poseStatusLabel)
    DebugLine("Torso valido", if (metrics.torsoValid) "sì" else "no")
    DebugLine("Rilevamento", if (metrics.validDetection) "sì" else "no")
    DebugLine("Mani visibili", visibleHandsLabel(metrics))
    DebugLine("Spalla S", landmarkDebug(metrics.leftShoulder))
    DebugLine("Spalla D", landmarkDebug(metrics.rightShoulder))
    DebugLine("Anca S", landmarkDebug(metrics.leftHip))
    DebugLine("Anca D", landmarkDebug(metrics.rightHip))
    DebugLine(
        "Polso S",
        metrics.leftWristVisibility?.let { "%.2f".format(it) } ?: "—",
    )
    DebugLine(
        "Polso D",
        metrics.rightWristVisibility?.let { "%.2f".format(it) } ?: "—",
    )
    DebugLine("Dita S", metrics.leftValidFingerCount.toString())
    DebugLine("Dita D", metrics.rightValidFingerCount.toString())
    DebugLine("Mano S", handDebug(metrics.leftHand))
    DebugLine("Mano D", handDebug(metrics.rightHand))
    DebugLine(
        "Tilt spalle",
        metrics.shoulderTilt?.let { "%.3f".format(it) } ?: "—",
    )
    DebugLine(
        "Inclinazione busto",
        metrics.trunkAngleDegrees?.let { "%.1f°".format(it) } ?: "—",
    )
    if (poseStatus is PoseDetectionStatus.Error) {
        DebugLine("Errore posa", poseStatus.message)
    }
}

@Composable
private fun AudioDebugContent(debug: LiveAudioDebug) {
    DebugLine("Stato registrazione", audioStateLabelIt(debug.state))
    DebugLine(
        "Frequenza di campionamento",
        debug.sampleRateHz?.let { "%d Hz".format(it) } ?: "—",
    )
    DebugLine("Sorgente audio", debug.audioSourceLabel ?: "—")
    DebugLine(
        "dBFS frame",
        debug.rawFrameDbfs?.let { "%.1f dBFS".format(it) } ?: "—",
    )
    DebugLine(
        "Rumore di fondo",
        debug.noiseFloorDbfs?.let { "%.1f dBFS".format(it) } ?: "—",
    )
    DebugLine(
        "Soglia speech-on",
        debug.speechOnThresholdDbfs?.let { "%.1f dBFS".format(it) } ?: "—",
    )
    DebugLine(
        "Soglia speech-off",
        debug.speechOffThresholdDbfs?.let { "%.1f dBFS".format(it) } ?: "—",
    )
    DebugLine("Stato VAD", vadStateLabelIt(debug.vadState))
    DebugLine("Parlato", if (debug.isSpeech) "sì" else "no")
    DebugLine(
        "Seg. parlato corrente",
        formatDurationMmSs(debug.currentSpeechSegmentMs),
    )
    DebugLine(
        "Seg. silenzio corrente",
        formatDurationMmSs(debug.currentSilenceSegmentMs),
    )
    DebugLine("Seg. parlato finalizzati", debug.finalizedSpeechSegments.toString())
    DebugLine("Pause interne finalizzate", debug.finalizedInternalPauses.toString())
    DebugLine("Durata catturata", formatDurationMmSs(debug.capturedDurationMs))
    DebugLine("Letture perse", debug.droppedReadCount.toString())
    val error = debug.errorMessage
    if (!error.isNullOrBlank()) {
        DebugLine("Errore", error)
    }
}

private fun vadStateLabelIt(state: VadState): String =
    when (state) {
        VadState.Calibrating -> "Calibrazione"
        VadState.Silence -> "Silenzio"
        VadState.Speech -> "Parlato"
    }

@Composable
private fun DebugLine(label: String, value: String) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.weight(0.48f),
        )
        Text(
            text = value,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurface,
            modifier = Modifier.weight(0.52f),
        )
    }
}

private fun visibleHandsLabel(metrics: LiveBodyMetrics): String =
    when {
        metrics.twoHandsVisible || (metrics.leftHandVisible && metrics.rightHandVisible) -> "2"
        metrics.oneHandVisible || metrics.leftHandVisible || metrics.rightHandVisible -> "1"
        else -> "0"
    }

private fun shortPoseLabel(full: String): String =
    when {
        full.contains("rilevat", ignoreCase = true) -> "Rilevata"
        full.contains("Inizializz", ignoreCase = true) -> "Init"
        full.contains("Posizionati", ignoreCase = true) -> "Assente"
        else -> full.take(18)
    }

private fun audioStateLabelIt(state: AudioRecordingState): String =
    when (state) {
        AudioRecordingState.Idle -> "Inattivo"
        AudioRecordingState.Initializing -> "Init"
        AudioRecordingState.Recording -> "Rec"
        AudioRecordingState.Stopping -> "Stop"
        AudioRecordingState.Completed -> "Fine"
        AudioRecordingState.Error -> "Errore"
    }

internal fun formatDurationMmSs(durationMs: Long): String {
    val totalSeconds = (durationMs.coerceAtLeast(0L) / 1000L).toInt()
    val minutes = totalSeconds / 60
    val seconds = totalSeconds % 60
    return "%d:%02d".format(minutes, seconds)
}

private fun landmarkDebug(info: LandmarkDebugInfo): String {
    val vis = info.visibility?.let { "%.2f".format(it) } ?: "—"
    val frame = if (info.inFrame) "in" else "out"
    return "$vis/$frame"
}

private fun handDebug(info: HandDebugInfo): String {
    val avg = info.averageVisibility?.let { "%.2f".format(it) } ?: "—"
    val box = info.boundingBoxSize?.let { "%.3f".format(it) } ?: "—"
    val spread = info.fingerSpread?.let { "%.3f".format(it) } ?: "—"
    val inside = if (info.insideTorsoRegion) "inT" else "outT"
    val occ = if (info.occludedByTorso) "occ" else "clear"
    val vis = if (info.handVisible) "vis" else "hide"
    return "$vis avg=$avg box=$box spr=$spread $inside $occ"
}
