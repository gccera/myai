package com.gccera.myai.engine.code

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.eclipse.jgit.api.Git
import org.eclipse.jgit.api.ListBranchCommand
import org.eclipse.jgit.lib.Config
import org.eclipse.jgit.lib.ProgressMonitor
import org.eclipse.jgit.storage.file.FileBasedConfig
import org.eclipse.jgit.storage.file.WindowCacheConfig
import org.eclipse.jgit.util.FS
import org.eclipse.jgit.util.SystemReader
import org.eclipse.jgit.transport.CredentialsProvider
import org.eclipse.jgit.transport.UsernamePasswordCredentialsProvider
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton

data class GitStatus(
    val branch: String,
    val changed: List<String>,
    val untracked: List<String>,
    val branches: List<String>,
    val remote: String?,
    val lastCommit: String?,
) { val clean get() = changed.isEmpty() && untracked.isEmpty() }

/**
 * Git via JGit 5.13 (pure Java; works on Android 8+). Network operations (clone/pull/push) are only
 * performed when the user taps them, using a GitHub personal access token from [SecretStore].
 */
@Singleton
class GitService(private val token: () -> String?) {

    @Inject constructor(secrets: SecretStore) : this({ secrets.get(SecretStore.GITHUB_TOKEN) })

    init { IsolatedSystemReader.install() }

    private fun creds(): CredentialsProvider? =
        token()?.takeIf { it.isNotBlank() }?.let { UsernamePasswordCredentialsProvider("x-access-token", it) }

    fun isRepo(dir: File) = File(dir, ".git").isDirectory

    suspend fun init(dir: File) = withContext(Dispatchers.IO) { Git.init().setDirectory(dir).call().close() }

    suspend fun clone(url: String, dir: File, onProgress: (String) -> Unit) = withContext(Dispatchers.IO) {
        require(url.startsWith("https://") || url.startsWith("file:")) { "Use an https:// repository URL" }
        Git.cloneRepository().setURI(url).setDirectory(dir).setCredentialsProvider(creds())
            .setProgressMonitor(Progress(onProgress)).call().close()
    }

    suspend fun status(dir: File): GitStatus = withContext(Dispatchers.IO) {
        Git.open(dir).use { git ->
            val st = git.status().call()
            val repo = git.repository
            GitStatus(
                branch = repo.branch ?: "(detached)",
                changed = (st.modified + st.changed + st.added + st.removed + st.missing).sorted(),
                untracked = st.untracked.sorted(),
                branches = git.branchList().setListMode(ListBranchCommand.ListMode.ALL).call().map { it.name.removePrefix("refs/heads/").removePrefix("refs/remotes/") }.distinct(),
                remote = repo.config.getString("remote", "origin", "url"),
                lastCommit = runCatching { git.log().setMaxCount(1).call().firstOrNull()?.let { "${it.name.take(7)} ${it.shortMessage}" } }.getOrNull(),
            )
        }
    }

    /** Stages everything (including deletions) and commits. */
    suspend fun commitAll(dir: File, message: String, name: String, email: String): String = withContext(Dispatchers.IO) {
        require(message.isNotBlank()) { "Commit message required" }
        Git.open(dir).use { git ->
            git.add().addFilepattern(".").call()
            git.add().addFilepattern(".").setUpdate(true).call()
            val c = git.commit().setMessage(message)
                .setAuthor(name.ifBlank { "MyAI user" }, email.ifBlank { "user@localhost" })
                .setCommitter(name.ifBlank { "MyAI user" }, email.ifBlank { "user@localhost" })
                .call()
            c.name.take(7)
        }
    }

    suspend fun createBranch(dir: File, name: String, checkout: Boolean = true) = withContext(Dispatchers.IO) {
        Git.open(dir).use { git -> if (checkout) git.checkout().setCreateBranch(true).setName(name).call() else git.branchCreate().setName(name).call() }
        Unit
    }

