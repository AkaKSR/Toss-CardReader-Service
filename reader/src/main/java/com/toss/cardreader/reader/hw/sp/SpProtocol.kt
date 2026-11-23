package com.toss.cardreader.reader.hw.sp

import java.io.ByteArrayOutputStream

/**
 * Wire format of the reader's secure processor link, written from our own protocol notes (docs/sp_protocol.md).
 *
 * Frame: MARK SEQ GROUP CMD LEN(u16 LE) PAYLOAD XOR, where XOR covers every byte before it.
 * Payload: ARGC(u8) then ARGC x [LEN(u16 LE) DATA].
 *
 * Only the card commands in [SpCommand] can be encoded. Anything else (firmware, keys, certificates, settings,
 * applets, power/reset) has no representation here, so it cannot reach the wire through this code.
 */
object SpProtocol {
    const val MARK = 0xDE
    const val LINK_PROBE: Byte = 0x51 // 'Q'
    const val LINK_ACK: Byte = 0x41 // 'A'
    const val MAX_FRAME = 2048
    const val HEADER = 6
    const val NO_CARD = -12001

    fun xor(bytes: ByteArray, from: Int = 0, to: Int = bytes.size): Int {
        var acc = 0
        for (i in from until to) acc = acc xor (bytes[i].toInt() and 0xFF)
        return acc
    }

    fun frame(seq: Int, command: SpCommand, args: List<ByteArray>): ByteArray {
        val payload = encodeArgs(args)
        require(payload.size + HEADER + 1 <= MAX_FRAME) { "frame too long" }
        val out = ByteArray(HEADER + payload.size + 1)
        out[0] = MARK.toByte()
        out[1] = seq.toByte()
        out[2] = SpCommand.GROUP.toByte()
        out[3] = command.id.toByte()
        out[4] = payload.size.toByte()
        out[5] = (payload.size ushr 8).toByte()
        payload.copyInto(out, HEADER)
        out[out.size - 1] = xor(out, 0, out.size - 1).toByte()
        return out
    }

    fun encodeArgs(args: List<ByteArray>): ByteArray {
        require(args.size in 1..255) { "argument count" }
        val out = ByteArrayOutputStream()
        out.write(args.size)
        for (a in args) {
            require(a.size <= 0xFFFF) { "argument too long" }
            out.write(a.size and 0xFF)
            out.write(a.size ushr 8)
            out.write(a)
        }
        return out.toByteArray()
    }

    fun text(s: String): ByteArray = s.toByteArray(Charsets.US_ASCII) + 0
    fun u32(v: Int): ByteArray = byteArrayOf(v.toByte(), (v ushr 8).toByte(), (v ushr 16).toByte(), (v ushr 24).toByte())
    fun readU32(b: ByteArray, at: Int = 0): Int =
        (b[at].toInt() and 0xFF) or ((b[at + 1].toInt() and 0xFF) shl 8) or
            ((b[at + 2].toInt() and 0xFF) shl 16) or ((b[at + 3].toInt() and 0xFF) shl 24)
    fun readU16(b: ByteArray, at: Int): Int = (b[at].toInt() and 0xFF) or ((b[at + 1].toInt() and 0xFF) shl 8)

    /**
     * Parses one complete reply frame and checks it answers [request]: same marker, sequence, group and command
     * (the processor echoes the request header), declared length matches, XOR matches.
     */
    fun parseReply(request: ByteArray, reply: ByteArray): SpReply {
        if (reply.size < HEADER + 1) throw SpProtocolException("short frame")
        for (i in 0 until 4) if (reply[i] != request[i]) throw SpProtocolException("header mismatch at $i")
        val len = readU16(reply, 4)
        if (reply.size != HEADER + len + 1) throw SpProtocolException("length mismatch")
        if (xor(reply, 0, reply.size - 1) != (reply.last().toInt() and 0xFF)) throw SpProtocolException("checksum")
        if (len < 2) throw SpProtocolException("payload too short")
        val status = reply[HEADER].toInt() and 0xFF
        val count = reply[HEADER + 1].toInt() and 0xFF
        val args = ArrayList<ByteArray>(count)
        var p = HEADER + 2
        val end = HEADER + len
        repeat(count) {
            if (p + 2 > end) throw SpProtocolException("argument header overrun")
            val n = readU16(reply, p)
            p += 2
            if (p + n > end) throw SpProtocolException("argument overrun")
            args += reply.copyOfRange(p, p + n)
            p += n
        }
        if (p != end) throw SpProtocolException("trailing bytes")
        val result = args.firstOrNull()?.takeIf { it.size == 4 }?.let { readU32(it) }
            ?: throw SpProtocolException("missing result")
        return SpReply(result, args.drop(1), freshStart = status == 0)
    }

    /** Short ISO 7816 command APDU to the processor's record: header, Lc u16 LE, data, Le u16 LE. */
    fun apduRecord(apdu: ByteArray): ByteArray {
        require(apdu.size >= 4) { "APDU too short" }
        val head = apdu.copyOf(4)
        val (data, le) = when {
            apdu.size == 4 -> ByteArray(0) to 0
            apdu.size == 5 -> ByteArray(0) to ((apdu[4].toInt() and 0xFF).takeIf { it != 0 } ?: 256)
            else -> {
                val lc = apdu[4].toInt() and 0xFF
                require(lc != 0) { "extended APDU not supported" }
                when (apdu.size) {
                    5 + lc -> apdu.copyOfRange(5, 5 + lc) to 0
                    6 + lc -> apdu.copyOfRange(5, 5 + lc) to ((apdu[5 + lc].toInt() and 0xFF).takeIf { it != 0 } ?: 256)
                    else -> throw IllegalArgumentException("malformed APDU")
                }
            }
        }
        return head + byteArrayOf(data.size.toByte(), (data.size ushr 8).toByte()) + data +
            byteArrayOf(le.toByte(), (le ushr 8).toByte())
    }

    /** Processor APDU reply record (len u16 LE, data, SW1, SW2) to data + SW1 SW2. */
    fun apduResponse(record: ByteArray): ByteArray {
        if (record.size < 4) throw SpProtocolException("APDU reply too short")
        val n = readU16(record, 0)
        if (record.size != n + 4) throw SpProtocolException("APDU reply length")
        return record.copyOfRange(2, record.size)
    }
}

/** The complete set of commands this app may send: contact-card and magnetic-stripe reads, all in group 0x12. */
enum class SpCommand(val id: Int) {
    MSR_READ(0x01),
    IC_ACTIVATE(0x04),
    IC_EXCHANGE(0x06),
    IC_POWER_OFF(0x07),
    IC_STATUS(0x0A);

    companion object {
        const val GROUP = 0x12
        /** Guard for any raw (group, cmd) pair seen at a boundary, e.g. a replayed log or a test vector. */
        fun allowed(group: Int, cmd: Int): Boolean = group == GROUP && entries.any { it.id == cmd }
    }
}

/**
 * Sequence numbers of one link. The processor numbers frames from 00 after each link probe and refuses any other
 * number (reply group 00 cmd 00, code 6, echoing the refused sequence), so [restart] runs on every successful open.
 */
class SpSequence {
    private var next = 0

    fun restart() {
        next = 0
    }

    fun take(): Int = next.also { next = (it + 1) and 0xFF }
}

/** [freshStart]: the processor flags its first reply after it (re)started; informational, not an error. */
data class SpReply(val result: Int, val data: List<ByteArray>, val freshStart: Boolean = false)

class SpProtocolException(message: String) : Exception(message)

/** Another process is already exchanging frames on the reader port. */
class SpPortBusyException : java.io.IOException("reader port in use by another process")
