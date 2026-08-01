package com.orato.app.speech

import java.util.Locale

/**
 * Deterministic detection of Italian textual discourse markers from recognized text.
 *
 * Vocal hesitations (eh, ehm, uhm, …) are intentionally excluded — whisper.cpp does not
 * reliably preserve them, and ORATO does not claim acoustic filler measurement.
 *
 * Counts are estimates (“Intercalari discorsivi stimati”).
 */
object DiscourseMarkerDetector {

    val CLEAR_MARKERS: Set<String> = setOf(
        "cioè", "praticamente", "diciamo", "insomma",
    )

    val CONTEXTUAL_MARKERS: Set<String> = setOf(
        "tipo", "allora", "ecco", "dunque",
    )

    /** Tokens that must never contribute to production discourse-marker counts. */
    val EXCLUDED_VOCAL_HESITATIONS: Set<String> = setOf(
        "eh", "ehm", "em", "emm", "uhm", "um", "mhm", "mmm", "mm",
        "ehmm", "ehmmm", "uhmm", "uhmmm", "mmmm", "mmmmm", "emmm",
    )

    private val ARTICLES_BEFORE_TIPO: Set<String> = setOf(
        "un", "uno", "una", "il", "lo", "la", "i", "gli", "le",
        "dei", "degli", "delle", "quel", "quello", "quella", "quei", "quegli",
        "questo", "questa", "questi", "queste", "ogni", "altro", "altra",
    )

    data class Result(
        val totalCount: Int,
        val breakdown: Map<String, Int>,
        /** Indices of tokens classified as discourse markers (for repetition de-duplication). */
        val markerTokenIndices: Set<Int>,
    )

    fun detect(transcript: String): Result {
        val tokens = TranscriptTokenizer.tokenize(transcript)
        val spans = TranscriptTokenizer.tokenizeWithSpans(transcript)
        return detect(tokens, spans, transcript)
    }

    fun detect(
        tokens: List<String>,
        spans: List<TranscriptTokenizer.TokenSpan>,
        original: String,
    ): Result {
        if (tokens.isEmpty()) {
            return Result(0, emptyMap(), emptySet())
        }
        val counts = linkedMapOf<String, Int>()
        val markerIndices = mutableSetOf<Int>()
        var total = 0

        for (i in tokens.indices) {
            val normalized = TranscriptTokenizer.normalizeToken(tokens[i])
            if (normalized in EXCLUDED_VOCAL_HESITATIONS) continue

            val counted: String? = when {
                normalized in CLEAR_MARKERS -> normalized
                normalized in CONTEXTUAL_MARKERS -> {
                    if (isContextualMarker(normalized, tokens, i, spans, original)) {
                        normalized
                    } else {
                        null
                    }
                }
                else -> null
            }
            if (counted != null) {
                counts[counted] = (counts[counted] ?: 0) + 1
                markerIndices.add(i)
                total++
            }
        }
        return Result(total, counts.toMap(), markerIndices.toSet())
    }

    private fun isContextualMarker(
        marker: String,
        tokens: List<String>,
        index: Int,
        spans: List<TranscriptTokenizer.TokenSpan>,
        original: String,
    ): Boolean {
        val prev = tokens.getOrNull(index - 1)?.let { TranscriptTokenizer.normalizeToken(it) }
        val next = tokens.getOrNull(index + 1)?.let { TranscriptTokenizer.normalizeToken(it) }
        val boundaryBefore = isBoundaryBefore(index, spans, original)
        val punctAfter = hasPunctuationAfter(index, spans, original)

        return when (marker) {
            "tipo" -> {
                if (next == "di") return false
                if (prev != null && prev in ARTICLES_BEFORE_TIPO) return false
                boundaryBefore || punctAfter
            }
            "allora" -> {
                if (prev == "da") return false
                boundaryBefore || punctAfter
            }
            "ecco" -> {
                if (punctAfter) return true
                if (next != null && next in ARTICLES_BEFORE_TIPO) return false
                false
            }
            "dunque" -> {
                punctAfter || (index > 0 && boundaryBefore)
            }
            else -> false
        }
    }

    private fun isBoundaryBefore(
        index: Int,
        spans: List<TranscriptTokenizer.TokenSpan>,
        original: String,
    ): Boolean {
        if (index <= 0) return true
        if (index >= spans.size) return index == 0
        val start = spans[index].start
        var i = start - 1
        while (i >= 0 && original[i].isWhitespace()) i--
        if (i < 0) return true
        val c = original[i]
        return c == ',' || c == ';' || c == ':' || c == '.' || c == '!' || c == '?' ||
            c == '…' || c == '-' || c == '—'
    }

    private fun hasPunctuationAfter(
        index: Int,
        spans: List<TranscriptTokenizer.TokenSpan>,
        original: String,
    ): Boolean {
        if (index >= spans.size) return false
        val end = spans[index].end
        var i = end
        while (i < original.length && original[i].isWhitespace()) i++
        if (i >= original.length) return false
        val c = original[i]
        return c == ',' || c == ';' || c == ':' || c == '.' || c == '!' || c == '?' ||
            c == '…' || c == '-' || c == '—'
    }
}

/** @deprecated Use [DiscourseMarkerDetector]. Kept as a thin alias during migration tests. */
@Deprecated("Use DiscourseMarkerDetector", ReplaceWith("DiscourseMarkerDetector"))
object FillerDetector {
    fun detect(transcript: String) = DiscourseMarkerDetector.detect(transcript)
}
