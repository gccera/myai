package com.gccera.myai.engine.rag

/** Splits documents into ~[target]-character chunks on paragraph/sentence boundaries, with overlap. */
object TextChunker {
    fun chunk(text: String, target: Int = 900, overlap: Int = 150): List<String> {
        val clean = text.replace("\r", "").replace(Regex("[ \t]+"), " ").replace(Regex("\n{3,}"), "\n\n").trim()
        if (clean.isEmpty()) return emptyList()
        val pieces = clean.split(Regex("(?<=\n\n)|(?<=[.!?])\\s+")).filter { it.isNotBlank() }
        val chunks = ArrayList<String>()
        val cur = StringBuilder()
        for (p in pieces) {
            if (cur.length + p.length > target && cur.isNotEmpty()) {
                chunks += cur.toString().trim()
                val tail = cur.takeLast(overlap).toString()
                cur.clear(); cur.append(tail.substringAfter(' ', tail))
            }
            if (p.length > target) { // very long sentence: hard split
                p.chunked(target).forEach { part -> if (cur.isNotEmpty()) { chunks += cur.toString().trim(); cur.clear() }; cur.append(part) }
            } else cur.append(if (cur.isEmpty()) "" else " ").append(p.trim())
        }
        if (cur.isNotBlank()) chunks += cur.toString().trim()
        return chunks
    }
}
