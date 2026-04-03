package com.localgithub.githost

import android.app.Application
import com.localgithub.githost.whitelist.Whitelist
import com.localgithub.githost.service.GitHostService
import java.io.File

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

        // BouncyCastle is on the classpath (used in Pairing.kt) so MINA SSHD's
        // auto-detection would register it as the JCE provider and then fail on Android
        // because BC's internal crypto checks don't pass (isECCSupported() → false, etc.).
        // Disabling the registrar list forces MINA SSHD to use Android's standard JCE.
        System.setProperty("org.apache.sshd.security.registrars", "")
    }

    companion object {
        lateinit var instance: GitHostApp
            private set
    }
}
