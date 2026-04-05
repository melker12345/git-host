package com.localgithub.githost.pairing

import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.bouncycastle.asn1.edec.EdECObjectIdentifiers
import org.bouncycastle.asn1.x509.AlgorithmIdentifier
import org.bouncycastle.asn1.x509.SubjectPublicKeyInfo
import org.bouncycastle.crypto.agreement.X25519Agreement
import org.bouncycastle.crypto.generators.X25519KeyPairGenerator
import org.bouncycastle.crypto.params.X25519KeyGenerationParameters
import org.bouncycastle.crypto.params.X25519PrivateKeyParameters
import org.bouncycastle.crypto.params.X25519PublicKeyParameters
import org.bouncycastle.jce.provider.BouncyCastleProvider
import org.bouncycastle.openssl.jcajce.JcaPEMKeyConverter
import java.io.DataInputStream
import java.io.InputStream
import java.io.OutputStream
import java.math.BigInteger
import java.net.ServerSocket
import java.net.Socket
import java.nio.ByteBuffer
import java.security.KeyFactory
import java.security.MessageDigest
import java.security.PublicKey
import java.security.SecureRandom
import java.security.spec.RSAPublicKeySpec
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

private const val TAG = "Pairing"
private const val GCM_NONCE_LEN = 12
private const val GCM_TAG_LEN = 128

/**
 * Derives the 6-digit pairing PIN from an X25519 public key.
 * PIN = SHA256(pub)[0..2] interpreted as big-endian uint24, mod 1_000_000.
 * Identical algorithm to the Go implementation.
 */
fun pinFromPubkey(pub: ByteArray): String {
    val h = MessageDigest.getInstance("SHA-256").digest(pub)
    val n = ((h[0].toInt() and 0xFF) shl 16 or
             ((h[1].toInt() and 0xFF) shl 8) or
              (h[2].toInt() and 0xFF)) % 1_000_000
    return n.toString().padStart(6, '0')
}

fun formatPin(pin: String): String =
    if (pin.length == 6) "${pin.substring(0, 3)} ${pin.substring(3)}" else pin

// --- Host side ---------------------------------------------------------------

/**
 * Holds the result of a completed pairing handshake.
 * The TCP connection is kept open until [approve] or [deny] is called.
 */
class PairingRequest(
    val publicKey: PublicKey,
    private val socket: java.net.Socket,
    private val output: java.io.OutputStream,
) {
    /** Send approval to the peer and close the connection. */
    fun approve() {
        try {
            writeMsg(output, byteArrayOf(1))
            Log.i(TAG, "Approve byte sent successfully")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to send approve byte", e)
        } finally {
            try { socket.close() } catch (_: Exception) {}
        }
    }

    /** Send denial to the peer and close the connection. */
    fun deny() {
        try {
            writeMsg(output, byteArrayOf(0))
        } catch (e: Exception) {
            Log.e(TAG, "Failed to send deny byte", e)
        } finally {
            try { socket.close() } catch (_: Exception) {}
        }
    }
}

/**
 * Runs the host (owner) side of the pairing protocol.
 *
 * Suspends until a device connects and completes the handshake.
 * [onPin] is called immediately with the 6-digit PIN so the caller can
 * display it before waiting.
 *
 * Returns a [PairingRequest] containing the peer's public key and
 * approve/deny callbacks. The TCP connection stays open until one is called.
 */
