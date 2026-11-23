package com.toss.cardreader

import android.os.Parcelable
import kotlinx.parcelize.Parcelize

// Card data classes print only which fields are present, so a stray log line never carries card data.

/** One public EMV data element as the app shows it: tag in hex, its name, a display value. */
@Parcelize
data class EmvRecordField(val tag: String, val name: String, val value: String) : Parcelable {
    override fun toString() = "EmvRecordField(tag=$tag)"
}

/**
 * Result of readIc(): ATR and the public EMV records. Absent values are null. [note] explains a partial read.
 * Over the interface a failed read arrives with [error] (a CardReaderError code) and no data; the wrapper throws it.
 */
@Parcelize
data class IcCardData(
    val atr: String?,
    val aid: String? = null,
    val appLabel: String? = null,
    val pan: String? = null,
    val expiry: String? = null,
    val holder: String? = null,
    val serviceCode: String? = null,
    val fields: List<EmvRecordField> = emptyList(),
    val note: String? = null,
    val error: Int = 0,
) : Parcelable {
    override fun toString() = "IcCardData(atr=${atr != null}, pan=${pan != null}, fields=${fields.size}, note=${note != null}, error=$error)"
}

/** Result of readMsr(): raw tracks 1-3 of one swipe, null when a track was empty. [error] as for [IcCardData]. */
@Parcelize
data class MsrCardData(val track1: String?, val track2: String?, val track3: String?, val error: Int = 0) : Parcelable {
    override fun toString() = "MsrCardData(track1=${track1 != null}, track2=${track2 != null}, track3=${track3 != null}, error=$error)"
}

/**
 * getStatus(): [backend] which reader path the service uses, [connected] whether it is open now,
 * [session] the session state, [busy] true when another app owns that session or another process holds the
 * reader port,
 * [icCard] IC slot state (CARD_ABSENT, CARD_PRESENT, or CARD_UNKNOWN when the reader is not open).
 */
@Parcelize
data class ServiceStatus(
    val backend: Int,
    val connected: Boolean,
    val session: Int,
    val busy: Boolean,
    val icCard: Int,
) : Parcelable {
    companion object {
        const val BACKEND_NONE = 0
        const val BACKEND_SUNMI = 1
        const val BACKEND_DIRECT = 2

        const val SESSION_IDLE = 0
        const val SESSION_WAITING = 1
        const val SESSION_CARD_IC = 2
        const val SESSION_CARD_MSR = 3
        const val SESSION_READING = 4

        const val CARD_UNKNOWN = -1
        const val CARD_ABSENT = 1
        const val CARD_PRESENT = 2
    }
}
