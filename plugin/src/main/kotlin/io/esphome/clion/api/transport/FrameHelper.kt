package io.esphome.clion.api.transport

import java.io.InputStream

/**
 * Reads and writes ESPHome native-API frames over a socket's streams. The
 * plaintext and Noise transports differ only in framing/encryption, so the
 * connection talks to this interface and stays transport-agnostic.
 */
interface FrameHelper {
    /** Perform any transport handshake (Noise); a no-op for plaintext. */
    fun handshake()

    /** Frame and send a single API message. */
    fun writeMessage(type: Int, payload: ByteArray)

    /** Block until a full message is read. */
    fun readMessage(): Frame

    data class Frame(val type: Int, val payload: ByteArray)

    companion object {
        /**
         * Upper bound on a single frame's payload, so a malformed/hostile peer
         * can't drive a huge allocation and OOM the IDE. ESPHome's own messages
         * are far smaller than this.
         */
        const val MAX_PAYLOAD = 1 shl 20 // 1 MiB

        /** Read a base-128 varint from [input], or throw on EOF. */
        fun readVarint(input: InputStream): Long {
            var result = 0L
            var shift = 0
            while (shift < 64) {
                val b = input.read()
                if (b < 0) throw java.io.EOFException("stream closed mid-varint")
                result = result or ((b.toLong() and 0x7F) shl shift)
                if (b and 0x80 == 0) return result
                shift += 7
            }
            error("varint too long")
        }

        /** Read exactly [n] bytes from [input], or throw on EOF. */
        fun readExact(input: InputStream, n: Int): ByteArray {
            val buf = ByteArray(n)
            var off = 0
            while (off < n) {
                val r = input.read(buf, off, n - off)
                if (r < 0) throw java.io.EOFException("stream closed (${off}/$n bytes)")
                off += r
            }
            return buf
        }
    }
}
