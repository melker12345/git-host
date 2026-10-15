package com.localgithub.githost

import android.app.Application
import com.localgithub.githost.whitelist.Whitelist
import org.bouncycastle.jce.provider.BouncyCastleProvider
import java.io.File
import java.security.Security

/**
 * Application singleton: owns the on-disk layout (config and repository
 * directories) and the shared [Whitelist], and sets up the JVM properties
 * and crypto provider MINA SSHD needs before any SSH class is loaded.
 */
class GitHostApp : Application() {

    val cfgDir: File by lazy { File(filesDir, "config").also { it.mkdirs() } }
    val reposDir: File by lazy { File(filesDir, "repos").also { it.mkdirs() } }

    val whitelist: Whitelist by lazy { Whitelist(File(cfgDir, "whitelist")) }

    override fun onCreate() {
        super.onCreate()
        instance = this

        // MINA SSHD's PathUtils/ServerBuilder static initializers read user.home.
        // On Android the property is unset → ExceptionInInitializerError.
        System.setProperty("user.home", filesDir.absolutePath)

        // Disable MINA SSHD's auto-registration of BC as the JCE provider (it breaks
        // Android's native crypto), but manually insert BC at a low priority so that
        // MINA SSHD's EdDSA signature factory can find it for Ed25519 key verification.
        System.setProperty("org.apache.sshd.security.registrars", "")
        if (Security.getProvider("BC") == null) {
            Security.addProvider(BouncyCastleProvider())
        }
    }

    companion object {
        lateinit var instance: GitHostApp
            private set
    }
}
