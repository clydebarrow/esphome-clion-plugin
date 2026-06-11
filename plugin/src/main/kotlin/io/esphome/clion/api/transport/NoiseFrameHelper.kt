package io.esphome.clion.api.transport

import java.io.EOFException
import java.io.InputStream
import java.io.OutputStream
import java.util.Base64

/**
 * Encrypted ESPHome API framing using `Noise_NNpsk0_25519_ChaChaPoly_SHA256`,
 * the suite a device's `api: encryption: key:` selects.
 *
 * Frames are `0x01 | uint16-BE length | body`. During the handshake the body is
 * `<status byte> | <noise message>` (status `0x00` = ok, `0x01` = reject);
 * afterwards it is the ChaCha20-Poly1305 ciphertext of
 * `type(2) | length(2) | payload`. The exchange matches ESPHome's
 * `api_frame_helper_noise.cpp`:
 *   client-hello (empty) → server-hello → Noise msg1 (→ psk, e) → msg2 (← e, ee).
 */
class NoiseFrameHelper(
    private val input: InputStream,
    private val output: OutputStream,
    encryptionKey: String,
) : FrameHelper {

    private val psk: ByteArray = decodeKey(encryptionKey)
    private lateinit var sendCipher: CipherState
    private lateinit var recvCipher: CipherState

    override fun handshake() {
        writeRawFrame(ByteArray(0)) // client hello: empty, its length feeds the prologue
        val serverHello = readRawFrame()
        require(serverHello.isNotEmpty() && serverHello[0].toInt() == 0x01) {
            "unexpected server hello / protocol byte"
        }

        val ss = SymmetricState(PROTOCOL)
        ss.mixHash(PROLOGUE) // "NoiseAPIInit" + uint16-BE(0) for the empty client hello
        ss.mixKeyAndHash(psk)

        // -> psk, e   (write message 1)
        val e = NoiseCrypto.generateKeyPair()
        ss.mixHash(e.publicRaw)
        ss.mixKey(e.publicRaw) // 'e' also mixes the key under the psk modifier
        val msg1 = e.publicRaw + ss.encryptAndHash(ByteArray(0))
        writeRawFrame(byteArrayOf(0x00) + msg1)

        // <- e, ee    (read message 2)
        val body = readRawFrame()
        require(body.isNotEmpty()) { "empty handshake reply" }
        if (body[0].toInt() != 0x00) {
            val reason = String(body, 1, body.size - 1, Charsets.UTF_8)
            throw SecurityException("Handshake rejected: ${reason.ifBlank { "wrong encryption key?" }}")
        }
        require(body.size >= 1 + 32 + 16) { "short handshake reply" }
        val re = body.copyOfRange(1, 33)
        ss.mixHash(re)
        ss.mixKey(re)
        ss.mixKey(e.dh(re))
        ss.decryptAndHash(body.copyOfRange(33, body.size)) // verifies the tag

        val (k1, k2) = ss.split()
        sendCipher = CipherState(k1) // initiator → device
        recvCipher = CipherState(k2) // device → initiator
    }

    override fun writeMessage(type: Int, payload: ByteArray) {
        val plain = ByteArray(4 + payload.size)
        plain[0] = (type ushr 8).toByte(); plain[1] = type.toByte()
        plain[2] = (payload.size ushr 8).toByte(); plain[3] = payload.size.toByte()
        payload.copyInto(plain, 4)
        writeRawFrame(sendCipher.encrypt(plain))
    }

    override fun readMessage(): FrameHelper.Frame {
        val plain = recvCipher.decrypt(readRawFrame())
        require(plain.size >= 4) { "short message" }
        val type = ((plain[0].toInt() and 0xFF) shl 8) or (plain[1].toInt() and 0xFF)
        val len = ((plain[2].toInt() and 0xFF) shl 8) or (plain[3].toInt() and 0xFF)
        require(4 + len <= plain.size) { "declared length past message" }
        return FrameHelper.Frame(type, plain.copyOfRange(4, 4 + len))
    }

    private fun writeRawFrame(body: ByteArray) {
        synchronized(output) {
            output.write(0x01)
            output.write((body.size ushr 8) and 0xFF)
            output.write(body.size and 0xFF)
            output.write(body)
            output.flush()
        }
    }

    private fun readRawFrame(): ByteArray {
        val indicator = input.read()
        if (indicator < 0) throw EOFException("connection closed")
        require(indicator == 0x01) { "bad indicator byte $indicator" }
        val hi = input.read(); val lo = input.read()
        if (hi < 0 || lo < 0) throw EOFException("connection closed mid-header")
        val len = (hi shl 8) or lo
        require(len <= FrameHelper.MAX_PAYLOAD) { "frame length $len too large" }
        return FrameHelper.readExact(input, len)
    }

    /** Noise symmetric state: chaining key, transcript hash, and the running AEAD key. */
    private class SymmetricState(protocolName: String) {
        private var ck: ByteArray
        private var h: ByteArray
        private var key: ByteArray? = null
        private var nonce = 0L

        init {
            val pn = protocolName.toByteArray(Charsets.US_ASCII)
            h = if (pn.size <= 32) pn.copyOf(32) else NoiseCrypto.sha256(pn)
            ck = h.copyOf()
        }

        fun mixHash(data: ByteArray) { h = NoiseCrypto.sha256(h + data) }

        fun mixKey(input: ByteArray) {
            val o = NoiseCrypto.hkdf(ck, input, 2)
            ck = o[0]; initKey(o[1])
        }

        fun mixKeyAndHash(input: ByteArray) {
            val o = NoiseCrypto.hkdf(ck, input, 3)
            ck = o[0]; mixHash(o[1]); initKey(o[2])
        }

        private fun initKey(k: ByteArray) { key = k; nonce = 0 }

        fun encryptAndHash(plaintext: ByteArray): ByteArray =
            NoiseCrypto.encrypt(key!!, nonce++, h, plaintext).also { mixHash(it) }

        fun decryptAndHash(ciphertext: ByteArray): ByteArray =
            NoiseCrypto.decrypt(key!!, nonce++, h, ciphertext).also { mixHash(ciphertext) }

        fun split(): Pair<ByteArray, ByteArray> {
            val o = NoiseCrypto.hkdf(ck, ByteArray(0), 2)
            return o[0] to o[1]
        }
    }

    /** A data-phase cipher with its own incrementing nonce and empty AD. */
    private class CipherState(private val key: ByteArray) {
        private var nonce = 0L
        fun encrypt(plaintext: ByteArray): ByteArray = NoiseCrypto.encrypt(key, nonce++, EMPTY_AD, plaintext)
        fun decrypt(ciphertext: ByteArray): ByteArray = NoiseCrypto.decrypt(key, nonce++, EMPTY_AD, ciphertext)
    }

    companion object {
        private const val PROTOCOL = "Noise_NNpsk0_25519_ChaChaPoly_SHA256"
        private val PROLOGUE = "NoiseAPIInit".toByteArray(Charsets.US_ASCII) + byteArrayOf(0, 0)
        private val EMPTY_AD = ByteArray(0)

        private fun decodeKey(key: String): ByteArray {
            val bytes = try {
                Base64.getDecoder().decode(key.trim())
            } catch (e: IllegalArgumentException) {
                throw SecurityException("api encryption key is not valid base64")
            }
            require(bytes.size == 32) { "api encryption key must decode to 32 bytes (got ${bytes.size})" }
            return bytes
        }
    }
}
