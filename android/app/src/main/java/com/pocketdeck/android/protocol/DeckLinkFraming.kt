package com.pocketdeck.android.protocol

import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import java.io.DataInputStream
import java.io.EOFException
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.security.GeneralSecurityException
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.UUID
import javax.crypto.Cipher
import javax.crypto.Mac
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

open class ProtocolException(message: String, cause: Throwable? = null) : Exception(message, cause)
class AuthenticationException(message: String) : ProtocolException(message)

object DeckLinkFraming {
    val MAGIC = byteArrayOf(0x50, 0x44) // "PD"
    const val PROTOCOL_VERSION: Int = 1
    const val HEADER_SIZE: Int = 12
    const val MAX_FRAME_SIZE: Int = 8 * 1024 * 1024

    // Multiplexed Channels
    const val CH_AUTH: Int = 0x01
    const val CH_CONTROL: Int = 0x02
    const val CH_VIDEO: Int = 0x03
    const val CH_AUDIO: Int = 0x04
    const val CH_FILE_CTRL: Int = 0x05
    const val CH_FILE_DATA: Int = 0x06
    const val CH_CHAT: Int = 0x07
    const val CH_CLIPBOARD: Int = 0x08

    val DIR_CLIENT_TO_SERVER = byteArrayOf('C'.code.toByte(), '2'.code.toByte(), 'S'.code.toByte(), 0)
    val DIR_SERVER_TO_CLIENT = byteArrayOf('S'.code.toByte(), '2'.code.toByte(), 'C'.code.toByte(), 0)

    const val VIDEO_KIND_DATA: Int = 0
    const val VIDEO_KIND_CONFIG: Int = 1

    const val AUDIO_CODEC_PCM16: Int = 1
    const val AUDIO_CODEC_OPUS: Int = 2
    const val AUDIO_CODEC_AAC: Int = 3

    private val rng = SecureRandom()
    val gson = Gson()
    private val mapType = object : TypeToken<Map<String, Any?>>() {}.type

    fun randomNonce(size: Int = 32): ByteArray = ByteArray(size).also { rng.nextBytes(it) }

    fun toHex(bytes: ByteArray): String = buildString(bytes.size * 2) {
        for (b in bytes) {
            val v = b.toInt() and 0xFF
            append("0123456789abcdef"[v ushr 4])
            append("0123456789abcdef"[v and 0x0F])
        }
    }

    fun fromHex(hex: String): ByteArray {
        val clean = hex.trim()
        require(clean.length % 2 == 0) { "Hex string must have even length" }
        val out = ByteArray(clean.length / 2)
        for (i in out.indices) {
            val hi = Character.digit(clean[i * 2], 16)
            val lo = Character.digit(clean[i * 2 + 1], 16)
            require(hi >= 0 && lo >= 0) { "Invalid hex character" }
            out[i] = ((hi shl 4) or lo).toByte()
        }
        return out
    }

    fun formatPartnerId(rawId: String): String {
        val digits = rawId.filter { it.isDigit() }
        return if (digits.length == 9) {
            "${digits.substring(0, 3)} ${digits.substring(3, 6)} ${digits.substring(6, 9)}"
        } else {
            rawId.trim()
        }
    }

    fun normalizePartnerTarget(raw: String): String {
        val s = raw.trim()
        val onlyDigits = s.filter { it.isDigit() }
        if (onlyDigits.length == 9 && s.all { it.isDigit() || it in " -." } && s.count { it == '.' } != 3) {
            return onlyDigits
        }
        return s
    }

    fun generatePartnerId(): String {
        val first = rng.nextInt(9) + 1
        val rest = rng.nextInt(100_000_000)
        return "%d%08d".format(first, rest)
    }

    fun generateSessionPassword(length: Int = 6): String =
        buildString(length) { repeat(length) { append(rng.nextInt(10)) } }

    private fun hmacSha256(key: ByteArray, data: ByteArray): ByteArray {
        val mac = Mac.getInstance("HmacSHA256")
        mac.init(SecretKeySpec(if (key.isEmpty()) ByteArray(32) else key, "HmacSHA256"))
        return mac.doFinal(data)
    }

