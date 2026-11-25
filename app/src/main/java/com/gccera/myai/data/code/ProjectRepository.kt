package com.gccera.myai.data.code

import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.documentfile.provider.DocumentFile
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton

@Serializable
data class ProjectRef(
    val id: String,
    val name: String,
    /** "local" = app storage (files/projects/<id>), "saf" = user-picked folder. */
    val kind: String,
    val uri: String? = null,
    val openedAt: Long = 0,
)

enum class ProjectTemplate(val label: String) { EMPTY("Empty"), WEB("Web page (HTML/CSS/JS)"), PYTHON("Python") }

/** Lists, creates and opens code projects. The project list is a small JSON file in app storage. */
@Singleton
class ProjectRepository @Inject constructor(
    @ApplicationContext private val context: Context,
    private val json: Json,
) {
    val projectsDir = File(context.filesDir, "projects").apply { mkdirs() }
    private val indexFile = File(context.filesDir, "projects.json")
    private val _projects = MutableStateFlow(read())
    val projects = _projects.asStateFlow()

    private fun read(): List<ProjectRef> = runCatching {
        json.decodeFromString(ListSerializer(ProjectRef.serializer()), indexFile.readText())
    }.getOrDefault(emptyList())

    private fun save(list: List<ProjectRef>) {
        indexFile.writeText(json.encodeToString(ListSerializer(ProjectRef.serializer()), list))
        _projects.value = list.sortedByDescending { it.openedAt }
    }

    fun fsFor(p: ProjectRef): ProjectFs =
        if (p.kind == "local") LocalFs(File(projectsDir, p.id)) else SafFs(context, Uri.parse(p.uri))

    fun localDirFor(id: String) = File(projectsDir, id)

    suspend fun createLocal(name: String, template: ProjectTemplate): ProjectRef = withContext(Dispatchers.IO) {
        require(name.isNotBlank()) { "Name required" }
        val id = uniqueId(name)
        val dir = File(projectsDir, id).apply { mkdirs() }
        when (template) {
            ProjectTemplate.EMPTY -> File(dir, "README.md").writeText("# $name\n")
            ProjectTemplate.WEB -> {
                File(dir, "index.html").writeText(Templates.HTML.replace("{{title}}", name))
                File(dir, "style.css").writeText(Templates.CSS)
                File(dir, "script.js").writeText(Templates.JS)
            }
            ProjectTemplate.PYTHON -> File(dir, "main.py").writeText(Templates.PY)
        }
        add(ProjectRef(id, name, "local", openedAt = System.currentTimeMillis()))
    }

    /** Registers an empty app-storage folder (used by Git clone). */
    fun reserveLocal(name: String): Pair<String, File> { val id = uniqueId(name); return id to File(projectsDir, id) }

    fun addLocal(id: String, name: String) = add(ProjectRef(id, name, "local", openedAt = System.currentTimeMillis()))

    /** Opens a folder chosen with ACTION_OPEN_DOCUMENT_TREE and keeps access across restarts. */
    fun addSaf(uri: Uri): ProjectRef {
        context.contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION)
        val name = DocumentFile.fromTreeUri(context, uri)?.name ?: "Folder"
        _projects.value.firstOrNull { it.uri == uri.toString() }?.let { return touch(it) }
        return add(ProjectRef("saf-" + System.currentTimeMillis(), name, "saf", uri.toString(), System.currentTimeMillis()))
    }

    fun touch(p: ProjectRef): ProjectRef {
        val t = p.copy(openedAt = System.currentTimeMillis())
        save(_projects.value.map { if (it.id == p.id) t else it })
        return t
    }

    /** Removes from the list; app-storage projects are deleted, SAF folders are left untouched. */
    suspend fun remove(p: ProjectRef) = withContext(Dispatchers.IO) {
        if (p.kind == "local") File(projectsDir, p.id).deleteRecursively()
        else runCatching { context.contentResolver.releasePersistableUriPermission(Uri.parse(p.uri), Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION) }
        save(_projects.value.filterNot { it.id == p.id })
    }

    suspend fun deleteAll() = withContext(Dispatchers.IO) { projectsDir.deleteRecursively(); projectsDir.mkdirs(); save(emptyList()) }

    private fun add(p: ProjectRef): ProjectRef { save(_projects.value.filterNot { it.id == p.id } + p); return p }

    private fun uniqueId(name: String): String {
        val base = name.lowercase().replace(Regex("[^a-z0-9._-]+"), "-").trim('-').ifEmpty { "project" }
        var id = base; var i = 2
        while (File(projectsDir, id).exists() || _projects.value.any { it.id == id }) id = "$base-${i++}"
        return id
    }
}

object Templates {
    const val HTML = """<!DOCTYPE html>
<html lang="en">
<head>
  <meta charset="utf-8">
  <meta name="viewport" content="width=device-width, initial-scale=1">
  <title>{{title}}</title>
  <link rel="stylesheet" href="style.css">
</head>
<body>
  <h1>Hello!</h1>
  <button id="btn">Click me</button>
  <p id="out"></p>
  <script src="script.js"></script>
</body>
</html>
"""
    const val CSS = """body { font-family: system-ui, sans-serif; margin: 2rem; }
button { padding: .6rem 1rem; border-radius: .5rem; }
"""
    const val JS = """let clicks = 0;
document.getElementById('btn').addEventListener('click', () => {
  clicks++;
  document.getElementById('out').textContent = `Clicked ${'$'}{clicks} time(s)`;
  console.log('clicks =', clicks);
});
"""
    const val PY = """def greet(name: str) -> str:
    return f"Hello, {name}!"


if __name__ == "__main__":
    print(greet("world"))
    for i in range(3):
        print("square of", i, "is", i * i)
"""
}

/** File extension → (display language, TextMate scope). */
object CodeLanguages {
    private val byExt = mapOf(
        "kt" to ("Kotlin" to "source.kotlin"), "kts" to ("Kotlin" to "source.kotlin"),
        "java" to ("Java" to "source.java"), "py" to ("Python" to "source.python"),
        "js" to ("JavaScript" to "source.js"), "mjs" to ("JavaScript" to "source.js"), "cjs" to ("JavaScript" to "source.js"),
        "html" to ("HTML" to "text.html.basic"), "htm" to ("HTML" to "text.html.basic"),
        "css" to ("CSS" to "source.css"), "json" to ("JSON" to "source.json"),
        "md" to ("Markdown" to "text.html.markdown"), "markdown" to ("Markdown" to "text.html.markdown"),
    )
    fun of(path: String): Pair<String, String>? = byExt[path.substringAfterLast('.', "").lowercase()]
    fun name(path: String) = of(path)?.first ?: "Text"
    fun isWeb(path: String) = path.substringAfterLast('.').lowercase() in setOf("html", "htm", "js", "css")
    fun isPython(path: String) = path.endsWith(".py", ignoreCase = true)
    fun isBinary(path: String) = path.substringAfterLast('.', "").lowercase() in setOf(
        "png", "jpg", "jpeg", "gif", "webp", "ico", "pdf", "zip", "jar", "apk", "aab", "so", "class", "bin", "mp3", "mp4", "ttf", "otf", "woff", "woff2",
    )
}
