package com.localgithub.githost.whitelist

import android.util.Base64
import android.util.Log
import org.apache.sshd.common.util.buffer.ByteArrayBuffer
import org.bouncycastle.asn1.x509.SubjectPublicKeyInfo
import java.io.File
import java.nio.ByteBuffer
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
 * expiry=0 (or absent) means permanent. Any further tokens on a line are
 * ignored, so files written by older builds (type=, repos=, perm=) still load.
 * This is the same format the Go implementation reads and writes.
 */
class Whitelist(private val file: File) {

    private fun parseLine(line: String): WhitelistEntry? {
        val parts = line.trim().split(Regex("\\s+"))
        if (parts.size < 2) {
            Log.w(TAG, "skipping malformed line: $line")
            return null
        }
        val rawKey = parts[0]
        val label  = parts[1]
        val expiry = if (parts.size > 2) parts[2].toLongOrNull() ?: 0L else 0L

        val pubkey = runCatching { base64WireToPublicKey(rawKey) }.getOrNull()
        return WhitelistEntry(rawKey, label, expiry, pubkey)
    }

    private fun entryToLine(e: WhitelistEntry): String {
        val sb = StringBuilder("${e.rawKey} ${e.label}")
        if (e.expiry != 0L) sb.append(" ${e.expiry}")
        return sb.toString()
    }

    private fun loadEntries(): List<WhitelistEntry> {
        if (!file.exists()) return emptyList()
        return file.readLines()
            .filter { it.isNotBlank() && !it.startsWith("#") }
            .mapNotNull { parseLine(it) }
    }

    /** Returns the matching entry if [key] is authorized (not expired), null otherwise. */
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
        val entry = WhitelistEntry(rawKey, sanitizeLabel(label), expiryEpoch, pubkey)
        file.parentFile?.mkdirs()
        // Re-pairing the same key replaces its entry instead of stacking duplicates.
        removeByRawKey(rawKey)
        file.appendText(entryToLine(entry) + "\n")
        return fingerprint(pubkey)
    }

    fun removeByRawKey(rawKey: String): Boolean {
        val entries = loadEntries()
        val filtered = entries.filter { it.rawKey != rawKey }
        if (filtered.size == entries.size) return false
        file.writeText(
            filtered.joinToString("\n") { entryToLine(it) } +
                if (filtered.isNotEmpty()) "\n" else ""
        )
        return true
    }

    companion object {
        /**
         * The label is one whitespace-delimited token in the file, so spaces
         * would be read back as an expiry or dropped. Collapse them to dashes.
         */
        fun sanitizeLabel(label: String): String =
            label.trim().replace(Regex("\\s+"), "-").ifEmpty { "unknown" }

        fun publicKeyToBase64Wire(key: PublicKey): String? = runCatching {
            val buf = ByteArrayBuffer()
            buf.putPublicKey(key)
            // putPublicKey writes [4-byte-len][wire-bytes]; skip the length prefix
            val all = buf.compactData
            val wireBytes = all.copyOfRange(4, all.size)
            Base64.encodeToString(wireBytes, Base64.NO_WRAP)
        }.recoverCatching {
            // MINA SSHD cannot encode Ed25519 without EdDSA registrar; encode manually
            Base64.encodeToString(manualPutPublicKey(key), Base64.NO_WRAP)
        }.getOrNull()

        private fun manualPutPublicKey(key: PublicKey): ByteArray {
            val alg = key.algorithm
            return when {
                alg.contains("ed25519", ignoreCase = true) ||
                alg.contains("eddsa",   ignoreCase = true) -> {
                    val rawKey = SubjectPublicKeyInfo.getInstance(key.encoded).publicKeyData.bytes
                    buildSshWire("ssh-ed25519".toByteArray(Charsets.US_ASCII), rawKey)
                }
                else -> throw IllegalArgumentException("no manual encoder for key type: $alg")
            }
        }

        private fun buildSshWire(vararg fields: ByteArray): ByteArray {
            val buf = ByteBuffer.allocate(fields.sumOf { 4 + it.size })
            for (f in fields) { buf.putInt(f.size); buf.put(f) }
            return buf.array()
        }

        fun base64WireToPublicKey(raw: String): PublicKey? = runCatching {
            val bytes = Base64.decode(raw, Base64.NO_WRAP)
            ByteArrayBuffer(bytes).rawPublicKey
        }.getOrNull()

        fun fingerprint(key: PublicKey): String = runCatching {
            val buf = ByteArrayBuffer()
            buf.putPublicKey(key)
            // putPublicKey writes [4-byte-len][wire-bytes]; fingerprint only over wire-bytes
            val all = buf.compactData
            val wire = all.copyOfRange(4, all.size)
            val digest = java.security.MessageDigest.getInstance("SHA-256").digest(wire)
            "SHA256:" + Base64.encodeToString(digest, Base64.NO_WRAP or Base64.NO_PADDING)
        }.getOrDefault("(unknown)")
    }
}
