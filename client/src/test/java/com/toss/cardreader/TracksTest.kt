package com.toss.cardreader

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

// Synthetic fixtures use public test numbers, never data captured from a card.
class TracksTest {
    @Test
    fun parsesTrack1WithSentinels() {
        val t = Tracks.track1("%B4242424242424242^TEST/ONE                  ^4912101000000000?7")!!
        assertEquals("4242424242424242", t.pan)
        assertEquals("TEST/ONE", t.holder)
        assertEquals("4912", t.expiry)
        assertEquals("101", t.serviceCode)
    }

    @Test
    fun parsesTrack2WithEqualsOrDSeparator() {
        assertEquals("6011111111111117", Tracks.track2(";6011111111111117=49122010000000000000?")!!.pan)
        val d = Tracks.track2("6011111111111117D4912201")!!
        assertEquals("4912", d.expiry)
        assertEquals("201", d.serviceCode)
        assertNull(Tracks.track2("1234567890"))
    }
}
