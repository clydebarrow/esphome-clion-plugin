package io.esphome.clion.api.proto

/**
 * A minimal reader for the protobuf wire format — only what the ESPHome native
 * API needs (varint, length-delimited, fixed32/float). Avoids pulling in a full
 * protobuf runtime for the small message subset the tool window decodes.
 *
 * Iterate fields by reading [readTag] then the value matching its wire type;
 * call [skip] for fields you don't care about.
 */
class ProtoReader(private val buf: ByteArray, private var pos: Int = 0, private val end: Int = buf.size) {

    fun hasMore(): Boolean = pos < end

    /** field number in the high bits, wire type in the low 3 bits. */
    fun readTag(): Int = readVarint().toInt()

    fun fieldOf(tag: Int): Int = tag ushr 3
    fun wireOf(tag: Int): Int = tag and 0x7

    fun readVarint(): Long {
        var result = 0L
        var shift = 0
        while (shift < 64) {
            require(pos < end) { "truncated varint" }
            val b = buf[pos++].toInt() and 0xFF
            result = result or ((b.toLong() and 0x7F) shl shift)
            if (b and 0x80 == 0) return result
            shift += 7
        }
        error("varint too long")
    }

    fun readBool(): Boolean = readVarint() != 0L

    /** fixed32 as an unsigned value widened to Long (ESPHome entity keys are fixed32). */
    fun readFixed32(): Long {
        require(pos + 4 <= end) { "truncated fixed32" }
        val v = (buf[pos].toLong() and 0xFF) or
            ((buf[pos + 1].toLong() and 0xFF) shl 8) or
            ((buf[pos + 2].toLong() and 0xFF) shl 16) or
            ((buf[pos + 3].toLong() and 0xFF) shl 24)
        pos += 4
        return v
    }

    fun readFloat(): Float = Float.fromBits(readFixed32().toInt())

    fun readBytes(): ByteArray {
        val len = readVarint().toInt()
        require(len >= 0 && pos + len <= end) { "truncated bytes" }
        val out = buf.copyOfRange(pos, pos + len)
        pos += len
        return out
    }

    fun readString(): String = String(readBytes(), Charsets.UTF_8)

    /** Consume a field's value given its [wire] type, for fields we ignore. */
    fun skip(wire: Int) {
        when (wire) {
            0 -> readVarint()
            1 -> pos += 8
            2 -> {
                val len = readVarint()
                require(len in 0..(end - pos).toLong()) { "length-delimited field out of range" }
                pos += len.toInt()
            }
            5 -> pos += 4
            else -> error("unknown wire type $wire")
        }
        require(pos <= end) { "skip past end" }
    }
}
