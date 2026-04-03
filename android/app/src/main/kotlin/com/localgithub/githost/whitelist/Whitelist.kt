package com.localgithub.githost.whitelist

import android.util.Base64
import android.util.Log
import org.apache.sshd.common.util.buffer.ByteArrayBuffer
import java.io.File
import java.security.PublicKey
import java.time.Instant

private const val TAG = "Whitelist"

data class WhitelistEntry(
    val rawKey: String,
    val label: String,
    val expiry: Long,
    val publicKey: PublicKey?,
)

/**
 * Manages the flat-file pubkey whitelist.
 *
 * Format (one entry per line):
 *   <base64-ssh-wire-pubkey> <label> [expiry-unix-timestamp]
 *
 * This format is identical to the Go implementation's whitelist — both sides
 * read and write the same file when backing up or syncing.
 */
class Whitelist(private val file: File) {

    private fun loadEntries(): List<WhitelistEntry> {
        if (!file.exists()) return emptyList()
        return file.readLines()
            .filter { it.isNotBlank() && !it.startsWith("#") }
            .mapNotNull { line ->
                val parts = line.trim().split(" ")
                if (parts.size < 2) {
                    Log.w(TAG, "skipping malformed line: $line")
                    return@mapNotNull null
                }
                val rawKey = parts[0]
                val label = parts[1]
                val expiry = parts.getOrNull(2)?.toLongOrNull() ?: 0L
                val pubkey = runCatching { base64WireToPublicKey(rawKey) }.getOrNull()
                WhitelistEntry(rawKey = rawKey, label = label, expiry = expiry, publicKey = pubkey)
            }
    }

    /** Returns the matching entry if [key] is authorized, null otherwise. */
    fun authorize(key: PublicKey): WhitelistEntry? {
        val incoming = publicKeyToBase64Wire(key) ?: return null
        val now = Instant.now().epochSecond
        return loadEntries().find { e ->
            e.rawKey == incoming && (e.expiry == 0L || now <= e.expiry)
        }
    }

    fun list(): List<WhitelistEntry> = loadEntries()

    fun add(pubkey: PublicKey, label: String, expiryEpoch: Long = 0L): String {
        val rawKey = publicKeyToBase64Wire(pubkey)
            ?: throw IllegalArgumentException("Cannot encode public key")
        val line = if (expiryEpoch != 0L) "$rawKey $label $expiryEpoch\n"
                   else "$rawKey $label\n"
        file.parentFile?.mkdirs()
        file.appendText(line)
        return fingerprint(pubkey)
    }

    fun remove(fingerprint: String): Boolean {
        val entries = loadEntries()
        val filtered = entries.filter { e ->
            e.publicKey?.let { fingerprint(it) } != fingerprint
        }
        if (filtered.size == entries.size) return false
        file.writeText(filtered.joinToString("\n") { e ->
            buildString {
                append(e.rawKey); append(" "); append(e.label)
                if (e.expiry != 0L) { append(" "); append(e.expiry) }
            }
        } + if (filtered.isNotEmpty()) "\n" else "")
        return true
    }

    companion object {
        fun publicKeyToBase64Wire(key: PublicKey): String? = runCatching {
            val buf = ByteArrayBuffer()
            buf.putPublicKey(key)
            Base64.encodeToString(buf.compactData, Base64.NO_WRAP)
        }.getOrNull()

        fun base64WireToPublicKey(raw: String): PublicKey? = runCatching {
            val bytes = Base64.decode(raw, Base64.NO_WRAP)
            ByteArrayBuffer(bytes).rawPublicKey
        }.getOrNull()

        fun fingerprint(key: PublicKey): String = runCatching {
            val buf = ByteArrayBuffer()
            buf.putPublicKey(key)
            val wire = buf.compactData
            val digest = java.security.MessageDigest.getInstance("SHA-256").digest(wire)
            "SHA256:" + Base64.encodeToString(digest, Base64.NO_WRAP or Base64.NO_PADDING)
        }.getOrDefault("(unknown)")
    }
}
