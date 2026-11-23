package com.toss.cardreader.reader.hw.sp

import com.toss.cardreader.reader.emv.hexToBytes
import com.toss.cardreader.reader.emv.toHex
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class SpProtocolTest {
    private val status = SpProtocol.frame(0, SpCommand.IC_STATUS, listOf(SpProtocol.text("SCC0")))

    private fun reply(request: ByteArray, payload: ByteArray): ByteArray {
        val out = request.copyOf(SpProtocol.HEADER) + payload + byteArrayOf(0)
        out[4] = payload.size.toByte()
        out[5] = (payload.size ushr 8).toByte()
        out[out.size - 1] = SpProtocol.xor(out, 0, out.size - 1).toByte()
        return out
    }

    private fun okPayload(vararg extra: ByteArray): ByteArray =
        byteArrayOf(0) + SpProtocol.encodeArgs(listOf(SpProtocol.u32(0)) + extra)

    @Test
    fun reopenedLinkNumbersFramesFromZeroAgain() {
        val seq = SpSequence()
        repeat(0x1B) { seq.take() } // first link: frames 00..1A, then the port closes
        seq.restart() // the next open
        val first = SpProtocol.frame(seq.take(), SpCommand.IC_STATUS, listOf(SpProtocol.text("SCC0")))
        assertEquals("DE00120A08000105005343433000A9", first.toHex())
        assertEquals(1, seq.take())
    }

    @Test
    fun statusFrameMatchesReviewedWireBytes() {
        assertEquals("DE00120A080001050053434330 00A9".replace(" ", ""), status.toHex())
    }

    @Test
    fun parsesEchoedReplyAndReturnsResultAndData() {
        val r = SpProtocol.parseReply(status, reply(status, okPayload("3B00".hexToBytes())))
        assertEquals(0, r.result)
        assertEquals("3B00", r.data.single().toHex())
    }

    @Test
    fun parsesBothNoCardStatusRepliesCapturedFromDevice() {
        val first = SpProtocol.parseReply(status, "DE00120A0800000104001FD1FFFF05".hexToBytes())
        val later = SpProtocol.parseReply(status, "DE00120A0800010104001FD1FFFF04".hexToBytes())
        assertEquals(SpProtocol.NO_CARD, first.result)
        assertEquals(SpProtocol.NO_CARD, later.result)
        assertTrue(first.freshStart)
        assertFalse(later.freshStart)
    }

    @Test
    fun rejectsReplyWithBadChecksum() {
        val bad = reply(status, okPayload())
        bad[bad.size - 1] = (bad.last() + 1).toByte()
        assertThrows(SpProtocolException::class.java) { SpProtocol.parseReply(status, bad) }
    }

    @Test
    fun rejectsReplyWhoseHeaderDoesNotEchoRequest() {
        for (i in 0 until 4) {
            val other = status.copyOf().also { it[i] = (it[i] + 1).toByte() }
            assertThrows(SpProtocolException::class.java) { SpProtocol.parseReply(status, reply(other, okPayload())) }
        }
    }

    @Test
    fun rejectsTruncatedOrOverrunningFrames() {
        val good = reply(status, okPayload("AA".hexToBytes()))
        assertThrows(SpProtocolException::class.java) { SpProtocol.parseReply(status, good.copyOf(good.size - 1)) }
        val overrun = byteArrayOf(0, 1, 9, 0, 1)
        assertThrows(SpProtocolException::class.java) { SpProtocol.parseReply(status, reply(status, overrun)) }
    }


    @Test
    fun allowlistAcceptsOnlyCardGroupCommands() {
        for (c in SpCommand.entries) assertTrue(SpCommand.allowed(0x12, c.id))
        val denied = listOf(0x11 to 0x0A, 0x14 to 0x45, 0x14 to 0x62, 0x17 to 0x00, 0x40 to 0x01, 0x12 to 0x08, 0x12 to 0x09, 0x92 to 0x0A)
        for ((g, c) in denied) assertFalse("$g/$c", SpCommand.allowed(g, c))
    }

    @Test
    fun apduRecordCoversShortCases() {
        assertEquals("00A40400" + "0000" + "0000", SpProtocol.apduRecord("00A40400".hexToBytes()).toHex())
        assertEquals("00B2010C" + "0000" + "0001", SpProtocol.apduRecord("00B2010C00".hexToBytes()).toHex())
        assertEquals("00A40400" + "0200" + "3F00" + "0000", SpProtocol.apduRecord("00A404000 23F00".replace(" ", "").hexToBytes()).toHex())
        assertEquals("80A80000" + "0200" + "8300" + "0001", SpProtocol.apduRecord("80A80000028300 00".replace(" ", "").hexToBytes()).toHex())
        assertThrows(IllegalArgumentException::class.java) { SpProtocol.apduRecord("00A4040005AA".hexToBytes()) }
    }

    @Test
    fun apduResponseStripsLengthAndKeepsStatusWord() {
        assertArrayEquals("6F009000".hexToBytes(), SpProtocol.apduResponse("02006F009000".hexToBytes()))
        assertThrows(SpProtocolException::class.java) { SpProtocol.apduResponse("05006F009000".hexToBytes()) }
    }
}
