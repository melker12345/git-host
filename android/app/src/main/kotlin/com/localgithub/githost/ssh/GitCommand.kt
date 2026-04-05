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
import org.apache.sshd.server.session.ServerSession
import org.eclipse.jgit.diff.DiffEntry
import org.eclipse.jgit.diff.DiffFormatter
import org.eclipse.jgit.lib.ObjectId
import org.eclipse.jgit.lib.Repository
import org.eclipse.jgit.revwalk.RevWalk
import org.eclipse.jgit.storage.file.FileRepositoryBuilder
import org.eclipse.jgit.transport.ReceiveCommand
import org.eclipse.jgit.transport.ReceivePack
import org.eclipse.jgit.transport.UploadPack
import org.eclipse.jgit.util.io.DisabledOutputStream
import java.io.File
import java.io.InputStream
import java.io.OutputStream

const val ACTION_GIT_EVENT    = "com.localgithub.githost.GIT_EVENT"

private const val TAG = "GitCommand"

sealed class GitCommand : Command {

    protected var _in: InputStream? = null
    protected var _out: OutputStream? = null
    protected var _err: OutputStream? = null
    protected var _cb: ExitCallback? = null

    override fun setInputStream(`in`: InputStream) { _in = `in` }
    override fun setOutputStream(out: OutputStream) { _out = out }
    override fun setErrorStream(err: OutputStream) { _err = err }
    override fun setExitCallback(callback: ExitCallback) { _cb = callback }

    protected fun exit(code: Int) {
        _out?.flush()
        _err?.flush()
        _cb?.onExit(code)
    }

    protected fun sendError(msg: String) {
        _err?.write("git-host: $msg\n".toByteArray())
        _err?.flush()
    }

    // -------------------------------------------------------------------------

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
                        broadcastEvent(context, "pull", repoPath.name, deviceLabel)
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

    class ReceivePackCommand(
        private val repoPath: File,
        private val context: Context,
        private val deviceLabel: String,
    ) : GitCommand() {
        override fun start(channel: ChannelSession, env: org.apache.sshd.server.Environment) {
            signalSession(context, open = true)
            CoroutineScope(Dispatchers.IO + SupervisorJob()).launch {
                // Captured inside receive block before repo is closed
                var capturedBranch = "unknown"
                var capturedOldId: ObjectId? = null
                var capturedNewId: ObjectId? = null
                var capturedBytes = -1L

                try {
                    val repo = ensureRepo(repoPath)
                    repo.use {
                        Log.i(TAG, "PUSH  repo=${repoPath.name} label=$deviceLabel")
                        val rp = ReceivePack(repo)
                        rp.setAllowCreates(true)
                        rp.setAllowNonFastForwards(true)
                        rp.receive(_in!!, _out!!, _err)
                        // Capture while repo is still open
                        val cmd = rp.getAllCommands()
                            .firstOrNull { it.result == ReceiveCommand.Result.OK }
                        capturedBranch = cmd?.refName?.removePrefix("refs/heads/") ?: "unknown"
                        capturedOldId  = cmd?.oldId
                        capturedNewId  = cmd?.newId
                        capturedBytes  = try { rp.packSize } catch (_: Exception) { -1L }
                    }
                    exit(0)
                } catch (e: Exception) {
                    Log.e(TAG, "ReceivePack error", e)
                    sendError(e.message ?: "internal error")
                    exit(1)
                    signalSession(context, open = false)
                    return@launch
                }
                // Metadata diff — separate repo handle, never affects git exit code
                try {
                    val oldId = capturedOldId
                    val newId = capturedNewId
                    val (added, modified, deleted) = if (oldId != null && newId != null) {
                        openRepo(repoPath)?.use { r -> diffStats(r, oldId, newId) }
                            ?: Triple(0, 0, 0)
                    } else Triple(0, 0, 0)
                    Log.i(TAG, "PUSH  branch=$capturedBranch bytes=$capturedBytes +$added ~$modified -$deleted")
                    broadcastPush(context, repoPath.name, deviceLabel, capturedBranch, capturedBytes, added, modified, deleted)
                } catch (e: Exception) {
                    Log.w(TAG, "Push metadata extraction failed (non-fatal)", e)
                    broadcastPush(context, repoPath.name, deviceLabel, capturedBranch, capturedBytes, 0, 0, 0)
                } finally {
                    signalSession(context, open = false)
                }
            }
        }

        override fun destroy(channel: ChannelSession) {}
    }

    // -------------------------------------------------------------------------

    class RejectCommand(private val reason: String) : GitCommand() {
        override fun start(channel: ChannelSession, env: org.apache.sshd.server.Environment) {
            sendError(reason)
            exit(1)
        }

        override fun destroy(channel: ChannelSession) {}
    }
}

// --- helpers -----------------------------------------------------------------

private fun signalSession(context: Context, open: Boolean) {
    val action = if (open) GitHostService.ACTION_SESSION_OPEN else GitHostService.ACTION_SESSION_CLOSE
    context.startService(Intent(context, GitHostService::class.java).setAction(action))
}

private fun broadcastEvent(context: Context, type: String, repo: String, label: String) {
    EventBus.emitGitEvent(EventBus.GitEvent(
        type = type,
        repo = repo,
        label = label
    ))
}

private fun broadcastPush(
    context: Context,
    repo: String,
    label: String,
    branch: String,
    bytes: Long,
    added: Int,
    modified: Int,
    deleted: Int,
) {
    EventBus.emitGitEvent(EventBus.GitEvent(
        type = "push",
        repo = repo,
        label = label,
        branch = branch,
        bytes = bytes,
        added = added,
        modified = modified,
        deleted = deleted
    ))
}

private fun diffStats(repo: Repository, oldId: ObjectId, newId: ObjectId): Triple<Int, Int, Int> {
    return try {
        RevWalk(repo).use { rw ->
            val newCommit = rw.parseCommit(newId)
            val oldTree = if (ObjectId.zeroId() == oldId) null
                          else rw.parseCommit(oldId).tree
            DiffFormatter(DisabledOutputStream.INSTANCE).use { df ->
                df.setRepository(repo)
                val entries = df.scan(oldTree, newCommit.tree)
                var added = 0; var modified = 0; var deleted = 0
                for (entry in entries) {
                    when (entry.changeType) {
                        DiffEntry.ChangeType.ADD    -> added++
                        DiffEntry.ChangeType.MODIFY -> modified++
                        DiffEntry.ChangeType.DELETE -> deleted++
                        DiffEntry.ChangeType.RENAME -> modified++
                        DiffEntry.ChangeType.COPY   -> added++
                        else -> {}
                    }
                }
                Triple(added, modified, deleted)
            }
        }
    } catch (e: Exception) {
        Log.w(TAG, "diffStats failed", e)
        Triple(0, 0, 0)
    }
}

private fun openRepo(path: File): Repository? {
    if (!path.exists()) return null
    return FileRepositoryBuilder()
        .setGitDir(path)
        .setBare()
        .readEnvironment()
        .build()
}

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
