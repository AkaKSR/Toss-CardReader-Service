package com.toss.cardreader.reader

import com.toss.cardreader.CardReaderError
import com.toss.cardreader.CardReaderException
import com.toss.cardreader.CardTypes
import com.toss.cardreader.MsrCardData
import com.toss.cardreader.ServiceStatus
import com.toss.cardreader.reader.hw.CardBackend
import com.toss.cardreader.reader.hw.CardEvents
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class ReaderSessionsTest {
    private class FakeReader : CardBackend {
        override var connected = true
        override var portBusy = false
        override val kind = ServiceStatus.BACKEND_DIRECT
        var events: CardEvents? = null
        var waits = 0
        var cancels = 0
        var powerOffs = 0
        var present = ServiceStatus.CARD_ABSENT

        override fun checkCard(cardTypes: Int, timeoutSec: Int, events: CardEvents) {
            waits++
            this.events = events
        }

        override fun cancelCheckCard() { cancels++ }
        override fun transmitApdu(cardType: Int, apdu: ByteArray): ByteArray = byteArrayOf(0x6A, 0x82.toByte())
        override fun cardOff(cardType: Int): Int { powerOffs++; return 0 }
        override fun cardExistStatus(cardType: Int): Int = present
    }

    private class Recorder : ReaderSessions.Listener {
        val detected = ArrayList<Int>()
        val errors = ArrayList<Int>()
        override fun onCardDetected(type: Int) { detected += type }
        override fun onError(code: Int, message: String?) { errors += code }
    }

    private val hw = FakeReader()
    private var clock = 0L
    private val sessions = ReaderSessions(hw) { clock }
    private val both = CardTypes.IC or CardTypes.MSR

    private fun refused(code: Int, block: () -> Unit) {
        try {
            block()
            fail("expected ${CardReaderError.name(code)}")
        } catch (e: CardReaderException) {
            assertEquals(CardReaderError.name(code), CardReaderError.name(e.code))
        }
    }

    @Test
    fun secondAppIsBusyWhileFirstWaits() {
        sessions.start(owner = 100, types = both, timeoutSec = 30, listener = Recorder())
        refused(CardReaderError.BUSY) { sessions.start(owner = 200, types = both, timeoutSec = 30, listener = Recorder()) }
        assertEquals(1, hw.waits)
        assertTrue(sessions.status(200).busy)
        assertFalse(sessions.status(100).busy)
    }

    @Test
    fun sameAppReplacesItsOwnWait() {
        val first = Recorder()
        sessions.start(100, both, 30, first)
        sessions.start(100, both, 30, Recorder())
        assertEquals(listOf(CardReaderError.CANCELLED), first.errors)
        assertEquals(2, hw.waits)
    }

    @Test
    fun swipeIsHandedOnceToTheOwnerOnly() {
        val r = Recorder()
        sessions.start(100, both, 30, r)
        hw.events!!.onMagCard(null, "4242424242424242=4912101", null)
        assertEquals(listOf(CardTypes.MSR), r.detected)
        refused(CardReaderError.NO_CARD) { sessions.readMsr(owner = 200) }
        refused(CardReaderError.NO_CARD) { sessions.readIc(owner = 100) }
        assertEquals(MsrCardData(null, "4242424242424242=4912101", null), sessions.readMsr(100))
        refused(CardReaderError.NO_CARD) { sessions.readMsr(100) }
        assertFalse(sessions.active)
    }

    @Test
    fun icReadPowersTheCardOffAndEndsTheSession() {
        sessions.start(100, CardTypes.IC, 30, Recorder())
        hw.events!!.onIcCard("3B00")
        val ic = sessions.readIc(100)
        assertEquals("3B00", ic.atr)
        assertNull(ic.pan)
        assertEquals(1, hw.powerOffs)
        assertFalse(sessions.active)
        sessions.start(200, both, 30, Recorder())
    }

    @Test
    fun readerCodesMapToInterfaceErrors() {
        val r = Recorder()
        sessions.start(100, both, 30, r)
        hw.events!!.onError(-30005, "timeout")
        sessions.start(100, both, 30, r)
        hw.events!!.onError(-20001, "repeat")
        sessions.start(100, both, 30, r)
        hw.events!!.onError(-9001, "contactless")
        assertEquals(listOf(CardReaderError.TIMEOUT, CardReaderError.BUSY, CardReaderError.UNSUPPORTED_CARD), r.errors)
        assertFalse(sessions.active)
    }

    @Test
    fun lateEventsOfAnEndedSessionAreDropped() {
        val r = Recorder()
        sessions.start(100, both, 30, r)
        val stale = hw.events!!
        sessions.cancel(100)
        stale.onIcCard("3B00")
        assertTrue(r.detected.isEmpty())
        assertEquals(1, hw.cancels)
    }

    @Test
    fun cancelFromAnotherAppLeavesTheSessionAlone() {
        sessions.start(100, both, 30, Recorder())
        sessions.cancel(200)
        assertTrue(sessions.active)
    }

    @Test
    fun unreadDetectedCardStopsBlockingAfterTtl() {
        sessions.start(100, CardTypes.IC, 30, Recorder())
        hw.events!!.onIcCard("3B00")
        refused(CardReaderError.BUSY) { sessions.start(200, both, 30, Recorder()) }
        clock += ReaderSessions.DETECTED_TTL_MS + 1
        sessions.start(200, both, 30, Recorder())
        assertEquals(1, hw.powerOffs)
    }

    @Test
    fun portHeldByAnotherProcessIsBusyNotMissing() {
        hw.connected = false
        refused(CardReaderError.NOT_READY) { sessions.start(100, both, 30, Recorder()) }
        hw.portBusy = true
        refused(CardReaderError.BUSY) { sessions.start(100, both, 30, Recorder()) }
        assertTrue(sessions.status(100).busy)
        assertEquals(ServiceStatus.CARD_UNKNOWN, sessions.status(100).icCard)
    }

    @Test
    fun badArgumentsAreRejectedBeforeTheReader() {
        refused(CardReaderError.INVALID_ARGUMENT) { sessions.start(100, 0, 30, Recorder()) }
        refused(CardReaderError.INVALID_ARGUMENT) { sessions.start(100, 4, 30, Recorder()) }
        refused(CardReaderError.INVALID_ARGUMENT) { sessions.start(100, both, 0, Recorder()) }
        refused(CardReaderError.INVALID_ARGUMENT) { sessions.start(100, both, 121, Recorder()) }
        assertEquals(0, hw.waits)
    }
}
