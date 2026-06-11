package io.esphome.clion.api.transport

import io.esphome.clion.api.proto.ProtoWriter
import java.io.InputStream
import java.io.OutputStream

/**
 * Unencrypted ESPHome API framing: each message is
 * `0x00 | varint(payload length) | varint(message type) | payload`.
 * Used when the device's `api:` has no `encryption: key:`.
 */
class PlaintextFrameHelper(
    private val input: InputStream,
    private val output: OutputStream,
) : FrameHelper {

    override fun handshake() = Unit // plaintext needs none

    override fun writeMessage(type: Int, payload: ByteArray) {
        synchronized(output) {
            output.write(0x00)
            output.write(ProtoWriter.varint(payload.size.toLong()))
            output.write(ProtoWriter.varint(type.toLong()))
            output.write(payload)
            output.flush()
        }
    }

    override fun readMessage(): FrameHelper.Frame {
        val preamble = input.read()
        if (preamble < 0) throw java.io.EOFException("connection closed")
        require(preamble == 0x00) { "bad preamble byte $preamble (encryption required?)" }
        val length = FrameHelper.readVarint(input)
        require(length in 0..FrameHelper.MAX_PAYLOAD.toLong()) { "frame length $length out of range" }
        val type = FrameHelper.readVarint(input).toInt()
        return FrameHelper.Frame(type, FrameHelper.readExact(input, length.toInt()))
    }
}