    fun computeAuthProof(
        password: String,
        serverNonce: ByteArray,
        clientNonce: ByteArray,
        role: String
    ): String {
        require(serverNonce.size == 32 && clientNonce.size == 32) { "Nonces must be 32 bytes each" }
        val key = password.trim().toByteArray(Charsets.UTF_8)
        val roleBytes = role.toByteArray(Charsets.US_ASCII)
        val msg = ByteArray(serverNonce.size + clientNonce.size + roleBytes.size)
        System.arraycopy(serverNonce, 0, msg, 0, serverNonce.size)
        System.arraycopy(clientNonce, 0, msg, serverNonce.size, clientNonce.size)
        System.arraycopy(roleBytes, 0, msg, serverNonce.size + clientNonce.size, roleBytes.size)
        return toHex(hmacSha256(key, msg))
    }

    fun verifyAuthProof(expectedHex: String, candidateHex: String): Boolean {
        val a = expectedHex.lowercase().toByteArray(Charsets.US_ASCII)
        val b = candidateHex.trim().lowercase().toByteArray(Charsets.US_ASCII)
        return MessageDigest.isEqual(a, b)
    }

    /** RFC 5869 HKDF-SHA256 matching Python `derive_session_key` byte-for-byte. */
    fun deriveSessionKey(password: String, serverNonce: ByteArray, clientNonce: ByteArray): ByteArray {
        val salt = ByteArray(serverNonce.size + clientNonce.size)
        System.arraycopy(serverNonce, 0, salt, 0, serverNonce.size)
        System.arraycopy(clientNonce, 0, salt, serverNonce.size, clientNonce.size)
        val ikm = password.trim().toByteArray(Charsets.UTF_8)
        val prk = hmacSha256(salt, ikm)
        val info = "PocketDeck-v1-SessionKey".toByteArray(Charsets.US_ASCII)
        val expandInput = ByteArray(info.size + 1)
        System.arraycopy(info, 0, expandInput, 0, info.size)
        expandInput[info.size] = 0x01
        return hmacSha256(prk, expandInput) // 32 bytes
    }

    data class Frame(val channel: Int, val seq: Long, val payload: ByteArray)

    fun packFrame(channel: Int, seq: Long, payload: ByteArray, cipher: SessionCipher? = null): ByteArray {
        val body = if (cipher != null && channel != CH_AUTH) {
            cipher.encrypt(channel, seq, payload)
        } else {
            payload
        }
        if (body.size > MAX_FRAME_SIZE) {
            throw ProtocolException("Frame body ${body.size} exceeds MAX_FRAME_SIZE ($MAX_FRAME_SIZE)")
        }
        val buf = ByteBuffer.allocate(HEADER_SIZE + body.size).order(ByteOrder.BIG_ENDIAN)
        buf.put(MAGIC)
        buf.put(PROTOCOL_VERSION.toByte())
        buf.put((channel and 0xFF).toByte())
        buf.putInt((seq and 0xFFFFFFFFL).toInt())
        buf.putInt(body.size)
        buf.put(body)
        return buf.array()
    }

    fun readFrame(
        input: DataInputStream,
        expectedSeq: Long? = null,
        cipher: SessionCipher? = null
    ): Frame {
        val hdr = ByteArray(HEADER_SIZE)
        try {
            input.readFully(hdr)
        } catch (e: EOFException) {
            throw EOFException("Stream closed while reading DeckLink header")
        }
        if (hdr[0] != MAGIC[0] || hdr[1] != MAGIC[1]) {
            throw ProtocolException("Invalid DeckLink magic bytes")
        }
        val ver = hdr[2].toInt() and 0xFF
        if (ver != PROTOCOL_VERSION) {
            throw ProtocolException("Unsupported DeckLink version: $ver")
        }
        val channel = hdr[3].toInt() and 0xFF
        val hb = ByteBuffer.wrap(hdr, 4, 8).order(ByteOrder.BIG_ENDIAN)
        val seq = hb.int.toLong() and 0xFFFFFFFFL
        val length = hb.int
        if (length < 0 || length > MAX_FRAME_SIZE) {
            throw ProtocolException("Invalid frame length: $length")
        }
        if (expectedSeq != null && seq != (expectedSeq and 0xFFFFFFFFL)) {
            throw ProtocolException("Sequence mismatch: got $seq, expected $expectedSeq")
        }
        val rawBody = ByteArray(length)
        if (length > 0) {
            input.readFully(rawBody)
        }
        val body = if (cipher != null && channel != CH_AUTH) {
            cipher.decrypt(channel, seq, rawBody)
        } else {
            rawBody
        }
        return Frame(channel, seq, body)
    }

