package com.orato.app.speech

import java.util.Locale

/**
 * Unicode-aware Italian word tokenization for speech metrics.
 * Pure Kotlin — no Android dependencies.
 *
 * Rules:
 * - Case-insensitive comparison helpers normalize with [Locale.ROOT]
 * - Apostrophes (ASCII `'` and typographic `’`) stay inside words (l'amico → one token)
 * - Punctuation alone never counts as a word
 * - Repeated whitespace / newlines are ignored
 */
object TranscriptTokenizer {

    private val wordPattern = Regex(
        pattern = "[\\p{L}\\p{M}]+(?:['’][\\p{L}\\p{M}]+)*",
    )

    /** Extracts word tokens in order of appearance. Empty / punctuation-only → empty list. */
    fun tokenize(transcript: String): List<String> {
        if (transcript.isBlank()) return emptyList()
        return wordPattern.findAll(transcript).map { it.value }.toList()
    }

    fun wordCount(transcript: String): Int = tokenize(transcript).size

    /** Lowercases with ROOT locale for deterministic filler matching. */
    fun normalizeToken(token: String): String =
        token.lowercase(Locale.ROOT)
}
