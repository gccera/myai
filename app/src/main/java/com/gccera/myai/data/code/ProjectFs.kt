package com.gccera.myai.data.code

import android.content.Context
import android.net.Uri
import android.provider.DocumentsContract
import androidx.documentfile.provider.DocumentFile
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

data class FsEntry(val path: String, val name: String, val isDir: Boolean, val size: Long)

/**
 * A project's files. Paths are relative to the project root and use "/" ("" = root).
 * Two backends: app-storage folders (java.io.File; needed for Git and Python's working directory)
 * and folders the user picks with the Storage Access Framework (DocumentFile).
 */
interface ProjectFs {
    val isLocal: Boolean
    /** Absolute folder for app-storage projects (Git, Python cwd); null for SAF projects. */
    val localRoot: File?
    suspend fun list(path: String): List<FsEntry>
    suspend fun readText(path: String): String
    suspend fun readBytes(path: String): ByteArray?
    suspend fun writeText(path: String, text: String)
    suspend fun createFile(parent: String, name: String): String
    suspend fun createDir(parent: String, name: String): String
    suspend fun rename(path: String, newName: String): String
    suspend fun delete(path: String)
    /** All file paths (skips .git and very large files), for project search. */
    suspend fun allFiles(maxFiles: Int = 3000): List<FsEntry>

    companion object {
        fun join(parent: String, name: String) = if (parent.isEmpty()) name else "$parent/$name"
        fun parentOf(path: String) = path.substringBeforeLast('/', "")
        val IGNORED_DIRS = setOf(".git", "node_modules", "build", ".gradle", "__pycache__", ".idea")
        fun checkName(name: String) = require(name.isNotBlank() && !name.contains('/') && name != "." && name != "..") { "Invalid name" }
    }
}

class LocalFs(override val localRoot: File) : ProjectFs {
    override val isLocal = true
    private fun f(path: String): File {
        val file = File(localRoot, path).canonicalFile
        require(file.path.startsWith(localRoot.canonicalPath)) { "Path escapes the project" }
        return file
    }

    override suspend fun list(path: String) = withContext(Dispatchers.IO) {
        (f(path).listFiles() ?: emptyArray()).map { FsEntry(ProjectFs.join(path, it.name), it.name, it.isDirectory, it.length()) }
            .sortedWith(compareBy({ !it.isDir }, { it.name.lowercase() }))
    }
    override suspend fun readText(path: String) = withContext(Dispatchers.IO) { f(path).readText() }
    override suspend fun readBytes(path: String) = withContext(Dispatchers.IO) { f(path).takeIf { it.isFile }?.readBytes() }
    override suspend fun writeText(path: String, text: String) = withContext(Dispatchers.IO) { f(path).apply { parentFile?.mkdirs() }.writeText(text) }
    override suspend fun createFile(parent: String, name: String) = withContext(Dispatchers.IO) {
        ProjectFs.checkName(name); val p = ProjectFs.join(parent, name)
        require(f(p).createNewFile()) { "$name already exists" }; p
    }
    override suspend fun createDir(parent: String, name: String) = withContext(Dispatchers.IO) {
        ProjectFs.checkName(name); val p = ProjectFs.join(parent, name)
        require(f(p).mkdirs()) { "$name already exists" }; p
    }
    override suspend fun rename(path: String, newName: String) = withContext(Dispatchers.IO) {
        ProjectFs.checkName(newName); val target = ProjectFs.join(ProjectFs.parentOf(path), newName)
        require(!f(target).exists()) { "$newName already exists" }
        require(f(path).renameTo(f(target))) { "Rename failed" }; target
    }
    override suspend fun delete(path: String) = withContext(Dispatchers.IO) { require(path.isNotEmpty()); f(path).deleteRecursively(); Unit }
    override suspend fun allFiles(maxFiles: Int) = withContext(Dispatchers.IO) {
        localRoot.walkTopDown().onEnter { it == localRoot || it.name !in ProjectFs.IGNORED_DIRS }
            .filter { it.isFile && it.length() < 2_000_000 }.take(maxFiles)
            .map { FsEntry(it.relativeTo(localRoot).invariantSeparatorsPath, it.name, false, it.length()) }.toList()
    }
}

