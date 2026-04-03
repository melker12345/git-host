package com.localgithub.githost.ssh

import android.util.Log
import com.localgithub.githost.whitelist.Whitelist
import org.apache.sshd.common.NamedFactory
import org.apache.sshd.common.cipher.BuiltinCiphers
import org.apache.sshd.common.cipher.Cipher
import org.apache.sshd.common.compression.BuiltinCompressions
import org.apache.sshd.common.compression.Compression
import org.apache.sshd.common.kex.BuiltinDHFactories
import org.apache.sshd.common.kex.KeyExchangeFactory
import org.apache.sshd.common.mac.BuiltinMacs
import org.apache.sshd.common.mac.Mac
import org.apache.sshd.common.signature.BuiltinSignatures
import org.apache.sshd.common.signature.Signature
import org.apache.sshd.common.file.nonefs.NoneFileSystemFactory
import org.apache.sshd.common.random.JceRandomFactory
import org.apache.sshd.common.random.SingletonRandomFactory
import org.apache.sshd.server.ServerBuilder
import org.apache.sshd.server.SshServer
import org.apache.sshd.server.auth.pubkey.PublickeyAuthenticator
import org.apache.sshd.server.auth.pubkey.UserAuthPublicKeyFactory
import org.apache.sshd.server.channel.ChannelSessionFactory
import org.apache.sshd.server.command.CommandFactory
import org.apache.sshd.server.keyprovider.SimpleGeneratorHostKeyProvider
import java.io.File

private const val TAG = "SshServer"

/**
 * Wraps Apache MINA SSHD. Implements the Behavior Contract:
 * - Ed25519 pubkey auth against the whitelist
 * - Only git-upload-pack and git-receive-pack exec commands accepted
 * - Path resolution: strip leading /, resolve against baseDir, guard traversal
 * - Auto-init bare repo on first push (handled by ReceivePackCommand)
 */
class GitSshServer(
    private val port: Int,
    private val baseDir: File,
    private val whitelist: Whitelist,
    private val hostKeyFile: File,
) {
    private var server: SshServer? = null

    fun start() {
        // Build server manually instead of setUpDefaultServer() to bypass the
        // isSupported() checks that require BC/JMX — those silently fail on
        // Android, leaving factory lists empty and causing "KeyExchangeFactories
        // not set".  lenient=true includes every built-in factory regardless;
        // the actual algorithm execution uses Android's native JCE.
        val srv = SshServer()
        srv.port = port

        val hostKeyProvider = SimpleGeneratorHostKeyProvider(hostKeyFile.toPath())
        hostKeyProvider.algorithm = "EC"
        srv.keyPairProvider = hostKeyProvider

        @Suppress("UNCHECKED_CAST")
        srv.keyExchangeFactories = NamedFactory.setUpTransformedFactories(
            true, BuiltinDHFactories.VALUES, ServerBuilder.DH2KEX)
                as List<KeyExchangeFactory>
        @Suppress("UNCHECKED_CAST")
        srv.cipherFactories      = NamedFactory.setUpBuiltinFactories(
            true, BuiltinCiphers.VALUES) as List<NamedFactory<Cipher>>
        @Suppress("UNCHECKED_CAST")
        srv.macFactories         = NamedFactory.setUpBuiltinFactories(
            true, BuiltinMacs.VALUES) as List<NamedFactory<Mac>>
        @Suppress("UNCHECKED_CAST")
        srv.compressionFactories = NamedFactory.setUpBuiltinFactories(
            true, BuiltinCompressions.VALUES) as List<NamedFactory<Compression>>
        @Suppress("UNCHECKED_CAST")
        srv.signatureFactories   = NamedFactory.setUpBuiltinFactories(
            true, BuiltinSignatures.VALUES) as List<NamedFactory<Signature>>

        srv.randomFactory       = SingletonRandomFactory(JceRandomFactory.INSTANCE)
        srv.channelFactories    = listOf(ChannelSessionFactory.INSTANCE)
        srv.userAuthFactories   = listOf(UserAuthPublicKeyFactory.INSTANCE)
        srv.fileSystemFactory   = NoneFileSystemFactory.INSTANCE

        Log.d(TAG, "kex=${srv.keyExchangeFactories?.size} ciphers=${srv.cipherFactories?.size}")

        srv.publickeyAuthenticator = PublickeyAuthenticator { _, key, session ->
            val entry = whitelist.authorize(key)
            if (entry != null) {
                session.setAttribute(SESSION_LABEL_KEY, entry.label)
                Log.i(TAG, "AUTH ok   key=${Whitelist.fingerprint(key)} label=${entry.label} addr=${session.clientAddress}")
            } else {
                Log.w(TAG, "AUTH fail key=${Whitelist.fingerprint(key)} addr=${session.clientAddress}")
            }
            entry != null
        }

        srv.commandFactory = CommandFactory { _, rawCommand ->
            handleCommand(rawCommand.trim())
        }

        srv.start()
        server = srv
        Log.i(TAG, "Listening on :$port  base-dir=${baseDir.absolutePath}")
    }

    fun stop() {
        server?.stop(true)
        server = null
        Log.i(TAG, "Stopped")
    }

    val isRunning: Boolean get() = server?.isStarted == true

    private fun handleCommand(rawCommand: String): org.apache.sshd.server.command.Command {
        val spaceIdx = rawCommand.indexOf(' ')
        if (spaceIdx < 0) return GitCommand.RejectCommand("invalid command: $rawCommand")

        val cmd = rawCommand.substring(0, spaceIdx)
        val repoArg = rawCommand.substring(spaceIdx + 1).trim('\'', '"', ' ')

        if (cmd != "git-upload-pack" && cmd != "git-receive-pack") {
            return GitCommand.RejectCommand("command not allowed: $cmd")
        }

        val repoPath = resolvePath(baseDir, repoArg)
            ?: return GitCommand.RejectCommand("invalid path: $repoArg")

        return when (cmd) {
            "git-upload-pack"  -> GitCommand.UploadPackCommand(repoPath)
            "git-receive-pack" -> GitCommand.ReceivePackCommand(repoPath)
            else               -> GitCommand.RejectCommand("command not allowed: $cmd")
        }
    }

    companion object {
        val SESSION_LABEL_KEY = org.apache.sshd.common.AttributeRepository.AttributeKey<String>()

        /**
         * Strips leading slash, resolves against baseDir, guards against path traversal.
         * Returns null if the path would escape baseDir.
         */
        fun resolvePath(baseDir: File, reqPath: String): File? {
            val clean = reqPath.trimStart('/')
            if (clean.isEmpty() || clean.contains("..")) return null
            val resolved = File(baseDir, clean).canonicalFile
            val base = baseDir.canonicalFile
            return if (resolved.path.startsWith(base.path + File.separator) || resolved == base) {
                resolved
            } else null
        }
    }
}
