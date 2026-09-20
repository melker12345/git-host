package com.localgithub.githost.pairing

import android.net.Uri
import android.util.Base64
import com.localgithub.githost.whitelist.Whitelist
import java.security.PublicKey

/**
 * A device key handed over by scanning a QR code or opening a githost:// link
 * instead of the network handshake. The key never crossed the network, so the
 * only check left is the owner reading the fingerprint and tapping Approve.
 */
data class ScannedDevice(val publicKey: PublicKey, val label: String)

private const val SCHEME = "githost"
private const val HOST = "pair"

/**
 * Parses `githost://pair?key=<base64 SSH wire key>&label=<name>`, as printed by
 * `git-host pair --qr`. Returns null for anything else.
 */
fun parseDevicePairingUri(raw: String): ScannedDevice? {
    val uri = runCatching { Uri.parse(raw.trim()) }.getOrNull() ?: return null
    if (!SCHEME.equals(uri.scheme, ignoreCase = true)) return null
    if (!HOST.equals(uri.host, ignoreCase = true)) return null

    val keyB64 = uri.getQueryParameter("key")?.trim().orEmpty()
    if (keyB64.isEmpty()) return null
    // Reject junk before handing it to the SSH parser.
    runCatching { Base64.decode(keyB64, Base64.NO_WRAP) }.getOrNull()
        ?.takeIf { it.isNotEmpty() } ?: return null
    val key = Whitelist.base64WireToPublicKey(keyB64) ?: return null

    val label = uri.getQueryParameter("label")?.trim().orEmpty().ifBlank { "device" }
    return ScannedDevice(key, label)
}