class SafFs(private val context: Context, private val treeUri: Uri) : ProjectFs {
    override val isLocal = false
    override val localRoot: File? = null
    private val root: DocumentFile = DocumentFile.fromTreeUri(context, treeUri) ?: error("Folder not accessible")
    private val cache = HashMap<String, DocumentFile>()

    private fun doc(path: String): DocumentFile {
        if (path.isEmpty()) return root
        cache[path]?.let { if (it.exists()) return it }
        var cur = root
        for (seg in path.split('/')) cur = cur.findFile(seg) ?: throw java.io.FileNotFoundException(path)
        cache[path] = cur
        return cur
    }

    override suspend fun list(path: String) = withContext(Dispatchers.IO) {
        doc(path).listFiles().map { d ->
            val p = ProjectFs.join(path, d.name ?: "?"); cache[p] = d
            FsEntry(p, d.name ?: "?", d.isDirectory, d.length())
        }.sortedWith(compareBy({ !it.isDir }, { it.name.lowercase() }))
    }
    override suspend fun readText(path: String) = withContext(Dispatchers.IO) {
        context.contentResolver.openInputStream(doc(path).uri)!!.use { it.bufferedReader().readText() }
    }
    override suspend fun readBytes(path: String) = withContext(Dispatchers.IO) {
        runCatching { context.contentResolver.openInputStream(doc(path).uri)!!.use { it.readBytes() } }.getOrNull()
    }
    override suspend fun writeText(path: String, text: String) = withContext(Dispatchers.IO) {
        context.contentResolver.openOutputStream(doc(path).uri, "wt")!!.use { it.write(text.toByteArray()) }
    }
    override suspend fun createFile(parent: String, name: String) = withContext(Dispatchers.IO) {
        ProjectFs.checkName(name)
        require(doc(parent).findFile(name) == null) { "$name already exists" }
        // "application/octet-stream" keeps the exact file name (no extension added by the provider).
        doc(parent).createFile("application/octet-stream", name) ?: error("Could not create $name")
        ProjectFs.join(parent, name)
    }
    override suspend fun createDir(parent: String, name: String) = withContext(Dispatchers.IO) {
        ProjectFs.checkName(name)
        doc(parent).createDirectory(name) ?: error("Could not create $name")
        ProjectFs.join(parent, name)
    }
    override suspend fun rename(path: String, newName: String) = withContext(Dispatchers.IO) {
        ProjectFs.checkName(newName)
        val d = doc(path); require(d.renameTo(newName)) { "Rename failed" }
        cache.keys.filter { it == path || it.startsWith("$path/") }.forEach { cache.remove(it) }
        ProjectFs.join(ProjectFs.parentOf(path), newName)
    }
    override suspend fun delete(path: String) = withContext(Dispatchers.IO) {
        require(path.isNotEmpty())
        DocumentsContract.deleteDocument(context.contentResolver, doc(path).uri)
        cache.keys.filter { it == path || it.startsWith("$path/") }.forEach { cache.remove(it) }
    }
    override suspend fun allFiles(maxFiles: Int): List<FsEntry> = withContext(Dispatchers.IO) {
        val out = ArrayList<FsEntry>()
        fun walk(d: DocumentFile, path: String) {
            for (c in d.listFiles()) {
                if (out.size >= maxFiles) return
                val p = ProjectFs.join(path, c.name ?: continue)
                if (c.isDirectory) { if (c.name !in ProjectFs.IGNORED_DIRS) walk(c, p) }
                else if (c.length() < 2_000_000) { cache[p] = c; out += FsEntry(p, c.name!!, false, c.length()) }
            }
        }
        walk(root, ""); out
    }
}
