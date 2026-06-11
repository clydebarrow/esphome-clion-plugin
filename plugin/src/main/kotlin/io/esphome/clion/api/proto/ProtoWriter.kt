package io.esphome.clion.api.proto

import java.io.ByteArrayOutputStream

/**
 * A minimal protobuf wire-format writer — only the field types the ESPHome API
 * requests we send need (varint, string). Counterpart to [ProtoReader].
 */
class ProtoWriter {
    private val out = ByteArrayOutputStream()

    fun string(field: Int, value: String): ProtoWriter {
        if (value.isEmpty()) return this
        val bytes = value.toByteArray(Charsets.UTF_8)
        tag(field, 2)
        varintRaw(bytes.size.toLong())
        out.write(bytes)
        return this
    }

    fun uint32(field: Int, value: Int): ProtoWriter {
        if (value == 0) return this
        tag(field, 0)
        varintRaw(value.toLong() and 0xFFFFFFFFL)
        return this
    }

    /** fixed32 (wire type 5), little-endian — always written (entity keys need it). */
    fun fixed32(field: Int, value: Long): ProtoWriter {
        require(value in 0..0xFFFF_FFFFL) { "fixed32 value out of range: $value" }
        tag(field, 5)
        out.write((value and 0xFF).toInt())
        out.write(((value ushr 8) and 0xFF).toInt())
        out.write(((value ushr 16) and 0xFF).toInt())
        out.write(((value ushr 24) and 0xFF).toInt())
        return this
    }

    /** A bool field; only emitted when true (proto3 default-omit). */
    fun bool(field: Int, value: Boolean): ProtoWriter {
        if (!value) return this
        tag(field, 0)
        varintRaw(1)
        return this
    }

    fun toByteArray(): ByteArray = out.toByteArray()

    private fun tag(field: Int, wire: Int) = varintRaw((field.toLong() shl 3) or wire.toLong())

    private fun varintRaw(value: Long) {
        var v = value
        while (true) {
            val b = (v and 0x7F).toInt()
            v = v ushr 7
            if (v == 0L) {
                out.write(b)
                return
            }
            out.write(b or 0x80)
        }
    }

    companion object {
        /** Encode a single varint to a byte array (used for plaintext frame headers). */
        fun varint(value: Long): ByteArray = ProtoWriter().apply { varintRaw(value) }.out.toByteArray()
    }
}
