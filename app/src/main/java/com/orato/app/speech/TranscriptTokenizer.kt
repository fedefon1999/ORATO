package com.orato.app.speech

import java.util.Locale

/**
 * Unicode-aware Italian word tokenization for speech metrics.
 * Pure Kotlin — no Android dependencies.
 */
object TranscriptTokenizer {

    private val wordPattern = Regex(
        pattern = "[\\p{L}\\p{M}]+(?:['’][\\p{L}\\p{M}]+)*",
    )

    fun tokenize(transcript: String): List<String> {
        if (transcript.isBlank()) return emptyList()
        return wordPattern.findAll(transcript).map { it.value }.toList()
    }

    fun wordCount(transcript: String): Int = tokenize(transcript).size

    fun normalizeToken(token: String): String =
        token.lowercase(Locale.ROOT)
}
