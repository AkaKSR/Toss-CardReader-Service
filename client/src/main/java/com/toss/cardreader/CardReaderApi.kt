package com.toss.cardreader

/**
 * Card reader operations of interface version 1. [CardReaderClient] implements them over the reader service;
 * apps that carry the driver themselves can implement them locally with the same results. Calls block, so make
 * them off the main thread; refusals and failures are [CardReaderException]s.
 */
interface CardReaderApi {

    interface ConnectionListener {
        fun onConnected()
        fun onDisconnected()
    }

    /** Called on a binder or reader thread. */
    interface CardListener {
        fun onCardDetected(type: Int)
        fun onError(code: Int, message: String?)
    }

    val connected: Boolean

    /** Starts connecting; [ConnectionListener.onConnected] follows. False when there is nothing to connect to. */
    fun connect(listener: ConnectionListener): Boolean

    fun disconnect()

    fun status(): ServiceStatus

    fun waitForCard(types: Int, timeoutSec: Int, listener: CardListener)

    fun readIc(): IcCardData

    fun readMsr(): MsrCardData

    fun cancel()
}
