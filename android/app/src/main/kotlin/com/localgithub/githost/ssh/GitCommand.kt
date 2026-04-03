package com.localgithub.githost.ssh

import android.util.Log
import org.apache.sshd.server.channel.ChannelSession
import org.apache.sshd.server.command.Command
import org.apache.sshd.server.ExitCallback
import org.apache.sshd.server.session.ServerSession
import org.eclipse.jgit.lib.Repository
import org.eclipse.jgit.storage.file.FileRepositoryBuilder
import org.eclipse.jgit.transport.ReceivePack
import org.eclipse.jgit.transport.UploadPack
import java.io.File
import java.io.InputStream
import java.io.OutputStream

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

    class UploadPackCommand(private val repoPath: File) : GitCommand() {
        override fun start(channel: ChannelSession, env: org.apache.sshd.server.Environment) {
            Thread {
                try {
                    openRepo(repoPath)?.use { repo ->
                        Log.i(TAG, "FETCH repo=${repoPath.name}")
                        val up = UploadPack(repo)
                        up.isBiDirectionalPipe = true
                        up.upload(_in!!, _out!!, _err)
                        exit(0)
                    } ?: run {
                        sendError("repository not found: ${repoPath.name}")
                        exit(128)
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "UploadPack error", e)
                    sendError(e.message ?: "internal error")
                    exit(1)
                }
            }.start()
        }

        override fun destroy(channel: ChannelSession) {}
    }

    // -------------------------------------------------------------------------

    class ReceivePackCommand(private val repoPath: File) : GitCommand() {
        override fun start(channel: ChannelSession, env: org.apache.sshd.server.Environment) {
            Thread {
                try {
                    val repo = ensureRepo(repoPath)
                    repo.use {
                        Log.i(TAG, "PUSH  repo=${repoPath.name}")
                        val rp = ReceivePack(repo)
                        rp.receive(_in!!, _out!!, _err)
                        exit(0)
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "ReceivePack error", e)
                    sendError(e.message ?: "internal error")
                    exit(1)
                }
            }.start()
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
