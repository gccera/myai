package com.gccera.myai.safety

import android.content.Context
import dagger.hilt.android.qualifiers.ApplicationContext
import java.text.Normalizer
import javax.inject.Inject
import javax.inject.Singleton

data class FilterResult(val blocked: Boolean, val matchedTerm: String? = null) {
    companion object { val OK = FilterResult(false) }
}

/**
 * Blocks prompts and replies containing terms from `assets/safety/blocked_terms.txt`
 * (Google Play AI-Generated Content policy). Matching is whole-word/phrase, case- and accent-
 * insensitive, and undoes simple obfuscation ("n4k3d", "p.o.r.n" style separators inside words are
 * normalised to spaces, then checked both spaced and joined).
 *
 * This is a first line of defence only; it complements — not replaces — the in-app Report button.
 */
class TermMatcher(terms: Collection<String>) {
    private val normalizedTerms: List<Pair<String, String>> =
        terms.map { it.trim() }.filter { it.isNotEmpty() && !it.startsWith("#") }
            .map { it to normalize(it) }.filter { it.second.isNotBlank() }.distinctBy { it.second }

    fun check(text: String): FilterResult {
        if (text.isBlank()) return FilterResult.OK
        val norm = normalize(text)
        val spaced = " $norm "
        // Also catch "p o r n" / "p-o-r-n": collapse runs of single characters into one word.
        val joinedSingles = " " + norm.replace(Regex("(?<=\\b\\w) (?=\\w\\b)"), "") + " "
        for ((original, term) in normalizedTerms) {
            val needle = " $term "
            if (spaced.contains(needle) || joinedSingles.contains(needle)) return FilterResult(true, original)
        }
        return FilterResult.OK
    }

    companion object {
        private val LEET = mapOf('0' to 'o', '1' to 'i', '3' to 'e', '4' to 'a', '5' to 's', '7' to 't', '@' to 'a', '$' to 's')

        fun normalize(s: String): String {
            val noAccents = Normalizer.normalize(s, Normalizer.Form.NFKD).replace(Regex("\\p{M}+"), "")
            val sb = StringBuilder(noAccents.length)
            for (ch in noAccents.lowercase()) {
                val c = LEET[ch] ?: ch
                sb.append(if (c.isLetterOrDigit()) c else ' ')
            }
            return sb.toString().replace(Regex("\\s+"), " ").trim()
        }
    }
}

@Singleton
class ContentFilter @Inject constructor(@ApplicationContext context: Context) {
    private val matcher: TermMatcher by lazy {
        TermMatcher(context.assets.open("safety/blocked_terms.txt").bufferedReader().readLines())
    }

    fun check(text: String): FilterResult = matcher.check(text)

    companion object {
        const val BLOCKED_PROMPT_MESSAGE =
            "This request can't be processed because it may violate the content policy."
        const val BLOCKED_REPLY_MESSAGE = "_This reply was hidden by the safety filter._"
    }
}
