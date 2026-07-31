package com.orato.app.domain.model

/**
 * Practice scenarios available in the MVP foundation.
 */
enum class Scenario(val routeArg: String, val displayName: String, val description: String) {
    PRESENTATION(
        routeArg = "presentation",
        displayName = "Presentazione",
        description = "Allenati per una presentazione di lavoro.",
    ),
    INTERVIEW(
        routeArg = "interview",
        displayName = "Colloquio",
        description = "Preparati a rispondere con chiarezza e presenza.",
    ),
    EXAM(
        routeArg = "exam",
        displayName = "Esame universitario",
        description = "Simula un'esposizione orale d'esame.",
    ),
    CONVERSATION(
        routeArg = "conversation",
        displayName = "Conversazione",
        description = "Esercitati in un dialogo naturale e fluido.",
    );

    companion object {
        fun fromRouteArg(arg: String): Scenario =
            entries.firstOrNull { it.routeArg == arg } ?: PRESENTATION
    }
}