    fun packJson(obj: Map<String, Any?>): ByteArray =
        gson.toJson(obj).toByteArray(Charsets.UTF_8)

    fun unpackJson(data: ByteArray): Map<String, Any?> {
        val parsed: Map<String, Any?>? = gson.fromJson(String(data, Charsets.UTF_8), mapType)
        return parsed ?: throw ProtocolException("Expected JSON object payload")
    }

    // Binary sub-payloads (>BQHH = 13B, >BIBQ = 14B, >16sQ = 24B)
    data class VideoSubPayload(val kind: Int, val ptsUs: Long, val width: Int, val height: Int, val data: ByteArray)
    data class AudioSubPayload(val codec: Int, val sampleRate: Int, val channels: Int, val ptsUs: Long, val data: ByteArray)
    data class FileChunkPayload(val uuidBytes: ByteArray, val offset: Long, val chunk: ByteArray)

    fun packVideoPayload(kind: Int, ptsUs: Long, width: Int, height: Int, data: ByteArray): ByteArray {
        val buf = ByteBuffer.allocate(13 + data.size).order(ByteOrder.BIG_ENDIAN)
        buf.put((kind and 0xFF).toByte())
        buf.putLong(ptsUs)
        buf.putShort((width and 0xFFFF).toShort())
        buf.putShort((height and 0xFFFF).toShort())
        buf.put(data)
        return buf.array()
    }

    fun unpackVideoPayload(payload: ByteArray): VideoSubPayload {
        if (payload.size < 13) throw ProtocolException("Truncated video frame payload")
        val buf = ByteBuffer.wrap(payload).order(ByteOrder.BIG_ENDIAN)
        val kind = buf.get().toInt() and 0xFF
        val ptsUs = buf.long
        val width = buf.short.toInt() and 0xFFFF
        val height = buf.short.toInt() and 0xFFFF
        val data = payload.copyOfRange(13, payload.size)
        return VideoSubPayload(kind, ptsUs, width, height, data)
    }

    fun packAudioPayload(codec: Int, sampleRate: Int, channels: Int, ptsUs: Long, data: ByteArray): ByteArray {
        val buf = ByteBuffer.allocate(14 + data.size).order(ByteOrder.BIG_ENDIAN)
        buf.put((codec and 0xFF).toByte())
        buf.putInt(sampleRate)
        buf.put((channels and 0xFF).toByte())
        buf.putLong(ptsUs)
        buf.put(data)
        return buf.array()
    }

    fun unpackAudioPayload(payload: ByteArray): AudioSubPayload {
        if (payload.size < 14) throw ProtocolException("Truncated audio frame payload")
        val buf = ByteBuffer.wrap(payload).order(ByteOrder.BIG_ENDIAN)
        val codec = buf.get().toInt() and 0xFF
        val sampleRate = buf.int
        val channels = buf.get().toInt() and 0xFF
        val ptsUs = buf.long
        val data = payload.copyOfRange(14, payload.size)
        return AudioSubPayload(codec, sampleRate, channels, ptsUs, data)
    }

    fun uuidToBytes(uuid: UUID): ByteArray {
        val buf = ByteBuffer.allocate(16).order(ByteOrder.BIG_ENDIAN)
        buf.putLong(uuid.mostSignificantBits)
        buf.putLong(uuid.leastSignificantBits)
        return buf.array()
    }

