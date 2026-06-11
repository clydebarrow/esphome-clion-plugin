package io.esphome.clion.api.transport

import java.security.KeyFactory
import java.security.KeyPairGenerator
import java.security.MessageDigest
import java.security.spec.X509EncodedKeySpec
import javax.crypto.Cipher
import javax.crypto.KeyAgreement
import javax.crypto.Mac
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * The crypto primitives for `Noise_NNpsk0_25519_ChaChaPoly_SHA256`, built only
 * from the JBR 21 JCA — X25519 (`XDH`), ChaCha20-Poly1305, and HMAC/SHA-256 — so
 * the encrypted API needs no third-party library.
 */
internal object NoiseCrypto {

    fun sha256(data: ByteArray): ByteArray = MessageDigest.getInstance("SHA-256").digest(data)

    fun hmacSha256(key: ByteArray, data: ByteArray): ByteArray =
        Mac.getInstance("HmacSHA256").apply { init(SecretKeySpec(key, "HmacSHA256")) }.doFinal(data)

    /** Noise HKDF: returns [num] 32-byte outputs chained from [chainingKey] and [ikm]. */
    fun hkdf(chainingKey: ByteArray, ikm: ByteArray, num: Int): List<ByteArray> {
        val temp = hmacSha256(chainingKey, ikm)
        val out = ArrayList<ByteArray>(num)
        var prev = ByteArray(0)
        for (i in 1..num) {
            prev = hmacSha256(temp, prev + i.toByte())
            out.add(prev)
        }
        return out
    }

    /** ChaCha20-Poly1305 with the Noise 12-byte nonce (4 zero bytes + little-endian counter). */
    fun encrypt(key: ByteArray, nonce: Long, ad: ByteArray, plaintext: ByteArray): ByteArray =
        chacha(Cipher.ENCRYPT_MODE, key, nonce, ad, plaintext)

    fun decrypt(key: ByteArray, nonce: Long, ad: ByteArray, ciphertext: ByteArray): ByteArray =
        chacha(Cipher.DECRYPT_MODE, key, nonce, ad, ciphertext)

    private fun chacha(mode: Int, key: ByteArray, nonce: Long, ad: ByteArray, data: ByteArray): ByteArray {
        val iv = ByteArray(12)
        for (i in 0 until 8) iv[4 + i] = (nonce ushr (8 * i)).toByte()
        val cipher = Cipher.getInstance("ChaCha20-Poly1305")
        cipher.init(mode, SecretKeySpec(key, "ChaCha20"), IvParameterSpec(iv))
        if (ad.isNotEmpty()) cipher.updateAAD(ad)
        return cipher.doFinal(data)
    }

    /** A freshly generated X25519 ephemeral key pair (raw 32-byte public + the private key). */
    class KeyPair(val publicRaw: ByteArray, private val privateKey: java.security.PrivateKey) {
        /** X25519 shared secret with the peer's raw 32-byte public key. */
        fun dh(peerPublicRaw: ByteArray): ByteArray =
            KeyAgreement.getInstance("XDH").apply {
                init(privateKey)
                doPhase(publicKeyFromRaw(peerPublicRaw), true)
            }.generateSecret()
    }

    fun generateKeyPair(): KeyPair {
        val kp = KeyPairGenerator.getInstance("XDH").apply { initialize(java.security.spec.NamedParameterSpec.X25519) }.generateKeyPair()
        val raw = kp.public.encoded.copyOfRange(kp.public.encoded.size - 32, kp.public.encoded.size)
        return KeyPair(raw, kp.private)
    }

    /** Wrap a raw 32-byte X25519 public key in its SPKI DER for the JCA KeyFactory. */
    fun publicKeyFromRaw(raw: ByteArray): java.security.PublicKey {
        require(raw.size == 32) { "X25519 public key must be 32 bytes" }
        return KeyFactory.getInstance("XDH").generatePublic(X509EncodedKeySpec(X25519_SPKI_PREFIX + raw))
    }

    // SPKI prefix for an X25519 public key: SEQUENCE{ SEQUENCE{ OID 1.3.101.110 }, BIT STRING(0x00 + key) }.
    private val X25519_SPKI_PREFIX =
        byteArrayOf(0x30, 0x2a, 0x30, 0x05, 0x06, 0x03, 0x2b, 0x65, 0x6e, 0x03, 0x21, 0x00)
}
