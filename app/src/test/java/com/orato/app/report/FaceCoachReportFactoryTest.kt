package com.orato.app.report

import com.orato.app.audio.AudioInputQuality
import com.orato.app.audio.AudioRecordingState
import com.orato.app.audio.AudioSessionMetrics
import com.orato.app.audio.PauseBuckets
import com.orato.app.domain.model.Scenario
import com.orato.app.domain.model.VisualAnalysisMode
import com.orato.app.face.CameraGazeReportData
import com.orato.app.face.FaceFramingReportData
import com.orato.app.face.HeadMovementReportData
import com.orato.app.face.SessionFaceReport
import com.orato.app.metrics.SessionBodyReport
import com.orato.app.speech.DiscourseMarkerMetrics
import com.orato.app.speech.SpeechIntelligenceMetrics
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class FaceCoachReportFactoryTest {

    private fun audio(): AudioSessionMetrics =
        AudioSessionMetrics(
            state = AudioRecordingState.Completed,
            inputQuality = AudioInputQuality.GOOD,
            capturedDurationMs = 90_000L,
            speechDurationMs = 60_000L,
            longestSpeechSegmentMs = 10_000L,
            droppedReadCount = 0,
            sampleRateHz = 16_000,
            audioSourceLabel = "MIC",
            speechRatioPercent = 66.0,
            meanSpeechDbfs = -20.0,
            volumeVariationStdDevDb = 2.0,
            clippingPercent = 0.0,
            approximatePauseCount = 2,
            medianPauseDurationMs = 800L,
            longestPauseDurationMs = 1_200L,
            pausesOver1500Ms = 0,
            pauseBuckets = PauseBuckets.fromDurations(listOf(800L)),
            errorMessage = null,
            insufficientData = false,
        )

    private fun linguistic(): SpeechIntelligenceMetrics =
        SpeechIntelligenceMetrics(
            wordCount = 100,
            vadSpeechDurationMs = 60_000L,
            wordsPerMinute = 100.0,
            discourseMarkers = DiscourseMarkerMetrics(totalCount = 2, markersPerMinute = 2.0, breakdown = emptyMap()),
            immediateRepetitionCount = 0,
            immediateRepetitionBreakdown = emptyMap(),
        )

    private fun faceReport(): SessionFaceReport =
        SessionFaceReport(
            framing = FaceFramingReportData(
                faceDetectedPercent = 90f,
                validTrackingPercent = 80f,
                centeredFacePercent = 70f,
                tooCloseDurationMs = 0L,
                tooFarDurationMs = 0L,
                outOfFrameEventCount = 1,
                longestValidTrackingMs = 5_000L,
                insufficientData = false,
            ),
            gaze = CameraGazeReportData(
                towardCameraPercent = 75f,
                longestTowardCameraMs = 3_000L,
                significantAwayCount = 2,
                averageSignificantAwayMs = 700L,
                longestAwayMs = 900L,
                validGazeTrackingMs = 50_000L,
                unavailableGazeTrackingMs = 5_000L,
                insufficientData = false,
                explanation = CameraGazeReportData.DEFAULT_EXPLANATION,
            ),
            headMovement = HeadMovementReportData(
                centeredHeadPercent = 60f,
                largeHorizontalTurnCount = 1,
                largeVerticalMovementCount = 0,
                lateralTiltCount = 0,
                averageAngularSpeedDegPerSec = 5f,
                peakAngularSpeedDegPerSec = 20f,
                meaningfulDirectionChanges = 2,
                longestStableHeadMs = 4_000L,
                insufficientData = false,
            ),
            eyeClosure = null,
        )

    @Test
    fun bodyOnly_hasCorpo_noFaceSections() {
        val report = CompletedSessionReportFactory.build(
            sessionId = "b",
            scenarioName = Scenario.PRESENTATION.displayName,
            completedAtEpochMs = 1L,
            totalSessionDurationMs = 90_000L,
            body = SessionBodyReport.emptyInsufficient(),
            audio = audio(),
            linguistic = linguistic(),
            scenario = Scenario.PRESENTATION,
            visualAnalysisMode = VisualAnalysisMode.BODY_ONLY,
            face = faceReport(),
        )
        assertNotNull(report.body)
        assertNull(report.facePresence)
        assertNull(report.gaze)
        assertNull(report.headMovement)
        assertNotNull(report.voice)
        assertTrue(report.rhythmAndFluency.linguisticAvailable)
    }

    @Test
    fun faceOnly_hasFaceSections_noCorpo() {
        val report = CompletedSessionReportFactory.build(
            sessionId = "f",
            scenarioName = Scenario.EXAM.displayName,
            completedAtEpochMs = 1L,
            totalSessionDurationMs = 90_000L,
            body = SessionBodyReport.emptyInsufficient(),
            audio = audio(),
            linguistic = linguistic(),
            scenario = Scenario.EXAM,
            visualAnalysisMode = VisualAnalysisMode.FACE_ONLY,
            face = faceReport(),
        )
        assertNull(report.body)
        assertNotNull(report.facePresence)
        assertNotNull(report.gaze)
        assertNotNull(report.headMovement)
        assertNotNull(report.voice)
        assertTrue(report.rhythmAndFluency.linguisticAvailable)
    }

    @Test
    fun noReportContainsBothBodyAndFaceVisualSections() {
        for (scenario in Scenario.entries) {
            val mode = com.orato.app.domain.model.VisualAnalysisMapping.modeFor(scenario)
            val report = CompletedSessionReportFactory.build(
                sessionId = scenario.name,
                scenarioName = scenario.displayName,
                completedAtEpochMs = 1L,
                totalSessionDurationMs = 90_000L,
                body = SessionBodyReport.emptyInsufficient(),
                audio = audio(),
                linguistic = linguistic(),
                scenario = scenario,
                visualAnalysisMode = mode,
                face = faceReport(),
            )
            val hasBody = report.body != null
            val hasFace = report.facePresence != null || report.gaze != null || report.headMovement != null
            assertFalse(
                "Scenario ${scenario.name} must not include both body and face visuals",
                hasBody && hasFace,
            )
        }
    }

    @Test
    fun voiceAndRhythm_inEveryScenario() {
        for (scenario in Scenario.entries) {
            val mode = com.orato.app.domain.model.VisualAnalysisMapping.modeFor(scenario)
            val report = CompletedSessionReportFactory.build(
                sessionId = scenario.name,
                scenarioName = scenario.displayName,
                completedAtEpochMs = 1L,
                totalSessionDurationMs = 90_000L,
                body = SessionBodyReport.emptyInsufficient(),
                audio = audio(),
                linguistic = linguistic(),
                scenario = scenario,
                visualAnalysisMode = mode,
                face = faceReport(),
            )
            assertFalse(report.voice.insufficientData)
            assertTrue(report.rhythmAndFluency.linguisticAvailable)
        }
    }

    @Test
    fun faceFailure_marksFaceUnavailable_preservesAudioSpeech_faceOnly() {
        val report = CompletedSessionReportFactory.build(
            sessionId = "ff",
            scenarioName = Scenario.EXAM.displayName,
            completedAtEpochMs = 1L,
            totalSessionDurationMs = 90_000L,
            body = null,
            audio = audio(),
            linguistic = linguistic(),
            scenario = Scenario.EXAM,
            visualAnalysisMode = VisualAnalysisMode.FACE_ONLY,
            face = null,
            faceFailed = true,
        )
        assertNull(report.body)
        assertNull(report.facePresence)
        assertTrue(ReportSection.FACE_PRESENCE in report.unavailableSections)
        assertFalse(report.voice.insufficientData)
        assertTrue(report.rhythmAndFluency.linguisticAvailable)
    }

    @Test
    fun poseFailure_marksBodyUnavailable_preservesAudioSpeech_bodyOnly() {
        val report = CompletedSessionReportFactory.build(
            sessionId = "pf",
            scenarioName = Scenario.PRESENTATION.displayName,
            completedAtEpochMs = 1L,
            totalSessionDurationMs = 90_000L,
            body = null,
            audio = audio(),
            linguistic = linguistic(),
            scenario = Scenario.PRESENTATION,
            visualAnalysisMode = VisualAnalysisMode.BODY_ONLY,
            face = faceReport(),
            bodyFailed = true,
        )
        assertNull(report.body)
        assertTrue(ReportSection.BODY in report.unavailableSections)
        assertNull(report.facePresence)
        assertFalse(report.voice.insufficientData)
        assertTrue(report.rhythmAndFluency.linguisticAvailable)
    }

    @Test
    fun noTranscriptFieldOnReport() {
        val report = CompletedSessionReportFactory.build(
            sessionId = "tx",
            scenarioName = "x",
            completedAtEpochMs = 1L,
            totalSessionDurationMs = 90_000L,
            body = SessionBodyReport.emptyInsufficient(),
            audio = audio(),
            linguistic = linguistic(),
            visualAnalysisMode = VisualAnalysisMode.BODY_ONLY,
        )
        val names = report::class.java.declaredFields.map { it.name }
        assertFalse(names.any { it.equals("transcript", ignoreCase = true) })
    }

    @Test
    fun pendingCalibration_notPersistedAcrossClear() {
        com.orato.app.face.PendingFaceCalibration.set(
            com.orato.app.face.FaceCalibrationProfile(
                calibratedAtMs = 1L,
                baselineYawDeg = 1f,
                baselinePitchDeg = 1f,
                baselineRollDeg = 1f,
                baselineLeftIrisHorizontalRatio = 0.5f,
                baselineLeftIrisVerticalRatio = 0.5f,
                baselineRightIrisHorizontalRatio = 0.5f,
                baselineRightIrisVerticalRatio = 0.5f,
                baselineFaceCenterX = 0.5f,
                baselineFaceCenterY = 0.5f,
                baselineFaceScale = 0.25f,
            ),
        )
        assertNotNull(com.orato.app.face.PendingFaceCalibration.peek())
        com.orato.app.face.PendingFaceCalibration.clear()
        assertNull(com.orato.app.face.PendingFaceCalibration.peek())
    }
}