    fun packFileChunk(uuidBytes: ByteArray, offset: Long, chunk: ByteArray): ByteArray {
        require(uuidBytes.size == 16) { "uuidBytes must be 16 bytes" }
        val buf = ByteBuffer.allocate(24 + chunk.size).order(ByteOrder.BIG_ENDIAN)
        buf.put(uuidBytes)
        buf.putLong(offset)
        buf.put(chunk)
        return buf.array()
    }

    fun unpackFileChunk(payload: ByteArray): FileChunkPayload {
        if (payload.size < 24) throw ProtocolException("Truncated file data chunk")
        val uid = payload.copyOfRange(0, 16)
        val offset = ByteBuffer.wrap(payload, 16, 8).order(ByteOrder.BIG_ENDIAN).long
        val chunk = payload.copyOfRange(24, payload.size)
        return FileChunkPayload(uid, offset, chunk)
    }
}

class SessionCipher(
    sessionKey: ByteArray,
    private val sendDir: ByteArray,
    private val recvDir: ByteArray
) {
    private val keySpec = SecretKeySpec(sessionKey, "AES")

    init {
        require(sessionKey.size == 32) { "Session key must be 32 bytes" }
        require(sendDir.size == 4 && recvDir.size == 4) { "Direction prefixes must be 4 bytes" }
    }

    private fun buildNonce(direction: ByteArray, seq: Long): ByteArray {
        val buf = ByteBuffer.allocate(12).order(ByteOrder.BIG_ENDIAN)
        buf.put(direction)
        buf.putLong(seq and 0xFFFFFFFFL)
        return buf.array()
    }

    private fun buildAad(channel: Int, seq: Long): ByteArray {
        val buf = ByteBuffer.allocate(6).order(ByteOrder.BIG_ENDIAN)
        buf.put(DeckLinkFraming.PROTOCOL_VERSION.toByte())
        buf.put((channel and 0xFF).toByte())
        buf.putInt((seq and 0xFFFFFFFFL).toInt())
        return buf.array()
    }

    @Synchronized
    fun encrypt(channel: Int, seq: Long, plaintext: ByteArray): ByteArray {
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, keySpec, GCMParameterSpec(128, buildNonce(sendDir, seq)))
        cipher.updateAAD(buildAad(channel, seq))
        return cipher.doFinal(plaintext)
    }

    @Synchronized
    fun decrypt(channel: Int, seq: Long, ciphertext: ByteArray): ByteArray {
        try {
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.DECRYPT_MODE, keySpec, GCMParameterSpec(128, buildNonce(recvDir, seq)))
            cipher.updateAAD(buildAad(channel, seq))
            return cipher.doFinal(ciphertext)
        } catch (e: GeneralSecurityException) {
            throw ProtocolException("Frame authentication / AES-GCM tag verification failed", e)
        }
    }
}

class AuthRateLimiter(
    private val maxFailures: Int = 5,
    private val windowMs: Long = 60_000L,
    private val lockoutMs: Long = 60_000L
) {
    private val failures = mutableMapOf<String, MutableList<Long>>()
    private val lockedUntil = mutableMapOf<String, Long>()

    @Synchronized
    fun isLocked(ip: String, nowMs: Long = System.currentTimeMillis()): Boolean {
        val until = lockedUntil[ip] ?: return false
        if (until > nowMs) return true
        lockedUntil.remove(ip)
        return false
    }

    @Synchronized
    fun recordFailure(ip: String, nowMs: Long = System.currentTimeMillis()): Boolean {
        val list = failures.getOrPut(ip) { mutableListOf() }
        list.removeAll { nowMs - it > windowMs }
        list.add(nowMs)
        if (list.size >= maxFailures) {
            lockedUntil[ip] = nowMs + lockoutMs
            list.clear()
            return true
        }
        return false
    }

    @Synchronized
    fun recordSuccess(ip: String) {
        failures.remove(ip)
        lockedUntil.remove(ip)
    }
}