    suspend fun checkout(dir: File, branch: String) = withContext(Dispatchers.IO) {
        Git.open(dir).use { git ->
            val local = git.branchList().call().any { it.name == "refs/heads/$branch" }
            val remoteName = branch.removePrefix("origin/")
            if (local) git.checkout().setName(branch).call()
            else if (branch.startsWith("origin/")) git.checkout().setCreateBranch(true).setName(remoteName).setStartPoint(branch)
                .setUpstreamMode(org.eclipse.jgit.api.CreateBranchCommand.SetupUpstreamMode.TRACK).call()
            else git.checkout().setName(branch).call()
        }
        Unit
    }

    suspend fun pull(dir: File, onProgress: (String) -> Unit): String = withContext(Dispatchers.IO) {
        Git.open(dir).use { git ->
            val r = git.pull().setCredentialsProvider(creds()).setProgressMonitor(Progress(onProgress)).call()
            if (r.isSuccessful) "Pulled: ${r.mergeResult?.mergeStatus ?: "up to date"}" else "Pull finished with conflicts: resolve them, then commit."
        }
    }

    suspend fun push(dir: File, onProgress: (String) -> Unit): String = withContext(Dispatchers.IO) {
        Git.open(dir).use { git ->
            requireNotNull(creds()) { "Add a GitHub token in Settings → Git first." }
            val results = git.push().setCredentialsProvider(creds()).setProgressMonitor(Progress(onProgress)).call()
            results.flatMap { it.remoteUpdates }.joinToString("\n") { "${it.remoteName}: ${it.status}" }.ifBlank { "Pushed" }
        }
    }

    suspend fun setRemote(dir: File, url: String) = withContext(Dispatchers.IO) {
        Git.open(dir).use { git ->
            val cfg = git.repository.config
            cfg.setString("remote", "origin", "url", url)
            cfg.setString("remote", "origin", "fetch", "+refs/heads/*:refs/remotes/origin/*")
            cfg.save()
        }
    }

    private class Progress(val cb: (String) -> Unit) : ProgressMonitor {
        private var task = ""; private var total = 0; private var done = 0
        override fun start(totalTasks: Int) = Unit
        override fun beginTask(title: String, totalWork: Int) { task = title; total = totalWork; done = 0; cb(title) }
        override fun update(completed: Int) { done += completed; if (total > 0 && done % maxOf(1, total / 20) == 0) cb("$task ${done * 100 / total}%") }
        override fun endTask() = Unit
        override fun isCancelled() = false
    }
}

/**
 * Makes JGit ignore user/system/JGit-global config files (~/.gitconfig, /etc/gitconfig). On a phone
 * there are none worth reading, and unknown options in them (e.g. gpg.format=ssh) make JGit 5.13
 * refuse to commit. Repository-local .git/config is still used.
 */
internal class IsolatedSystemReader private constructor(private val base: SystemReader) : SystemReader() {
    private fun empty(parent: Config?, fs: FS) = object : FileBasedConfig(parent, null, fs) {
        // Disable automatic gc: JGit's gc lock uses java.lang.management, which Android lacks.
        init { setInt("gc", null, "auto", 0); setBoolean("gc", null, "autoDetach", false) }
        override fun load() = Unit
        override fun save() = Unit
        override fun isOutdated() = false
        override fun toString() = "isolated-empty-config"
    }
    override fun getHostname(): String = runCatching { base.hostname }.getOrDefault("localhost")
    override fun getenv(variable: String): String? = base.getenv(variable)
    override fun getProperty(key: String): String? = base.getProperty(key)
    override fun openUserConfig(parent: Config?, fs: FS): FileBasedConfig = empty(parent, fs)
    override fun openSystemConfig(parent: Config?, fs: FS): FileBasedConfig = empty(parent, fs)
    override fun openJGitConfig(parent: Config?, fs: FS): FileBasedConfig = empty(parent, fs)
    override fun getCurrentTime(): Long = base.currentTime
    override fun getTimezone(time: Long): Int = base.getTimezone(time)

    companion object {
        @Synchronized fun install() {
            val current = SystemReader.getInstance()
            if (current !is IsolatedSystemReader) SystemReader.setInstance(IsolatedSystemReader(current))
            // JGit publishes pack-cache stats via JMX (javax.management) by default; Android has no JMX.
            WindowCacheConfig().apply { setExposeStatsViaJmx(false) }.install()
        }
    }
}
