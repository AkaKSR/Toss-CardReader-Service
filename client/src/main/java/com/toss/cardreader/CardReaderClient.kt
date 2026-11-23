package com.toss.cardreader

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.content.pm.PackageManager
import android.os.IBinder
import android.os.RemoteException

/**
 * [CardReaderApi] over the Toss Card Reader Service. [connect] binds it; calls throw [CardReaderException]
 * (BUSY, NOT_READY, NOT_ALLOWED, ...) for error codes and binder failures alike.
 */
class CardReaderClient(context: Context) : CardReaderApi {

    private val context = context.applicationContext
    @Volatile private var service: ICardReaderService? = null
    @Volatile private var listener: CardReaderApi.ConnectionListener? = null
    private var bound = false

    override val connected: Boolean get() = service != null

    private val connection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName, binder: IBinder) {
            service = ICardReaderService.Stub.asInterface(binder)
            listener?.onConnected()
        }

        override fun onServiceDisconnected(name: ComponentName) {
            service = null
            listener?.onDisconnected()
        }
    }

    /** Binds the reader service. False when it is not installed. */
    override fun connect(listener: CardReaderApi.ConnectionListener): Boolean {
        this.listener = listener
        if (bound) return true
        val ok = context.bindService(Intent(CardReader.ACTION_BIND).setPackage(CardReader.SERVICE_PACKAGE), connection, Context.BIND_AUTO_CREATE)
        // A failed bind may still have registered the connection; Android then wants it unbound. When nothing was
        // registered unbindService throws IllegalArgumentException, which means there is nothing to release.
        if (!ok) runCatching { context.unbindService(connection) }
        bound = ok
        return ok
    }

    override fun disconnect() {
        if (bound) context.unbindService(connection)
        bound = false
        service = null
        listener = null
    }

    fun version(): Int = call { it.version }

    override fun status(): ServiceStatus = call { it.status }

    override fun waitForCard(types: Int, timeoutSec: Int, listener: CardReaderApi.CardListener) {
        val code = call {
            it.waitForCard(types, timeoutSec, object : ICardReaderCallback.Stub() {
                override fun onCardDetected(type: Int) = listener.onCardDetected(type)
                override fun onError(code: Int, message: String?) = listener.onError(code, message)
            })
        }
        if (code != 0) throw CardReaderException(code, "waitForCard")
    }

    override fun readIc(): IcCardData = call { it.readIc() }.also { if (it.error != 0) throw CardReaderException(it.error, "readIc") }

    override fun readMsr(): MsrCardData = call { it.readMsr() }.also { if (it.error != 0) throw CardReaderException(it.error, "readMsr") }

    override fun cancel() = call { it.cancel() }

    private inline fun <T> call(block: (ICardReaderService) -> T): T {
        val s = service ?: throw CardReaderException(CardReaderError.NOT_READY, "service not connected")
        return try {
            block(s)
        } catch (e: SecurityException) {
            throw CardReaderException(CardReaderError.NOT_ALLOWED, e.message)
        } catch (e: RemoteException) {
            throw CardReaderException(CardReaderError.NOT_READY, e.javaClass.simpleName)
        }
    }

    companion object {
        /** True when the reader service is installed (and visible: the library's manifest declares the query). */
        fun installed(context: Context): Boolean = try {
            context.packageManager.getPackageInfo(CardReader.SERVICE_PACKAGE, 0)
            true
        } catch (e: PackageManager.NameNotFoundException) {
            false
        }
    }
}
