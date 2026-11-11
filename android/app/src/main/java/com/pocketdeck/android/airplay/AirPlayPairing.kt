package com.pocketdeck.android.airplay

import org.bouncycastle.crypto.agreement.X25519Agreement
import org.bouncycastle.crypto.params.Ed25519PrivateKeyParameters
import org.bouncycastle.crypto.params.Ed25519PublicKeyParameters
import org.bouncycastle.crypto.params.X25519PrivateKeyParameters
import org.bouncycastle.crypto.params.X25519PublicKeyParameters
import org.bouncycastle.crypto.signers.Ed25519Signer
import java.security.MessageDigest
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * AirPlay legacy pairing (`pair-setup` / `pair-verify`) using Ed25519 + X25519 + AES-128-CTR.
 * Matches iOS Screen Mirroring expectations (`features = 0x5A7FFEE6`).
 */
class AirPlayServerIdentity(seed: ByteArray) {
    private val privateKey: Ed25519PrivateKeyParameters
    val publicKeyBytes: ByteArray
    val publicKeyHex: String

    init {
        require(seed.size == 32) { "Ed25519 seed must be 32 bytes" }
        privateKey = Ed25519PrivateKeyParameters(seed, 0)
        publicKeyBytes = privateKey.generatePublicKey().encoded
        publicKeyHex = publicKeyBytes.joinToString("") { "%02x".format(it) }
    }

    fun sign(message: ByteArray): ByteArray {
        val signer = Ed25519Signer()
        signer.init(true, privateKey)
        signer.update(message, 0, message.size)
        return signer.generateSignature()
    }

    companion object {
        fun fromHexOrGenerate(seedHex: String?): Pair<AirPlayServerIdentity, String> {
            val seed = if (seedHex != null && seedHex.length == 64) {
                ByteArray(32) { seedHex.substring(it * 2, it * 2 + 2).toInt(16).toByte() }
            } else {
                ByteArray(32).also { SecureRandom().nextBytes(it) }
            }
            val hex = seed.joinToString("") { "%02x".format(it) }
            return AirPlayServerIdentity(seed) to hex
        }
    }
}

class AirPlayPairingSession(private val identity: AirPlayServerIdentity) {
    var ecdhSecret: ByteArray? = null
        private set
    var verified: Boolean = false
        private set

    private var ourEcdhPub: ByteArray? = null
    private var theirEcdhPub: ByteArray? = null
    private var theirEdPub: ByteArray? = null
    private var aesKey: ByteArray? = null
    private var aesIv: ByteArray? = null

    fun pairSetup(data: ByteArray): ByteArray {
        require(data.size == 32) { "Invalid pair-setup payload (${data.size} bytes)" }
        return identity.publicKeyBytes.copyOf()
    }

    fun pairVerify(data: ByteArray): ByteArray {
        require(data.size >= 4) { "Invalid pair-verify payload" }
        val step = data[0].toInt() and 0xFF
        if (step == 1) {
            require(data.size == 68) { "Invalid pair-verify step 1 length (${data.size})" }
            val theirEcdh = data.copyOfRange(4, 36)
            val theirEd = data.copyOfRange(36, 68)
            theirEcdhPub = theirEcdh
            theirEdPub = theirEd

            val ourPriv = X25519PrivateKeyParameters(SecureRandom())
            val ourPub = ourPriv.generatePublicKey().encoded
            ourEcdhPub = ourPub

            val shared = ByteArray(32)
            val agreement = X25519Agreement()
            agreement.init(ourPriv)
            agreement.calculateAgreement(X25519PublicKeyParameters(theirEcdh, 0), shared, 0)
            ecdhSecret = shared

            val key = sha512("Pair-Verify-AES-Key".encodeToByteArray() + shared).copyOfRange(0, 16)
            val iv = sha512("Pair-Verify-AES-IV".encodeToByteArray() + shared).copyOfRange(0, 16)
            aesKey = key
            aesIv = iv

            val sig = identity.sign(ourPub + theirEcdh)
            val cipher = Cipher.getInstance("AES/CTR/NoPadding")
            cipher.init(Cipher.ENCRYPT_MODE, SecretKeySpec(key, "AES"), IvParameterSpec(iv))
            val encSig = cipher.doFinal(sig)
            return ourPub + encSig
        }

        if (step == 0) {
            val key = aesKey
            val iv = aesIv
            val ourPub = ourEcdhPub
            val theirEcdh = theirEcdhPub
            val theirEd = theirEdPub
            require(data.size == 68 && key != null && iv != null && ourPub != null && theirEcdh != null && theirEd != null) {
                "Invalid pair-verify step 2 state"
            }

            val cipher = Cipher.getInstance("AES/CTR/NoPadding")
            cipher.init(Cipher.DECRYPT_MODE, SecretKeySpec(key, "AES"), IvParameterSpec(iv))
            cipher.update(ByteArray(64)) // Advance past our 64-byte signature from step 1
            val clientSig = cipher.doFinal(data.copyOfRange(4, 68))

            val verifier = Ed25519Signer()
            verifier.init(false, Ed25519PublicKeyParameters(theirEd, 0))
            val msg = theirEcdh + ourPub
            verifier.update(msg, 0, msg.size)
            if (!verifier.verifySignature(clientSig)) {
                throw IllegalArgumentException("AirPlay client Ed25519 signature verification failed")
            }
            verified = true
            return ByteArray(0)
        }

        throw IllegalArgumentException("Unknown pair-verify flag $step")
    }

    companion object {
        internal fun sha512(input: ByteArray): ByteArray =
            MessageDigest.getInstance("SHA-512").digest(input)
    }
}
