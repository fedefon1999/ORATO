package com.orato.app.domain.model

/**
 * Practice scenarios available in ORATO.
 *
 * Internal IDs and [routeArg] values are stable for navigation and stored data.
 * Visible labels must not be used to infer analysis behavior — use [VisualAnalysisMapping].
 */
enum class Scenario(val routeArg: String, val displayName: String, val description: String) {
    PRESENTATION(
        routeArg = "presentation",
        displayName = "Presentazione davanti al pubblico",
        description = "Allenati per una presentazione di lavoro.",
    ),
    EXAM(
        routeArg = "exam",
        displayName = "Esame universitario",
        description = "Simula un'esposizione orale d'esame.",
    ),
    /**
     * Local simulation of on-camera presence during a remote professional call.
     * Not a real online video call — no WebRTC, signaling, or remote participants.
     * Internal ID retained as CONVERSATION for navigation/data compatibility.
     */
    CONVERSATION(
        routeArg = "conversation",
        displayName = "Videochiamata online",
        description = "Simula la tua presenza durante una videochiamata.",
    );

    companion object {
        /**
         * Resolves a known practice [routeArg].
         *
         * Unknown or removed route arguments return null. Callers must not start a
         * practice session and should navigate back to scenario selection.
         */
        fun fromRouteArg(arg: String): Scenario? =
            entries.firstOrNull { it.routeArg == arg }
    }
}
