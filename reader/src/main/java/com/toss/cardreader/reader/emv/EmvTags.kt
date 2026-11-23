package com.toss.cardreader.reader.emv

import java.security.SecureRandom
import java.time.LocalDate
import java.time.format.DateTimeFormatter

object EmvTags {
    private val names = mapOf(
        0x4F to "AID",
        0x50 to "Application label",
        0x57 to "Track 2 equivalent",
        0x5A to "PAN",
        0x5F20 to "Cardholder name",
        0x5F24 to "Expiry date",
        0x5F25 to "Effective date",
        0x5F28 to "Issuer country",
        0x5F2D to "Language preference",
        0x5F30 to "Service code",
        0x5F34 to "PAN sequence number",
        0x82 to "Application interchange profile",
        0x84 to "DF name",
        0x87 to "Application priority",
        0x88 to "Directory SFI",
        0x8C to "CDOL1",
        0x8D to "CDOL2",
        0x8E to "CVM list",
        0x8F to "CA public key index",
        0x90 to "Issuer public key certificate",
        0x92 to "Issuer public key remainder",
        0x94 to "Application file locator",
        0x9F07 to "Application usage control",
        0x9F08 to "Application version",
        0x9F0B to "Cardholder name (extended)",
        0x9F0D to "Issuer action code - default",
        0x9F0E to "Issuer action code - denial",
        0x9F0F to "Issuer action code - online",
        0x9F11 to "Issuer code table index",
        0x9F12 to "Application preferred name",
        0x9F1F to "Track 1 discretionary data",
        0x9F32 to "Issuer public key exponent",
        0x9F38 to "PDOL",
        0x9F42 to "Application currency",
        0x9F44 to "Currency exponent",
        0x9F46 to "ICC public key certificate",
        0x9F47 to "ICC public key exponent",
        0x9F48 to "ICC public key remainder",
        0x9F49 to "DDOL",
        0x9F4A to "SDA tag list",
        0x9F6B to "Track 2 data",
        0xBF0C to "FCI discretionary data",
    )
    private val textTags = setOf(0x50, 0x5F20, 0x5F2D, 0x9F0B, 0x9F12)

    fun name(tag: Int): String = names[tag] ?: "Tag"

    fun format(t: Tlv): String = when (t.tag) {
        in textTags -> t.value.ascii().trim()
        0x5A, 0x57 -> t.value.toHex().trimEnd('F')
        else -> t.value.toHex()
    }
}

// Values the terminal supplies when a card's PDOL asks for them. Amount is zero: this app never pays.
object TerminalData {
    private val random = SecureRandom()

    fun valueFor(tag: Int): ByteArray? = when (tag) {
        0x9F02, 0x9F03 -> ByteArray(6)
        0x9F1A, 0x5F2A -> "0410".hexToBytes()
        0x95 -> ByteArray(5)
        0x9A -> LocalDate.now().format(DateTimeFormatter.ofPattern("yyMMdd")).hexToBytes()
        0x9C -> byteArrayOf(0)
        0x9F37 -> ByteArray(4).also(random::nextBytes)
        0x9F35 -> byteArrayOf(0x22)
        0x9F33 -> "E0F8C8".hexToBytes()
        0x9F40 -> "6000F0A001".hexToBytes()
        0x9F66 -> "36004000".hexToBytes()
        else -> null
    }
}
