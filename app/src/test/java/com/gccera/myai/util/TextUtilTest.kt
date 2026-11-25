package com.gccera.myai.util

import com.gccera.myai.engine.PromptImprover
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TextUtilTest {
    @Test fun thinkTagsAreHidden() {
        assertEquals("Hello", ThinkTags.strip("<think>reasoning</think>Hello"))
        assertEquals("", ThinkTags.strip("<think>still thinking"))
        assertTrue(ThinkTags.isThinking("<think>still"))
        assertFalse(ThinkTags.isThinking("<think>a</think>done"))
    }

    @Test fun promptCleanup() {
        assertEquals("a red fox, forest, golden hour", PromptImprover.cleanup("<think>x</think>\n\"Prompt: a red fox, forest, golden hour\"").removePrefix("Prompt: "))
        assertEquals("a cat, oil painting", PromptImprover.cleanup("Prompt: a cat, oil painting"))
    }

    @Test fun bytes() {
        assertEquals("1.0 KB", formatBytes(1024))
        assertEquals("1.5 GB", formatBytes(1610612736))
    }
}
