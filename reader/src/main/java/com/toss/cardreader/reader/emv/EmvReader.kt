package com.toss.cardreader.reader.emv

import com.toss.cardreader.EmvRecordField
import com.toss.cardreader.Tracks
import java.io.ByteArrayOutputStream

fun interface Transceiver {
    /** Sends one command APDU and returns the response data followed by SW1 SW2. */
    fun transmit(apdu: ByteArray): ByteArray
}

data class EmvResult(
    val aid: String? = null,
    val appLabel: String? = null,
    val pan: String? = null,
    val expiry: String? = null,
    val holder: String? = null,
    val serviceCode: String? = null,
    val fields: List<EmvRecordField> = emptyList(),
    val note: String? = null,
)

/**
 * Reads the public EMV records of a contact card: application selection (PSE, then PPSE, then
 * known RIDs by partial name), GET PROCESSING OPTIONS and READ RECORD over the AFL.
 * It never sends VERIFY (PIN), GENERATE AC, INTERNAL AUTHENTICATE or any key command.
 */
class EmvReader(
    private val card: Transceiver,
    private val terminalData: (Int) -> ByteArray? = TerminalData::valueFor,
) {
    private class Rapdu(val data: ByteArray, val sw: Int) {
        val ok get() = sw == 0x9000
    }

    private data class Candidate(val aid: ByteArray, val label: String?, val priority: Int)

    fun read(): EmvResult {
        val candidates = (directory(PSE) + directory(PPSE)).sortedBy { it.priority }
        val tried = ArrayList<String>()
        var fci: List<Tlv>? = null
        var selected: ByteArray? = null
        for (aid in candidates.map { it.aid } + KNOWN_RIDS.map { it.hexToBytes() }) {
            if (aid.toHex() in tried) continue
            tried += aid.toHex()
            val r = select(aid)
            if (r.ok) {
                fci = Tlv.parse(r.data)
                selected = fci.firstNotNullOfOrNull { it.find(0x84) }?.value ?: aid
                break
            }
        }
        if (fci == null || selected == null) return EmvResult(note = "No EMV application answered SELECT")

        val pdol = fci.firstNotNullOfOrNull { it.find(0x9F38) }?.value
        val records = ArrayList<Tlv>()
        var note: String? = null
        val afl = gpo(pdol)
        if (afl == null) {
            note = "GET PROCESSING OPTIONS refused; records read by SFI scan"
            records += scanRecords()
        } else {
            records += readAfl(afl)
        }

        val all = Tlv.flatten(fci) + Tlv.flatten(records)
        fun first(tag: Int) = all.firstOrNull { it.tag == tag }?.value
        val track2Equiv = first(0x57)?.toHex()?.let { Tracks.track2(it.trimEnd('F')) }
        return EmvResult(
            aid = selected.toHex(),
            appLabel = first(0x9F12)?.ascii() ?: first(0x50)?.ascii(),
            pan = first(0x5A)?.toHex()?.trimEnd('F') ?: track2Equiv?.pan,
            expiry = first(0x5F24)?.toHex()?.take(4) ?: track2Equiv?.expiry,
            holder = (first(0x9F0B) ?: first(0x5F20))?.ascii()?.trim()?.ifEmpty { null },
            serviceCode = first(0x5F30)?.toHex()?.takeLast(3) ?: track2Equiv?.serviceCode,
            fields = all.distinctBy { it.tag }.map { EmvRecordField(it.tagHex, EmvTags.name(it.tag), EmvTags.format(it)) },
            note = note,
        )
    }

    private fun directory(name: String): List<Candidate> {
        val r = select(name.toByteArray(Charsets.US_ASCII))
        if (!r.ok) return emptyList()
        val fci = Tlv.parse(r.data)
        val entries = ArrayList<Tlv>()
        fci.firstNotNullOfOrNull { it.find(0xBF0C) }?.let { entries += it.children.filter { c -> c.tag == 0x61 } }
        val sfi = fci.firstNotNullOfOrNull { it.find(0x88) }?.value?.firstOrNull()?.toInt()
        if (sfi != null) {
            for (rec in 1..16) {
                val rr = readRecord(sfi, rec)
                if (!rr.ok) break
                Tlv.parse(rr.data).forEach { t -> entries += t.children.filter { it.tag == 0x61 } }
            }
        }
        return entries.mapNotNull { e ->
            val aid = e.find(0x4F)?.value ?: return@mapNotNull null
            Candidate(aid, e.find(0x50)?.value?.ascii(), e.find(0x87)?.value?.firstOrNull()?.toInt()?.and(0x0F) ?: 15)
        }
    }

    private fun gpo(pdol: ByteArray?): List<IntArray>? {
        val dolData = ByteArrayOutputStream()
        if (pdol != null) {
            for ((tag, len) in parseDol(pdol)) {
                val v = terminalData(tag) ?: ByteArray(len)
                dolData.write(if (v.size >= len) v.copyOf(len) else ByteArray(len - v.size) + v)
            }
        }
        val body = byteArrayOf(0x83.toByte(), dolData.size().toByte()) + dolData.toByteArray()
        val r = send(byteArrayOf(0x80.toByte(), 0xA8.toByte(), 0, 0, body.size.toByte()) + body + byteArrayOf(0))
        if (!r.ok) return null
        val top = Tlv.parse(r.data).firstOrNull() ?: return null
        val aflBytes = when (top.tag) {
            0x80 -> top.value.copyOfRange(2, top.value.size)
            0x77 -> top.find(0x94)?.value ?: ByteArray(0)
            else -> return null
        }
        return aflBytes.toList().chunked(4).filter { it.size == 4 }.map { e ->
            intArrayOf((e[0].toInt() and 0xFF) ushr 3, e[1].toInt() and 0xFF, e[2].toInt() and 0xFF)
        }
    }

    private fun readAfl(afl: List<IntArray>): List<Tlv> {
        val out = ArrayList<Tlv>()
        for ((sfi, firstRec, lastRec) in afl.map { Triple(it[0], it[1], it[2]) }) {
            for (rec in firstRec..lastRec) {
                val r = readRecord(sfi, rec)
                if (r.ok) out += Tlv.parse(r.data)
            }
        }
        return out
    }

    private fun scanRecords(): List<Tlv> {
        val out = ArrayList<Tlv>()
        for (sfi in 1..10) {
            for (rec in 1..16) {
                val r = readRecord(sfi, rec)
                if (!r.ok) break
                out += Tlv.parse(r.data)
            }
        }
        return out
    }

    private fun select(name: ByteArray): Rapdu =
        send(byteArrayOf(0x00, 0xA4.toByte(), 0x04, 0x00, name.size.toByte()) + name + byteArrayOf(0))

    private fun readRecord(sfi: Int, rec: Int): Rapdu =
        send(byteArrayOf(0x00, 0xB2.toByte(), rec.toByte(), ((sfi shl 3) or 0x04).toByte(), 0))

    // T=0 cards answer 61xx (more data: GET RESPONSE) or 6Cxx (wrong Le: resend with Le=xx).
    private fun send(cmd: ByteArray): Rapdu {
        var r = split(card.transmit(cmd))
        if (r.sw ushr 8 == 0x6C) {
            r = split(card.transmit(cmd.copyOf(cmd.size - 1) + byteArrayOf((r.sw and 0xFF).toByte())))
        }
        val acc = ByteArrayOutputStream().apply { write(r.data) }
        var guard = 0
        while (r.sw ushr 8 == 0x61 && guard++ < 32) {
            r = split(card.transmit(byteArrayOf(0x00, 0xC0.toByte(), 0x00, 0x00, (r.sw and 0xFF).toByte())))
            acc.write(r.data)
        }
        return Rapdu(acc.toByteArray(), r.sw)
    }

    private fun split(resp: ByteArray): Rapdu {
        if (resp.size < 2) return Rapdu(ByteArray(0), 0x6F00)
        val sw = ((resp[resp.size - 2].toInt() and 0xFF) shl 8) or (resp[resp.size - 1].toInt() and 0xFF)
        return Rapdu(resp.copyOf(resp.size - 2), sw)
    }

    companion object {
        const val PSE = "1PAY.SYS.DDF01"
        const val PPSE = "2PAY.SYS.DDF01"
        val KNOWN_RIDS = listOf(
            "A000000003", // Visa
            "A000000004", // Mastercard
            "A000000025", // American Express
            "A000000065", // JCB
            "A000000333", // UnionPay
            "A000000152", // Discover / Diners
            "D410000001", // Korean domestic (KFTC registered RID)
        )
    }
}

internal fun ByteArray.ascii(): String = String(this, Charsets.ISO_8859_1).filter { it.code in 0x20..0x7E }
