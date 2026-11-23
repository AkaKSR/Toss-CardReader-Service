package com.toss.cardreader.reader

import android.os.SystemClock
import android.util.Log
import com.toss.cardreader.CardReader
import com.toss.cardreader.CardReaderError
import com.toss.cardreader.CardReaderException
import com.toss.cardreader.CardTypes
import com.toss.cardreader.IcCardData
import com.toss.cardreader.MsrCardData
import com.toss.cardreader.ServiceStatus
import com.toss.cardreader.reader.emv.EmvReader
import com.toss.cardreader.reader.emv.EmvResult
import com.toss.cardreader.reader.hw.CardBackend
import com.toss.cardreader.reader.hw.CardEvents
import com.toss.cardreader.reader.hw.PayHardware

/**
 * The single card session the reader allows. Used by the reader service (one per device, owners are client pids)
 * and by [LocalCardReader] inside an app. Refusals are [CardReaderException]s. A session belongs to the process that started it ([owner] = its pid):
 * another process gets BUSY until it ends, the same process replaces it. It ends on readIc/readMsr, cancel,
 * timeout or error, or when its owner dies. A detected card its owner never reads stops blocking others after
 * [DETECTED_TTL_MS]. The reader is only polled while a session is waiting.
 * Logcat gets session events and field counts only, never card data.
 */
class ReaderSessions(private val hw: CardBackend, private val now: () -> Long = SystemClock::elapsedRealtime) {

    interface Listener {
        fun onCardDetected(type: Int)
        fun onError(code: Int, message: String?)
        /** The session ended without an error (its card was read). */
        fun onEnded() {}
    }

    private class Session(val id: Int, val owner: Int, val listener: Listener) {
        var state = ServiceStatus.SESSION_WAITING
        var atr: String? = null
        var tracks: MsrCardData? = null
        var detectedAt = 0L
    }

    private val lock = Any()
    private var current: Session? = null
    private var lastId = 0

    fun start(owner: Int, types: Int, timeoutSec: Int, listener: Listener) {
        if (types and (CardTypes.IC or CardTypes.MSR) == 0 || types and (CardTypes.IC or CardTypes.MSR).inv() != 0) {
            throw CardReaderException(CardReaderError.INVALID_ARGUMENT, "types $types")
        }
        if (timeoutSec !in 1..CardReader.MAX_TIMEOUT_SEC) throw CardReaderException(CardReaderError.INVALID_ARGUMENT, "timeout $timeoutSec")
        val replaced: Session?
        val session: Session
        synchronized(lock) {
            val cur = current
            if (cur != null && cur.owner != owner && !abandoned(cur)) throw CardReaderException(CardReaderError.BUSY, "reader in use by another app")
            if (!hw.connected) {
                if (hw.portBusy) throw CardReaderException(CardReaderError.BUSY, "reader port in use by another process")
                throw CardReaderException(CardReaderError.NOT_READY, "reader not connected")
            }
            replaced = cur
            session = Session(++lastId, owner, listener)
            current = session
        }
        replaced?.let { release(it, CardReaderError.CANCELLED, "replaced") }
        try {
            hw.checkCard(types, timeoutSec, Events(session))
        } catch (e: Exception) {
            synchronized(lock) { if (current === session) current = null }
            throw CardReaderException(CardReaderError.READER_ERROR, e.javaClass.simpleName)
        }
        Log.i(TAG, "session ${session.id} waiting owner=$owner types=$types timeout=$timeoutSec")
    }

    fun readIc(owner: Int): IcCardData {
        val s = claim(owner, ServiceStatus.SESSION_CARD_IC)
        try {
            hw.cancelCheckCard()
            val emv = try {
                EmvReader({ apdu -> hw.transmitApdu(PayHardware.CARD_IC, apdu) }).read()
            } catch (e: Exception) {
                EmvResult(note = "EMV read failed: ${e.javaClass.simpleName}")
            }
            hw.cardOff(PayHardware.CARD_IC)
            Log.i(TAG, "session ${s.id} IC read fields=${emv.fields.size}")
            return IcCardData(
                atr = s.atr, aid = emv.aid, appLabel = emv.appLabel, pan = emv.pan, expiry = emv.expiry,
                holder = emv.holder, serviceCode = emv.serviceCode, fields = emv.fields, note = emv.note,
            )
        } finally {
            end(s)
        }
    }

    fun readMsr(owner: Int): MsrCardData {
        val s = claim(owner, ServiceStatus.SESSION_CARD_MSR)
        end(s)
        Log.i(TAG, "session ${s.id} MSR handed over")
        return s.tracks ?: MsrCardData(null, null, null)
    }

