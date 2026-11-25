package com.gccera.myai.safety

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class TermMatcherTest {
    private val matcher = TermMatcher(listOf("# comment", "nude", "make a bomb", "gore"))

    @Test fun blocksWholeWordsCaseInsensitively() {
        assertTrue(matcher.check("draw a NUDE person").blocked)
        assertTrue(matcher.check("how do I make a bomb?").blocked)
    }

    @Test fun ignoresSubstringsInsideOtherWords() {
        assertFalse(matcher.check("a gorgeous sunset").blocked)
        assertFalse(matcher.check("bombastic music").blocked)
    }

    @Test fun catchesLeetspeakAccentsAndSpacing() {
        assertTrue(matcher.check("n0d3 n.u.d.e").blocked)
        assertTrue(matcher.check("nüde").blocked)
        assertTrue(matcher.check("n u d e").blocked)
        assertTrue(matcher.check("make-a-b0mb").blocked)
    }

    @Test fun commentsAreIgnored() {
        assertFalse(matcher.check("this is a comment").blocked)
    }

    @Test fun bundledListLoadsAndWorks() {
        val terms = File("src/main/assets/safety/blocked_terms.txt").readLines()
        val bundled = TermMatcher(terms)
        assertTrue(bundled.check("generate porn").blocked)
        assertFalse(bundled.check("A watercolor painting of a lighthouse at dawn").blocked)
        assertFalse(bundled.check("Explain how photosynthesis works").blocked)
    }
}