suspend fun hostPair(
    pairingPort: Int,
    onPin: (String) -> Unit,
): PairingRequest = withContext(Dispatchers.IO) {
    val (hostPriv, hostPub) = generateX25519Keypair()
    onPin(pinFromPubkey(hostPub))
    Log.i(TAG, "Pairing session open on :$pairingPort")

    Log.i(TAG, "Opening ServerSocket on port $pairingPort")
    val serverSocket = ServerSocket(pairingPort)
    serverSocket.soTimeout = 5 * 60 * 1000
    val conn = try {
        Log.i(TAG, "Waiting for peer to connect...")
        serverSocket.accept()
    } finally {
        serverSocket.close()
    }
    Log.i(TAG, "Peer connected from ${conn.inetAddress}")

    conn.soTimeout = 5 * 60 * 1000
    val input = conn.getInputStream()
    val output = conn.getOutputStream()

    Log.d(TAG, "Step 1: reading peer ephemeral pubkey")
    val friendPub = readMsg(input)
    require(friendPub.size == 32) { "invalid friend pubkey length: ${friendPub.size}" }

    Log.d(TAG, "Step 2: ECDH + send host pubkey")
    val sharedSecret = x25519(hostPriv, friendPub)
    writeMsg(output, hostPub)

    Log.d(TAG, "Step 3: deriving session key")
    val sessionKey = deriveSessionKey(sharedSecret)

    Log.d(TAG, "Step 4: reading encrypted SSH pubkey")
    val encPayload = readMsg(input)

    Log.d(TAG, "Step 5: decrypting payload (${encPayload.size} bytes)")
    val pubkeyWire = aesgcmDecrypt(sessionKey, encPayload)

    Log.d(TAG, "Step 6: parsing SSH public key")
    val pubkey = parseSshWireKey(pubkeyWire)
    Log.i(TAG, "Handshake complete — key type=${pubkey.algorithm}, awaiting user decision")

    // Connection stays open — caller must call PairingRequest.approve() or .deny()
    PairingRequest(pubkey, conn, output)
}

// --- Friend side -------------------------------------------------------------

/**
 * Runs the friend side of the pairing protocol.
 *
 * Connects to [hostAddr]:[pairingPort], verifies the [pin], and sends the
 * SSH public key from [pubkeyPem] (authorized_keys format) over the
 * ECDH-secured channel.
 */
suspend fun friendJoin(
    hostAddr: String,
    pairingPort: Int,
    pin: String,
    pubkeyBytes: ByteArray,
): Unit = withContext(Dispatchers.IO) {
    val (friendPriv, friendPub) = generateX25519Keypair()

    Socket(hostAddr, pairingPort).use { conn ->
        conn.soTimeout = 30_000
        val input = conn.getInputStream()
        val output = conn.getOutputStream()

        writeMsg(output, friendPub)
        val hostPub = readMsg(input)
        require(hostPub.size == 32) { "invalid host pubkey length" }

        val expectedPin = pinFromPubkey(hostPub)
        check(expectedPin == pin) {
            "PIN mismatch — possible MITM\n  entered:  ${formatPin(pin)}\n  received: ${formatPin(expectedPin)}"
        }

        val sharedSecret = x25519(friendPriv, hostPub)
        val sessionKey = deriveSessionKey(sharedSecret)

        val encrypted = aesgcmEncrypt(sessionKey, pubkeyBytes)
        writeMsg(output, encrypted)

        val resp = readMsg(input)
        check(resp.isNotEmpty() && resp[0] == 1.toByte()) { "request denied by host owner" }
    }
    Log.i(TAG, "Pairing complete")
}

// --- Wire helpers ------------------------------------------------------------

private fun writeMsg(out: OutputStream, data: ByteArray) {
    val lenBytes = ByteBuffer.allocate(4).putInt(data.size).array()
    out.write(lenBytes)
    out.write(data)
    out.flush()
}

private fun readMsg(input: InputStream): ByteArray {
    val dis = DataInputStream(input)
    val len = dis.readInt()
    require(len in 1..(1 shl 20)) { "message length out of range: $len" }
    val buf = ByteArray(len)
    dis.readFully(buf)
    return buf
}

// --- Crypto helpers ----------------------------------------------------------

private data class Keypair(val priv: ByteArray, val pub: ByteArray)

private fun generateX25519Keypair(): Keypair {
    val gen = X25519KeyPairGenerator()
    gen.init(X25519KeyGenerationParameters(SecureRandom()))
    val pair = gen.generateKeyPair()
    val priv = (pair.private as X25519PrivateKeyParameters).encoded
    val pub  = (pair.public  as X25519PublicKeyParameters).encoded
    return Keypair(priv, pub)
}

