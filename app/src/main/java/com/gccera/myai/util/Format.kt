package com.gccera.myai.util

import java.util.Locale

fun formatBytes(bytes: Long): String {
    if (bytes < 1024) return "$bytes B"
    val units = listOf("KB", "MB", "GB", "TB")
    var v = bytes / 1024.0
    var i = 0
    while (v >= 1024 && i < units.lastIndex) { v /= 1024; i++ }
    return String.format(Locale.US, if (v >= 100) "%.0f %s" else "%.1f %s", v, units[i])
}

fun formatDuration(ms: Long): String {
    val s = ms / 1000
    return if (s >= 60) "${s / 60}m ${s % 60}s" else "${s}s"
}

/**
 * Reasoning models (e.g. Qwen3) wrap their chain of thought in <think>…</think>.
 * We hide it: completed blocks are removed; an unfinished block shows as "Thinking…".
 */
object ThinkTags {
    private val closed = Regex("(?s)<think>.*?</think>")
    fun strip(text: String): String {
        val noClosed = text.replace(closed, "")
        val open = noClosed.indexOf("<think>")
        return (if (open >= 0) noClosed.substring(0, open) else noClosed).trimStart()
    }
    fun isThinking(text: String): Boolean = text.replace(closed, "").contains("<think>")
}
