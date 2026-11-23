package com.toss.cardreader.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Binder
import android.os.IBinder
import android.util.Log
import com.toss.cardreader.CardReader
import com.toss.cardreader.CardReaderError
import com.toss.cardreader.CardReaderException
import com.toss.cardreader.ICardReaderCallback
import com.toss.cardreader.ICardReaderService
import com.toss.cardreader.IcCardData
import com.toss.cardreader.MsrCardData
import com.toss.cardreader.ServiceStatus
import com.toss.cardreader.reader.ReaderSessions
import com.toss.cardreader.reader.hw.PayHardware

/**
 * Headless owner of the card reader. Clients bind with [CardReader.ACTION_BIND]; the reader link (stock payment
 * service, else /dev/ttyS3) is opened on the first allowed call that needs it and closed when the last client
 * unbinds. Started on boot as a foreground service so it is ready for clients.
 */
class ReaderService : Service(), PayHardware.ConnectionListener {
    private lateinit var hw: PayHardware
    private lateinit var sessions: ReaderSessions
    private lateinit var callers: CallerCheck
    private val readerLock = Object()

    override fun onCreate() {
        super.onCreate()
        startForeground(NOTIFICATION_ID, notification())
        hw = PayHardware(this).also { it.listener = this }
        sessions = ReaderSessions(hw)
        callers = CallerCheck.of(this)
        Log.i(TAG, "created")
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int = START_STICKY

    override fun onBind(intent: Intent): IBinder? = if (intent.action == CardReader.ACTION_BIND) binder else null

    // Last client gone: end any session and let go of the reader.
    override fun onUnbind(intent: Intent): Boolean {
        sessions.readerLost()
        hw.unbind()
        Log.i(TAG, "no clients: reader closed")
        // true asks Android for onRebind, which re-arms onUnbind for the next time the last client leaves.
        // With false, onUnbind runs only once per process and the reader stays open afterwards.
        return true
    }

    // A client came back after the last one left; the reader opens again on its first call (ensureReader).
    override fun onRebind(intent: Intent) {}

    override fun onDestroy() {
        sessions.readerLost()
        hw.unbind()
        super.onDestroy()
    }

    private fun openReader() {
        if (!hw.ensureOpen()) Log.w(TAG, "no reader: payment service missing and port unavailable")
    }

    override fun onConnected() {
        Log.i(TAG, "reader connected backend=${hw.kind}")
        synchronized(readerLock) { readerLock.notifyAll() }
    }

    override fun onDisconnected() {
        Log.i(TAG, "reader disconnected")
        sessions.readerLost()
    }

    // Opens the link if needed and waits briefly for it; the stock service and the port both connect asynchronously.
    private fun ensureReader() {
        if (hw.connected) return
        openReader()
        awaitReader()
    }

    private fun awaitReader() {
        val deadline = System.currentTimeMillis() + CONNECT_WAIT_MS
        synchronized(readerLock) {
            while (!hw.connected) {
                val left = deadline - System.currentTimeMillis()
                if (left <= 0) return
                readerLock.wait(left)
            }
        }
    }

    private val binder = object : ICardReaderService.Stub() {
        override fun getVersion(): Int = allowed { CardReader.INTERFACE_VERSION }

        override fun getStatus(): ServiceStatus = allowed {
            ensureReader()
            sessions.status(Binder.getCallingPid())
        }

        override fun waitForCard(types: Int, timeoutSec: Int, callback: ICardReaderCallback?): Int = allowed {
            if (callback == null) return@allowed CardReaderError.INVALID_ARGUMENT
            val owner = Binder.getCallingPid()
            ensureReader()
            try {
                sessions.start(owner, types, timeoutSec, Forward(owner, callback))
                0
            } catch (e: CardReaderException) {
                e.code
            }
        }

        override fun readIc(): IcCardData = allowed {
            try {
                sessions.readIc(Binder.getCallingPid())
            } catch (e: CardReaderException) {
                IcCardData(atr = null, error = e.code)
            }
        }

        override fun readMsr(): MsrCardData = allowed {
            try {
                sessions.readMsr(Binder.getCallingPid())
            } catch (e: CardReaderException) {
                MsrCardData(null, null, null, error = e.code)
            }
        }

        override fun cancel() = allowed { sessions.cancel(Binder.getCallingPid()) }

        private inline fun <T> allowed(block: () -> T): T {
            if (!callers.allowed(Binder.getCallingUid())) {
                Log.w(TAG, "refused uid=${Binder.getCallingUid()}: signer not allowed")
                throw SecurityException("caller is not signed with an allowed key")
            }
            return block()
        }
    }

    // Delivers session events to the client; a client that dies ends its own session.
    private inner class Forward(private val owner: Int, private val callback: ICardReaderCallback) :
        ReaderSessions.Listener, IBinder.DeathRecipient {
        init {
            callback.asBinder().linkToDeath(this, 0)
        }

        override fun binderDied() {
            Log.i(TAG, "client died owner=$owner")
            sessions.cancel(owner)
        }

        override fun onCardDetected(type: Int) = send { callback.onCardDetected(type) }

        override fun onError(code: Int, message: String?) {
            send { callback.onError(code, message) }
            onEnded()
        }

        override fun onEnded() {
            callback.asBinder().unlinkToDeath(this, 0)
        }

        private fun send(block: () -> Unit) {
            try {
                block()
            } catch (e: Exception) {
                Log.w(TAG, "callback failed owner=$owner: ${e.javaClass.simpleName}")
            }
        }
    }

    private fun notification(): Notification {
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(NotificationChannel(CHANNEL, "카드 리더 서비스", NotificationManager.IMPORTANCE_MIN))
        return Notification.Builder(this, CHANNEL)
            .setSmallIcon(R.drawable.ic_service)
            .setContentTitle("카드 리더 서비스 실행 중")
            .setContentText("앱이 요청할 때만 카드 리더를 사용해요")
            .setOngoing(true)
            .build()
    }

    companion object {
        private const val TAG = "ReaderService"
        private const val CHANNEL = "reader_service"
        private const val NOTIFICATION_ID = 1
        private const val CONNECT_WAIT_MS = 3000L

        fun start(context: Context) {
            context.startForegroundService(Intent(context, ReaderService::class.java))
        }
    }
}

class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action == Intent.ACTION_BOOT_COMPLETED || intent.action == Intent.ACTION_MY_PACKAGE_REPLACED) {
            ReaderService.start(context)
        }
    }
}