private fun x25519(priv: ByteArray, pub: ByteArray): ByteArray {
    val agreement = X25519Agreement()
    agreement.init(X25519PrivateKeyParameters(priv))
    val secret = ByteArray(agreement.agreementSize)
    agreement.calculateAgreement(X25519PublicKeyParameters(pub), secret, 0)
    return secret
}

private fun deriveSessionKey(sharedSecret: ByteArray): ByteArray {
    val info = "localgithub-pairing".toByteArray()
    val prk = hmacSha256(ByteArray(32), sharedSecret)
    val okm = hmacSha256(prk, info + byteArrayOf(0x01))
    return okm.copyOf(32)
}

private fun hmacSha256(key: ByteArray, data: ByteArray): ByteArray {
    val mac = javax.crypto.Mac.getInstance("HmacSHA256")
    mac.init(SecretKeySpec(key, "HmacSHA256"))
    return mac.doFinal(data)
}

private fun aesgcmEncrypt(key: ByteArray, plaintext: ByteArray): ByteArray {
    val nonce = ByteArray(GCM_NONCE_LEN).also { SecureRandom().nextBytes(it) }
    val cipher = Cipher.getInstance("AES/GCM/NoPadding")
    cipher.init(Cipher.ENCRYPT_MODE, SecretKeySpec(key, "AES"), GCMParameterSpec(GCM_TAG_LEN, nonce))
    val ct = cipher.doFinal(plaintext)
    return nonce + ct
}

private fun aesgcmDecrypt(key: ByteArray, ciphertext: ByteArray): ByteArray {
    require(ciphertext.size > GCM_NONCE_LEN) { "ciphertext too short" }
    val nonce = ciphertext.copyOf(GCM_NONCE_LEN)
    val ct = ciphertext.copyOfRange(GCM_NONCE_LEN, ciphertext.size)
    val cipher = Cipher.getInstance("AES/GCM/NoPadding")
    cipher.init(Cipher.DECRYPT_MODE, SecretKeySpec(key, "AES"), GCMParameterSpec(GCM_TAG_LEN, nonce))
    return cipher.doFinal(ct)
}

private fun parseSshWireKey(wire: ByteArray): PublicKey {
    val buf = ByteBuffer.wrap(wire)
    val typeBytes = ByteArray(buf.int); buf.get(typeBytes)
    return when (val keyType = String(typeBytes, Charsets.US_ASCII)) {
        "ssh-rsa" -> {
            val eBytes = ByteArray(buf.int); buf.get(eBytes)
            val nBytes = ByteArray(buf.int); buf.get(nBytes)
            KeyFactory.getInstance("RSA").generatePublic(
                RSAPublicKeySpec(BigInteger(1, nBytes), BigInteger(1, eBytes))
            )
        }
        "ecdsa-sha2-nistp256" -> {
            val _curve = ByteArray(buf.int); buf.get(_curve)   // skip "nistp256"
            val point  = ByteArray(buf.int); buf.get(point)    // 04 || x (32) || y (32)
            require(point.size == 65 && point[0] == 0x04.toByte()) {
                "Expected uncompressed P-256 point (65 bytes), got ${point.size}"
            }
            val x = BigInteger(1, point.copyOfRange(1, 33))
            val y = BigInteger(1, point.copyOfRange(33, 65))
            val params = java.security.AlgorithmParameters.getInstance("EC")
            params.init(java.security.spec.ECGenParameterSpec("secp256r1"))
            val ecSpec = params.getParameterSpec(java.security.spec.ECParameterSpec::class.java)
            KeyFactory.getInstance("EC").generatePublic(
                java.security.spec.ECPublicKeySpec(java.security.spec.ECPoint(x, y), ecSpec)
            )
        }
        "ssh-ed25519" -> {
            val kBytes = ByteArray(buf.int); buf.get(kBytes)
            val spki = SubjectPublicKeyInfo(
                AlgorithmIdentifier(EdECObjectIdentifiers.id_Ed25519), kBytes)
            JcaPEMKeyConverter().setProvider(BouncyCastleProvider()).getPublicKey(spki)
        }
        else -> throw IllegalArgumentException("Unsupported SSH key type: $keyType")
    }
}
