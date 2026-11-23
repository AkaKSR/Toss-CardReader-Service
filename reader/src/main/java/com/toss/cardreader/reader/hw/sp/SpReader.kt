package com.toss.cardreader.reader.hw.sp

import android.os.SystemClock
import android.util.Log
import com.toss.cardreader.reader.hw.CardEvents
import java.util.concurrent.atomic.AtomicInteger
import kotlin.concurrent.thread

/**
 * Card reading straight over [SpLink], with the same operations the app uses from the stock service:
 * wait for an IC insert or an MSR swipe, exchange APDUs with an inserted card, power it off, report its presence.
 */
object SpReader {
    private const val TAG = "SpReader"
    private const val SLOT = "SCC0"
    private const val POLL_GAP_MS = 50L
    private const val EXCHANGE_CONTROL = 32
    private const val ATR_CAPACITY = 256
    private val MSR_TRACK_MAX = intArrayOf(79, 40, 107)

    const val ERR_TIMEOUT = -30005
    const val CARD_ABSENT = 1
    const val CARD_PRESENT = 2

    private val session = AtomicInteger(0)
    private var users = 0

    @Synchronized fun connect() {
        if (users == 0) SpLink.open()
        users++
        Log.i(TAG, "connect users=$users")
    }

    @Synchronized fun disconnect() {
        if (users == 0) return
        if (--users == 0) {
            session.incrementAndGet()
            SpLink.close()
        }
        Log.i(TAG, "disconnect users=$users")
    }

    fun checkCard(icWanted: Boolean, msrWanted: Boolean, timeoutSec: Int, events: CardEvents) {
        val id = session.incrementAndGet()
        Log.i(TAG, "wait start session=$id ic=$icWanted msr=$msrWanted")
        thread(name = "sp-check", isDaemon = true) {
            val deadline = SystemClock.uptimeMillis() + timeoutSec * 1000L
            try {
                while (session.get() == id) {
                    if (SystemClock.uptimeMillis() >= deadline) return@thread events.onError(ERR_TIMEOUT, "timeout")
                    if (icWanted && icPresent()) return@thread events.onIcCard(activate())
                    if (msrWanted) readStripe()?.let { (t1, t2, t3) -> return@thread events.onMagCard(t1, t2, t3) }
                    SystemClock.sleep(POLL_GAP_MS)
                }
            } catch (e: Exception) {
                if (session.get() == id) events.onError(-1, e.javaClass.simpleName)
            }
        }
    }

    fun cancelCheckCard() {
        Log.i(TAG, "wait cancel session=${session.incrementAndGet()}")
    }

    fun cardExistStatus(): Int = if (icPresent()) CARD_PRESENT else CARD_ABSENT

    fun transmitApdu(apdu: ByteArray): ByteArray {
        val r = SpLink.call(
            SpCommand.IC_EXCHANGE,
            listOf(SpProtocol.text(SLOT), SpProtocol.u32(EXCHANGE_CONTROL), SpProtocol.u32(1), SpProtocol.apduRecord(apdu)),
            16_700,
        )
        if (r.result < 0) throw SpProtocolException("exchange ${r.result}")
        return SpProtocol.apduResponse(r.data.firstOrNull() ?: throw SpProtocolException("no APDU reply"))
    }

    fun cardOff(): Int = SpLink.call(SpCommand.IC_POWER_OFF, listOf(SpProtocol.text(SLOT)), 700).result

    private fun icPresent(): Boolean =
        SpLink.call(SpCommand.IC_STATUS, listOf(SpProtocol.text(SLOT)), 4000).result >= 0

    private fun activate(): String {
        val r = SpLink.call(
            SpCommand.IC_ACTIVATE,
            listOf(SpProtocol.text(SLOT), SpProtocol.u32(0), SpProtocol.u32(ATR_CAPACITY)),
            12_000,
        )
        if (r.result < 0) throw SpProtocolException("activate ${r.result}")
        val buf = r.data.firstOrNull() ?: throw SpProtocolException("no ATR")
        val n = buf.firstOrNull()?.toInt()?.and(0xFF) ?: 0
        if (n == 0 || n + 1 > buf.size) throw SpProtocolException("ATR length")
        return buf.copyOfRange(1, n + 1).joinToString("") { "%02X".format(it.toInt() and 0xFF) }
    }

    private fun readStripe(): Triple<String?, String?, String?>? {
        val r = SpLink.call(SpCommand.MSR_READ, MSR_TRACK_MAX.map { SpProtocol.u32(it) }, 1500)
        if (r.result <= 0 || r.data.size < 6) return null
        fun track(i: Int): String? {
            val n = SpProtocol.readU32(r.data[i * 2]).coerceIn(0, r.data[i * 2 + 1].size)
            return if (n == 0) null else String(r.data[i * 2 + 1], 0, n, Charsets.ISO_8859_1)
        }
        return Triple(track(0), track(1), track(2))
    }
}