    /** Ends [owner]'s session; another process's session is left alone. */
    fun cancel(owner: Int) {
        val s = synchronized(lock) { current?.takeIf { it.owner == owner }?.also { current = null } } ?: return
        release(s, CardReaderError.CANCELLED, "cancelled")
    }

    /** The reader link went away (stock service died, port closed): the session cannot finish. */
    fun readerLost() {
        val s = synchronized(lock) { current.also { current = null } } ?: return
        Log.i(TAG, "session ${s.id} ended: reader lost")
        s.listener.onError(CardReaderError.CANCELLED, "reader disconnected")
    }

    val active: Boolean get() = synchronized(lock) { current != null }

    fun status(owner: Int): ServiceStatus {
        val s = synchronized(lock) { current }
        val connected = hw.connected
        // A slot query is one status command; skipped while a read holds the card.
        val ic = if (!connected || s?.state == ServiceStatus.SESSION_READING) ServiceStatus.CARD_UNKNOWN else when (hw.cardExistStatus(PayHardware.CARD_IC)) {
            ServiceStatus.CARD_PRESENT -> ServiceStatus.CARD_PRESENT
            ServiceStatus.CARD_ABSENT -> ServiceStatus.CARD_ABSENT
            else -> ServiceStatus.CARD_UNKNOWN
        }
        return ServiceStatus(
            backend = hw.kind,
            connected = connected,
            session = s?.state ?: ServiceStatus.SESSION_IDLE,
            busy = (s != null && s.owner != owner) || (!connected && hw.portBusy),
            icCard = ic,
        )
    }

    private fun claim(owner: Int, state: Int): Session = synchronized(lock) {
        val s = current
        if (s == null || s.owner != owner || s.state != state) {
            throw CardReaderException(CardReaderError.NO_CARD, "no detected card for this caller")
        }
        s.state = ServiceStatus.SESSION_READING
        s
    }

    private fun abandoned(s: Session): Boolean =
        (s.state == ServiceStatus.SESSION_CARD_IC || s.state == ServiceStatus.SESSION_CARD_MSR) && now() - s.detectedAt > DETECTED_TTL_MS

    private fun end(s: Session) {
        synchronized(lock) { if (current === s) current = null }
        s.listener.onEnded()
    }

    // Stops the reader work of a session that is no longer current and tells its owner.
    private fun release(s: Session, code: Int, why: String) {
        when (s.state) {
            ServiceStatus.SESSION_WAITING -> hw.cancelCheckCard()
            ServiceStatus.SESSION_CARD_IC -> hw.cardOff(PayHardware.CARD_IC)
        }
        s.tracks = null
        Log.i(TAG, "session ${s.id} ended: $why")
        s.listener.onError(code, why)
    }

    // Backend callbacks; only the first event of the current session counts.
    private inner class Events(private val s: Session) : CardEvents {
        private fun detected(state: Int, fill: Session.() -> Unit): Boolean = synchronized(lock) {
            if (current !== s || s.state != ServiceStatus.SESSION_WAITING) return false
            s.state = state
            s.detectedAt = now()
            s.fill()
            true
        }

        override fun onMagCard(track1: String?, track2: String?, track3: String?) {
            if (!detected(ServiceStatus.SESSION_CARD_MSR) { tracks = MsrCardData(track1, track2, track3) }) return
            Log.i(TAG, "session ${s.id} detected MSR")
            s.listener.onCardDetected(CardTypes.MSR)
        }

        override fun onIcCard(atr: String?) {
            if (!detected(ServiceStatus.SESSION_CARD_IC) { this.atr = atr }) return
            Log.i(TAG, "session ${s.id} detected IC")
            s.listener.onCardDetected(CardTypes.IC)
        }

        override fun onError(code: Int, message: String?) {
            val mine = synchronized(lock) { (current === s && s.state == ServiceStatus.SESSION_WAITING).also { if (it) current = null } }
            if (!mine) return
            // The stock service answers INVOKING_REPEAT when another app's card wait is running there.
            if (code == ERR_INVOKING_REPEAT) hw.cancelCheckCard()
            val mapped = when (code) {
                ERR_TIMEOUT -> CardReaderError.TIMEOUT
                ERR_INVOKING_REPEAT -> CardReaderError.BUSY
                PayHardware.ERR_UNSUPPORTED_CARD -> CardReaderError.UNSUPPORTED_CARD
                else -> CardReaderError.READER_ERROR
            }
            Log.i(TAG, "session ${s.id} ended: reader code $code")
            s.listener.onError(mapped, if (mapped == CardReaderError.READER_ERROR) "reader code $code" else message)
        }
    }

    companion object {
        const val DETECTED_TTL_MS = 30_000L
        private const val TAG = "ReaderSessions"
        private const val ERR_TIMEOUT = -30005
        private const val ERR_INVOKING_REPEAT = -20001
    }
}
