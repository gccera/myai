package com.gccera.myai.engine.llm

/** Post-processing for inline code completions (pure Kotlin, unit-tested). */
object CodeCompletionText {
    /** Removes fences/think tags, the <CURSOR> marker, and any text the model echoed from the context. */
    fun clean(raw: String, before: String, after: String, maxLines: Int): String {
        var s = raw.replace(Regex("(?s)<think>.*?</think>"), "")
        s = Regex("(?s)```[a-zA-Z0-9_+-]*\\n?(.*?)(```|$)").find(s)?.groupValues?.get(1) ?: s
        s = s.replace("<CURSOR>", "").trimEnd()
        // Drop an echoed copy of the current line's beginning.
        val lineStart = before.substringAfterLast('\n')
        if (lineStart.isNotBlank() && s.startsWith(lineStart)) s = s.removePrefix(lineStart)
        else if (lineStart.isNotBlank() && s.trimStart().startsWith(lineStart.trim())) s = s.trimStart().removePrefix(lineStart.trim())
        // Drop leading lines that just repeat the last lines before the cursor.
        val recent = before.lines().takeLast(4).map { it.trim() }.filter { it.isNotEmpty() }.toSet()
        s = s.lines().dropWhile { it.isBlank() || it.trim() in recent }.joinToString("\n")
        // Cursor already sits after this line's indentation: don't indent the completion twice.
        if (lineStart.isNotEmpty() && lineStart.isBlank() && s.startsWith(lineStart)) s = s.removePrefix(lineStart)
        // Stop where the model starts repeating the code after the cursor.
        val nextLine = after.trimStart('\n').substringBefore('\n').trim()
        val lines = s.lines().takeWhile { nextLine.isEmpty() || it.trim() != nextLine }
        return lines.take(maxLines).joinToString("\n").trimEnd()
    }
}
