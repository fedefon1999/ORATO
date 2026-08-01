package com.orato.app.speech

import java.util.Locale

/**
 * Unicode-aware Italian word tokenization for speech metrics.
 */
object TranscriptTokenizer {

    private val wordPattern = Regex(
        pattern = "[\\p{L}\\p{M}]+(?:['’][\\p{L}\\p{M}]+)*",
    )

    data class TokenSpan(
        val text: String,
        val start: Int,
        val end: Int,
    )

    fun tokenize(transcript: String): List<String> {
        if (transcript.isBlank()) return emptyList()
        return wordPattern.findAll(transcript).map { it.value }.toList()
    }

    fun tokenizeWithSpans(transcript: String): List<TokenSpan> {
        if (transcript.isBlank()) return emptyList()
        return wordPattern.findAll(transcript).map {
            TokenSpan(text = it.value, start = it.range.first, end = it.range.last + 1)
        }.toList()
    }

    fun wordCount(transcript: String): Int = tokenize(transcript).size

    fun normalizeToken(token: String): String =
        token.lowercase(Locale.ROOT)
}
