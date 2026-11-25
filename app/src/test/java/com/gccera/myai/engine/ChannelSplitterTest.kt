package com.gccera.myai.engine

import com.gccera.myai.engine.llm.ChannelSplitter
import com.gccera.myai.util.ThinkTags
import org.junit.Assert.assertEquals
import org.junit.Test

/** Regression tests for replies that LiteRT-LM routes into the "thought" channel (seen with Qwen3). */
class ChannelSplitterTest {
    // (answer text, thought-channel text) as LiteRT-LM delivers them per message
    private fun answer(t: String) = t to null
    private fun thought(t: String) = "" to t

    private fun run(vararg msgs: Pair<String, String?>): String {
        val s = ChannelSplitter()
        return (msgs.flatMap { (a, th) -> s.accept(a, th) } + s.finish()).joinToString("")
    }

    @Test fun plainAnswer() = assertEquals("Paris.", ThinkTags.strip(run(answer("Paris"), answer("."))))

    @Test fun thoughtThenAnswerHidesThought() {
        val raw = run(thought("hmm"), thought(" ok"), answer("Rome."))
        assertEquals("<think>hmm ok</think>Rome.", raw)
        assertEquals("Rome.", ThinkTags.strip(raw))
    }

    @Test fun thoughtOnlyIsPromotedToAnswer() {
        val raw = run(thought("The capital"), thought(" of Italy is Rome."))
        assertEquals("The capital of Italy is Rome.", ThinkTags.strip(raw))
    }
}
