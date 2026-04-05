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

enum class KeyType { DEVICE, FRIEND }
enum class Permission { RW, RO }

data class WhitelistEntry(
    val rawKey: String,
    val label: String,
    val expiry: Long,
    val publicKey: PublicKey?,
    val type: KeyType = KeyType.DEVICE,
    val repos: Set<String> = setOf("*"),
    val perm: Permission = Permission.RW,
)

/**
 * Manages the flat-file pubkey whitelist.
 *
 * Format (one entry per line, all fields after label are optional):
 *   <base64-ssh-wire-pubkey> <label> [expiry-unix-timestamp] [type=device|friend] [repos=*|r1,r2] [perm=rw|ro]
 *
 * Omitted optional fields default to: expiry=0 (permanent), type=device, repos=*, perm=rw.
 * This is backward-compatible with the Go implementation's whitelist format.
 */
class Whitelist(private val file: File) {

    private fun parseLine(line: String): WhitelistEntry? {
        val parts = line.trim().split(" ")
        if (parts.size < 2) {
            Log.w(TAG, "skipping malformed line: $line")
            return null
        }
        val rawKey = parts[0]
        val label  = parts[1]
        var expiry = 0L
        var type   = KeyType.DEVICE
        var repos: Set<String> = setOf("*")
        var perm   = Permission.RW

        var idx = 2
        if (idx < parts.size && parts[idx].toLongOrNull() != null) {
            expiry = parts[idx].toLong()
            idx++
        }
        for (i in idx until parts.size) {
            when {
                parts[i].startsWith("type=")  ->
                    type = if (parts[i].substringAfter("type=") == "friend") KeyType.FRIEND else KeyType.DEVICE
                parts[i].startsWith("repos=") ->
                    repos = parts[i].substringAfter("repos=").split(",").filter { it.isNotBlank() }.toSet()
                parts[i].startsWith("perm=")  ->
                    perm = if (parts[i].substringAfter("perm=") == "ro") Permission.RO else Permission.RW
            }
        }

        val pubkey = runCatching { base64WireToPublicKey(rawKey) }.getOrNull()
        return WhitelistEntry(rawKey, label, expiry, pubkey, type, repos, perm)
    }

    private fun entryToLine(e: WhitelistEntry): String {
        val sb = StringBuilder("${e.rawKey} ${e.label}")
        val hasExtended = e.type != KeyType.DEVICE || e.repos != setOf("*") || e.perm != Permission.RW
        if (e.expiry != 0L || hasExtended) sb.append(" ${if (e.expiry != 0L) e.expiry else 0L}")
        if (hasExtended) {
            sb.append(" type=${e.type.name.lowercase()}")
            if (e.repos != setOf("*")) sb.append(" repos=${e.repos.joinToString(",")}")
            if (e.perm == Permission.RO) sb.append(" perm=ro")
        }
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

    /**
     * Like [authorize] but also checks repo-level access and write permission.
     * [repoName] is the bare repo filename (e.g. "Test.git").
     */
    fun authorizeRepo(key: PublicKey, repoName: String, needsWrite: Boolean): WhitelistEntry? {
        val entry = authorize(key) ?: return null
        if (!entry.repos.contains("*") && !entry.repos.contains(repoName)) return null
        if (needsWrite && entry.perm == Permission.RO) return null
        return entry
    }

    fun list(): List<WhitelistEntry> = loadEntries()

    fun add(
        pubkey: PublicKey,
        label: String,
        expiryEpoch: Long = 0L,
        type: KeyType = KeyType.DEVICE,
        repos: Set<String> = setOf("*"),
        perm: Permission = Permission.RW,
    ): String {
        val rawKey = publicKeyToBase64Wire(pubkey)
            ?: throw IllegalArgumentException("Cannot encode public key")
        val entry = WhitelistEntry(rawKey, label, expiryEpoch, pubkey, type, repos, perm)
        file.parentFile?.mkdirs()
        file.appendText(entryToLine(entry) + "\n")
        return fingerprint(pubkey)
    }

    fun remove(fingerprint: String): Boolean {
        val entries = loadEntries()
        val filtered = entries.filter { e ->
            val fp = e.publicKey?.let { fingerprint(it) } ?: return@filter true
            fp != fingerprint
        }
        if (filtered.size == entries.size) return false
        file.writeText(
            filtered.joinToString("\n") { entryToLine(it) } +
                if (filtered.isNotEmpty()) "\n" else ""
        )
        return true
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
            val wireBytes = when {
                alg.contains("ed25519", ignoreCase = true) ||
                alg.contains("eddsa",   ignoreCase = true) -> {
                    val rawKey = SubjectPublicKeyInfo.getInstance(key.encoded).publicKeyData.bytes
                    buildSshWire("ssh-ed25519".toByteArray(Charsets.US_ASCII), rawKey)
                }
                else -> throw IllegalArgumentException("no manual encoder for key type: $alg")
            }
            return ByteBuffer.allocate(4 + wireBytes.size)
                .putInt(wireBytes.size).put(wireBytes).array()
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
