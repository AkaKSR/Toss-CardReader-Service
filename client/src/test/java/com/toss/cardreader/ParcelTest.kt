package com.toss.cardreader

import android.os.Parcel
import android.os.Parcelable
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

// Values are synthetic (public test numbers), never captured from a card.
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class ParcelTest {
    private inline fun <reified T : Parcelable> roundTrip(value: T): T {
        val p = Parcel.obtain()
        try {
            p.writeParcelable(value, 0)
            p.setDataPosition(0)
            @Suppress("DEPRECATION")
            return p.readParcelable(T::class.java.classLoader)!!
        } finally {
            p.recycle()
        }
    }

    private val ic = IcCardData(
        atr = "3B6800000073C84013009000", aid = "A0000000031010", appLabel = "VISA", pan = "4242424242424242",
        expiry = "4912", holder = "TEST/ONE", serviceCode = "201",
        fields = listOf(EmvRecordField("5A", "PAN", "4242424242424242"), EmvRecordField("9F12", "App name", "VISA")),
        note = "GET PROCESSING OPTIONS refused",
    )

    @Test
    fun icCardDataKeepsEveryFieldAndOrder() {
        assertEquals(ic, roundTrip(ic))
    }

    @Test
    fun icCardDataWithOnlyAtrKeepsNulls() {
        val partial = IcCardData(atr = "3B00")
        assertEquals(partial, roundTrip(partial))
    }

    @Test
    fun msrCardDataKeepsEmptyTracksNull() {
        val msr = MsrCardData(null, "4242424242424242=4912101", null)
        assertEquals(msr, roundTrip(msr))
    }

    @Test
    fun serviceStatusRoundTrips() {
        val s = ServiceStatus(ServiceStatus.BACKEND_DIRECT, true, ServiceStatus.SESSION_CARD_IC, true, ServiceStatus.CARD_PRESENT)
        assertEquals(s, roundTrip(s))
    }

    @Test
    fun cardDataNeverPrintsCardValues() {
        val printed = listOf(ic.toString(), MsrCardData("%B4242424242424242^TEST/ONE^4912?", null, null).toString(),
            ic.fields.first().toString(), Tracks.track2("4242424242424242=4912101").toString())
        for (s in printed) {
            assertFalse(s, s.contains("4242"))
            assertFalse(s, s.contains("TEST/ONE"))
            assertFalse(s, s.contains("4912"))
        }
    }
}
