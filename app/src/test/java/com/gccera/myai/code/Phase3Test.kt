package com.gccera.myai.code

import com.gccera.myai.data.code.CodeLanguages
import com.gccera.myai.data.code.LocalFs
import com.gccera.myai.engine.code.CloudClient
import com.gccera.myai.engine.code.CodeAction
import com.gccera.myai.engine.code.CodeAssistant
import com.gccera.myai.engine.code.GitService
import com.gccera.myai.ui.code.CodeDiff
import com.github.difflib.text.DiffRow
import kotlinx.coroutines.runBlocking
import org.eclipse.jgit.api.Git
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class Phase3Test {
    @get:Rule val tmp = TemporaryFolder()

    @Test fun gitRoundTrip() = runBlocking {
        val git = GitService { "test-token" }
        val repo = tmp.newFolder("repo")
        git.init(repo)
        File(repo, "main.py").writeText("print('hi')\n")
        var st = git.status(repo)
        assertEquals(listOf("main.py"), st.untracked)
        val sha = git.commitAll(repo, "first", "Tester", "t@example.com")
        assertEquals(7, sha.length)
        st = git.status(repo)
        assertTrue(st.clean)

        // branch + checkout
        git.createBranch(repo, "feature")
        assertEquals("feature", git.status(repo).branch)
        File(repo, "main.py").writeText("print('feature')\n")
        git.commitAll(repo, "feature work", "Tester", "t@example.com")
        git.checkout(repo, "master".takeIf { git.status(repo).branches.contains("master") } ?: "main")
        assertEquals("print('hi')\n", File(repo, "main.py").readText())

        // push to a local bare "remote", clone it, then pull a new commit
        val bare = tmp.newFolder("remote.git")
        Git.init().setBare(true).setDirectory(bare).call().close()
        git.setRemote(repo, bare.toURI().toString())
        git.push(repo) {}
        val clone = File(tmp.root, "clone")
        git.clone(bare.toURI().toString(), clone) {}
        assertEquals("print('hi')\n", File(clone, "main.py").readText())
        File(repo, "README.md").writeText("# hello\n")
        git.commitAll(repo, "docs", "Tester", "t@example.com")
        git.push(repo) {}
        git.pull(clone) {}
        assertTrue(File(clone, "README.md").exists())
        assertEquals("docs", git.status(clone).lastCommit!!.substringAfter(' '))
    }

    @Test fun cloudSseParsing() {
        assertEquals("Hel", CloudClient.parseDelta("""{"id":"x","choices":[{"index":0,"delta":{"content":"Hel"}}]}"""))
        assertNull(CloudClient.parseDelta("""{"choices":[{"delta":{"role":"assistant"}}]}"""))
        assertNull(CloudClient.parseDelta("not json"))
        assertEquals("api.openai.com", CloudClient.host("https://api.openai.com/v1"))
    }

    @Test fun assistantPromptsAndExtraction() {
        val (_, fix) = CodeAssistant.prompt(CodeAction.FIX, "Python", "a.py", "print(1", false, "SyntaxError")
        assertTrue(fix.contains("SyntaxError") && fix.contains("```python") && fix.contains("COMPLETE updated code"))
        val reply = "<think>hmm</think>Here:\n```python\nprint(1)\n```\n- fixed paren"
        assertEquals("print(1)", CodeAssistant.extractCode(reply))
        assertNull(CodeAssistant.extractCode("no code here"))
    }

    @Test fun diffRows() {
        val rows = CodeDiff.rows("a\nb\nc", "a\nB\nc\nd")
        assertEquals(listOf(DiffRow.Tag.EQUAL, DiffRow.Tag.CHANGE, DiffRow.Tag.EQUAL, DiffRow.Tag.INSERT), rows.map { it.tag })
    }

    @Test fun localFsOperationsAndSandbox() = runBlocking {
        val fs = LocalFs(tmp.newFolder("proj"))
        val p = fs.createFile("", "main.py"); fs.writeText(p, "x = 1\n")
        fs.createDir("", "src"); fs.createFile("src", "util.py")
        assertEquals(listOf("src", "main.py"), fs.list("").map { it.name })
        assertEquals("src/helpers.py", fs.rename("src/util.py", "helpers.py"))
        assertEquals(setOf("main.py", "src/helpers.py"), fs.allFiles().map { it.path }.toSet())
        fs.delete("src")
        assertEquals(listOf("main.py"), fs.list("").map { it.name })
        assertTrue(runCatching { fs.readText("../outside.txt") }.isFailure) // can't escape the project
        assertTrue(runCatching { fs.createFile("", "a/b") }.isFailure)
    }

    @Test fun languages() {
        assertEquals("source.kotlin", CodeLanguages.of("App.kt")?.second)
        assertEquals("text.html.basic", CodeLanguages.of("index.HTML")?.second)
        assertTrue(CodeLanguages.isWeb("style.css") && CodeLanguages.isPython("x.py"))
        assertFalse(CodeLanguages.isBinary("main.py")); assertTrue(CodeLanguages.isBinary("logo.png"))
    }
}
