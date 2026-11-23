package com.toss.cardreader.reader.emv

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

// Scripted responses below are synthetic and contain public test numbers only.
class TlvTest {
    @Test
    fun parsesNestedFciWithMultiByteTagsAndLongLength() {
        val cert = "AB".repeat(130)
        val data = ("6F" + "1A" + "8407A0000000031010" + "A50F" + "500456495341" + "9F38069F02069F3704" +
            "90" + "8182" + cert).hexToBytes()
        val nodes = Tlv.parse(data)
        assertEquals(listOf(0x6F, 0x90), nodes.map { it.tag })
        assertEquals("A0000000031010", nodes[0].find(0x84)!!.value.toHex())
        assertEquals("VISA", nodes[0].find(0x50)!!.value.ascii())
        assertEquals(listOf(0x9F02 to 6, 0x9F37 to 4), parseDol(nodes[0].find(0x9F38)!!.value))
        assertEquals(130, nodes[1].value.size)
    }

    @Test
    fun rejectsTruncatedValue() {
        assertThrows(IllegalArgumentException::class.java) { Tlv.parse("5A08411111".hexToBytes()) }
    }
}

class EmvReaderTest {
    private class ScriptedCard(private val script: List<Pair<String, String>>) : Transceiver {
        val sent = ArrayList<String>()
        override fun transmit(apdu: ByteArray): ByteArray {
            val hex = apdu.toHex()
            sent += hex
            val reply = script.firstOrNull { hex.startsWith(it.first) }?.second ?: "6A82"
            return reply.hexToBytes()
        }
    }

    private val fci = "6F" + "1D" + "8407A0000000031010" + "A512" + "500456495341" + "9F38069F02069F3704" + "870101"
    private val record = "70" + "20" + "5A084242424242424242" + "5F2403491231" + "5F2008544553542F4F4E45" + "5F30020101"

    @Test
    fun readsPanExpiryNameAidAndLabelThroughGpoAndAfl() {
        val card = ScriptedCard(listOf(
            "00A404000E31504159" to "6A82",
            "00A404000E32504159" to "6A82",
            "00A4040005A000000003" to fci + "9000",
            "80A80000" to "6114",
            "00C0000014" to "800E" + "1C00" + "08010100" + "10010200" + "18010100" + "9000",
            "00B2010C" to record + "9000",
            "00B20114" to "70055F28020410" + "9000",
            "00B20214" to "6A83",
            "00B2011C" to "6A83",
        ))
        val r = EmvReader(card) { tag -> if (tag == 0x9F37) "01020304".hexToBytes() else null }.read()

        assertEquals("4242424242424242", r.pan)
        assertEquals("4912", r.expiry)
        assertEquals("TEST/ONE", r.holder)
        assertEquals("A0000000031010", r.aid)
        assertEquals("VISA", r.appLabel)
        assertEquals("101", r.serviceCode)
        assertNull(r.note)
        val gpo = card.sent.first { it.startsWith("80A8") }
        assertEquals("80A80000" + "0C" + "830A" + "000000000000" + "01020304" + "00", gpo)
        assertTrue(r.fields.any { it.tag == "5F28" && it.value == "0410" })
        assertTrue(card.sent.none { it.startsWith("0020") || it.startsWith("80AE") || it.startsWith("0088") })
    }

    @Test
    fun fallsBackToTrack2EquivalentAndSfiScanWhenGpoIsRefused() {
        val card = ScriptedCard(listOf(
            "00A4040005A000000003" to "6F098407A00000000310109000",
            "80A80000" to "6985",
            "00B2010C" to "70135711" + "4000056655665556D4812201000000000F" + "9000",
            "00B2020C" to "6A83",
        ))
        val r = EmvReader(card) { null }.read()
        assertEquals("4000056655665556", r.pan)
        assertEquals("4812", r.expiry)
        assertNotNull(r.note)
    }

    @Test
    fun reportsWhenNoApplicationAnswers() {
        val r = EmvReader(ScriptedCard(emptyList())) { null }.read()
        assertNull(r.pan)
        assertEquals("No EMV application answered SELECT", r.note)
    }
}
