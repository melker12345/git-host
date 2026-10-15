package com.localgithub.githost.ssh

import android.content.Context
import android.content.Intent
import android.util.Log
import com.localgithub.githost.EventBus
import com.localgithub.githost.service.GitHostService
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import org.apache.sshd.server.channel.ChannelSession
import org.apache.sshd.server.command.Command
import org.apache.sshd.server.ExitCallback
import org.eclipse.jgit.lib.Repository
import org.eclipse.jgit.storage.file.FileRepositoryBuilder
import org.eclipse.jgit.transport.ReceivePack
import org.eclipse.jgit.transport.UploadPack
import java.io.File
import java.io.InputStream
import java.io.OutputStream

private const val TAG = "GitCommand"

/**
 * The SSH exec commands the server runs, one instance per channel. MINA SSHD
 * hands each command its streams and an exit callback before calling
 * [Command.start]; the subclasses do the work and report an exit status.
 *
 * Git commands run on their own IO coroutine because [Command.start] must
 * return promptly; the session stays open until [exit] is called. They also
 * tell [GitHostService] when a session opens and closes so the idle timeout
 * never stops the server in the middle of a transfer.
 */
sealed class GitCommand : Command {

    protected var _in: InputStream? = null
    protected var _out: OutputStream? = null
    protected var _err: OutputStream? = null
    protected var _cb: ExitCallback? = null

    override fun setInputStream(`in`: InputStream) { _in = `in` }
    override fun setOutputStream(out: OutputStream) { _out = out }
    override fun setErrorStream(err: OutputStream) { _err = err }
    override fun setExitCallback(callback: ExitCallback) { _cb = callback }

    /** Flushes both output streams and reports [code] as the exit status, closing the channel. */
    protected fun exit(code: Int) {
        _out?.flush()
        _err?.flush()
        _cb?.onExit(code)
    }

    /** Writes [msg] to stderr, which the git client prints as `git-host: <msg>`. */
    protected fun sendError(msg: String) {
        _err?.write("git-host: $msg\n".toByteArray())
        _err?.flush()
    }

    // -------------------------------------------------------------------------

    /** Serves `git fetch` / `git clone`. A missing repository is an error, never created. */
    class UploadPackCommand(
        private val repoPath: File,
        private val context: Context,
        private val deviceLabel: String,
    ) : GitCommand() {
        override fun start(channel: ChannelSession, env: org.apache.sshd.server.Environment) {
            signalSession(context, open = true)
            CoroutineScope(Dispatchers.IO + SupervisorJob()).launch {
                try {
                    openRepo(repoPath)?.use { repo ->
                        Log.i(TAG, "FETCH repo=${repoPath.name} label=$deviceLabel")
                        val up = UploadPack(repo)
                        up.isBiDirectionalPipe = true
                        up.upload(_in!!, _out!!, _err)
                        broadcastEvent(EventBus.GitEvent.PULL, repoPath.name, deviceLabel)
                        exit(0)
                    } ?: run {
                        sendError("repository not found: ${repoPath.name}")
                        exit(128)
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "UploadPack error", e)
                    sendError(e.message ?: "internal error")
                    exit(1)
                } finally {
                    signalSession(context, open = false)
                }
            }
        }

        override fun destroy(channel: ChannelSession) {}
    }

    // -------------------------------------------------------------------------

