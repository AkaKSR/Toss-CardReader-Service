package com.toss.cardreader.reader

import android.content.Context
import android.os.Process
import com.toss.cardreader.CardReaderApi
import com.toss.cardreader.IcCardData
import com.toss.cardreader.MsrCardData
import com.toss.cardreader.ServiceStatus
import com.toss.cardreader.reader.hw.PayHardware

/**
 * [CardReaderApi] inside the calling app, without the reader service: the stock Sunmi service when installed,
 * else the app's own direct link. Same session rules and results as the service.
 */
class LocalCardReader(context: Context) : CardReaderApi, PayHardware.ConnectionListener {
    private val hw = PayHardware(context.applicationContext)
    private val sessions = ReaderSessions(hw)
    private val owner = Process.myPid()
    @Volatile private var listener: CardReaderApi.ConnectionListener? = null

    override val connected: Boolean get() = hw.connected

    override fun connect(listener: CardReaderApi.ConnectionListener): Boolean {
        this.listener = listener
        hw.listener = this
        return hw.ensureOpen()
    }

    override fun disconnect() {
        listener = null
        sessions.readerLost()
        hw.unbind()
    }

    override fun onConnected() {
        listener?.onConnected()
    }

    override fun onDisconnected() {
        sessions.readerLost()
        listener?.onDisconnected()
    }

    override fun status(): ServiceStatus = sessions.status(owner)

    override fun waitForCard(types: Int, timeoutSec: Int, listener: CardReaderApi.CardListener) {
        if (!hw.connected) hw.ensureOpen()
        sessions.start(owner, types, timeoutSec, object : ReaderSessions.Listener {
            override fun onCardDetected(type: Int) = listener.onCardDetected(type)
            override fun onError(code: Int, message: String?) = listener.onError(code, message)
        })
    }

    override fun readIc(): IcCardData = sessions.readIc(owner)

    override fun readMsr(): MsrCardData = sessions.readMsr(owner)

    override fun cancel() = sessions.cancel(owner)
}
