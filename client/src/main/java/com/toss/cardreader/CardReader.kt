package com.toss.cardreader

/** Constants of interface version 1, shared by the service and its clients. */
object CardReader {
    const val INTERFACE_VERSION = 1
    const val SERVICE_PACKAGE = "com.toss.cardreader.service"
    const val ACTION_BIND = "com.toss.cardreader.action.BIND"
    const val MAX_TIMEOUT_SEC = 120
}

/** Bits for waitForCard(types) and the type passed to onCardDetected. Same values as the stock Sunmi service. */
object CardTypes {
    const val MSR = 1
    const val IC = 2
}

/** ServiceSpecificException codes (and onError codes) of interface version 1. */
object CardReaderError {
    /**
     * The reader is taken: another app holds the session, another process (e.g. a card app with its own direct
     * link) has /dev/ttyS3 open, or the stock payment service is serving another app's card wait.
     */
    const val BUSY = 1
    /** No reader link: the payment service is missing and /dev/ttyS3 cannot be opened, or the service is gone. */
    const val NOT_READY = 2
    /** readIc/readMsr without a detected card of that kind in the caller's session. */
    const val NO_CARD = 3
    const val TIMEOUT = 4
    /** The session was cancelled, replaced, or its reader link went away. */
    const val CANCELLED = 5
    const val INVALID_ARGUMENT = 6
    /** Contactless or another card kind this interface does not read. */
    const val UNSUPPORTED_CARD = 7
    /** The reader reported an error; the message carries its code. */
    const val READER_ERROR = 8
    /** The calling app is not signed with a key the service allows. */
    const val NOT_ALLOWED = 9

    fun name(code: Int): String = when (code) {
        BUSY -> "BUSY"
        NOT_READY -> "NOT_READY"
        NO_CARD -> "NO_CARD"
        TIMEOUT -> "TIMEOUT"
        CANCELLED -> "CANCELLED"
        INVALID_ARGUMENT -> "INVALID_ARGUMENT"
        UNSUPPORTED_CARD -> "UNSUPPORTED_CARD"
        READER_ERROR -> "READER_ERROR"
        NOT_ALLOWED -> "NOT_ALLOWED"
        else -> "ERROR_$code"
    }
}

class CardReaderException(val code: Int, message: String?) : Exception("${CardReaderError.name(code)}: ${message ?: "-"}")