    /**
     * Serves `git push`. Repositories are created on first push, and
     * non-fast-forward updates and deletions are allowed, because a mirror
     * sync makes the host an exact copy of the client.
     *
     * A [PushSummary] of the applied ref updates is emitted afterwards. Pushes
     * that change nothing (most pushes of a sync) emit nothing.
     */
    class ReceivePackCommand(
        private val repoPath: File,
        private val context: Context,
        private val deviceLabel: String,
    ) : GitCommand() {
        override fun start(channel: ChannelSession, env: org.apache.sshd.server.Environment) {
            signalSession(context, open = true)
            CoroutineScope(Dispatchers.IO + SupervisorJob()).launch {
                var summary: PushSummary? = null
                var packBytes = -1L
                try {
                    ensureRepo(repoPath).use { repo ->
                        Log.i(TAG, "PUSH  repo=${repoPath.name} label=$deviceLabel")
                        val rp = ReceivePack(repo)
                        rp.isBiDirectionalPipe = true
                        rp.setAllowCreates(true)
                        rp.setAllowNonFastForwards(true)
                        // Runs after the refs are updated and before the client
                        // is told the result. A failure here must never fail
                        // the push itself, so it is only logged.
                        rp.setPostReceiveHook { pack, commands ->
                            try {
                                summary = PushSummary.of(repo, commands)
                                packBytes = runCatching { pack.packSize }.getOrDefault(-1L)
                            } catch (e: Exception) {
                                Log.w(TAG, "Push summary failed (non-fatal)", e)
                            }
                        }
                        rp.receive(_in!!, _out!!, _err)
                    }
                    exit(0)
                } catch (e: Exception) {
                    Log.e(TAG, "ReceivePack error", e)
                    sendError(e.message ?: "internal error")
                    exit(1)
                } finally {
                    signalSession(context, open = false)
                }

                summary?.takeUnless { it.isEmpty }?.let { s ->
                    Log.i(TAG, "PUSH  repo=${repoPath.name} branches=${s.branches} commits=${s.commits} bytes=$packBytes")
                    EventBus.emitGitEvent(EventBus.GitEvent(
                        type = EventBus.GitEvent.PUSH,
                        repo = repoPath.name,
                        label = deviceLabel,
                        bytes = packBytes,
                        push = s,
                    ))
                }
            }
        }

        override fun destroy(channel: ChannelSession) {}
    }

    // -------------------------------------------------------------------------

    /** Answers `git-host-ls`: the bare repositories under baseDir, one per line. */
    class ListReposCommand(private val baseDir: File) : GitCommand() {
        override fun start(channel: ChannelSession, env: org.apache.sshd.server.Environment) {
            val names = baseDir.listFiles()
                ?.filter { it.isDirectory && it.name.endsWith(".git") }
                ?.map { it.name }?.sorted() ?: emptyList()
            _out?.write(names.joinToString("") { it + "\n" }.toByteArray())
            exit(0)
        }

        override fun destroy(channel: ChannelSession) {}
    }

    // -------------------------------------------------------------------------

    /** Refuses the request with [reason] on stderr and exit status 1. */
    class RejectCommand(private val reason: String) : GitCommand() {
        override fun start(channel: ChannelSession, env: org.apache.sshd.server.Environment) {
            sendError(reason)
            exit(1)
        }

        override fun destroy(channel: ChannelSession) {}
    }
}

// --- helpers -----------------------------------------------------------------

/** Tells [GitHostService] a git session opened or closed, for its idle-timeout bookkeeping. */
private fun signalSession(context: Context, open: Boolean) {
    val action = if (open) GitHostService.ACTION_SESSION_OPEN else GitHostService.ACTION_SESSION_CLOSE
    context.startService(Intent(context, GitHostService::class.java).setAction(action))
}

/** Publishes a git event without a push summary (used for fetches). */
private fun broadcastEvent(type: String, repo: String, label: String) {
    EventBus.emitGitEvent(EventBus.GitEvent(type = type, repo = repo, label = label))
}

/** Opens the bare repository at [path], or returns null if it does not exist. */
private fun openRepo(path: File): Repository? {
    if (!path.exists()) return null
    return FileRepositoryBuilder()
        .setGitDir(path)
        .setBare()
        .readEnvironment()
        .build()
}

/**
 * Opens the bare repository at [path], first creating it with HEAD pointing
 * at `main` if it does not exist yet (a repository is created by its first push).
 */
private fun ensureRepo(path: File): Repository {
    if (!path.exists()) {
        path.mkdirs()
        val repo = FileRepositoryBuilder()
            .setGitDir(path)
            .setBare()
            .build()
        repo.create(true)
        // Set default branch to main
        repo.updateRef("HEAD").apply {
            link("refs/heads/main")
        }
        Log.i(TAG, "Initialized bare repo: ${path.name}")
    }
    return FileRepositoryBuilder()
        .setGitDir(path)
        .setBare()
        .readEnvironment()
        .build()
}
