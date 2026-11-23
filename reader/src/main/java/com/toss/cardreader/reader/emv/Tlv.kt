package com.toss.cardreader.reader.emv

data class Tlv(val tag: Int, val value: ByteArray, val children: List<Tlv>) {
    val tagHex: String get() = tag.toString(16).uppercase().let { if (it.length % 2 == 1) "0$it" else it }
    val isConstructed: Boolean get() = Tlv.isConstructed(tag)

    fun find(tag: Int): Tlv? {
        if (this.tag == tag) return this
        for (c in children) c.find(tag)?.let { return it }
        return null
    }

    override fun equals(other: Any?): Boolean =
        other is Tlv && tag == other.tag && value.contentEquals(other.value) && children == other.children

    override fun hashCode(): Int = 31 * (31 * tag + value.contentHashCode()) + children.hashCode()

    companion object {
        // BER: bit 6 (0x20) of the first tag byte marks a constructed tag.
        fun isConstructed(tag: Int): Boolean {
            var first = tag
            while (first > 0xFF) first = first ushr 8
            return first and 0x20 != 0
        }

        fun parse(data: ByteArray, from: Int = 0, to: Int = data.size): List<Tlv> {
            val out = ArrayList<Tlv>()
            var i = from
            while (i < to) {
                val b = data[i].toInt() and 0xFF
                if (b == 0x00 || b == 0xFF) { i++; continue }
                var tag = b
                i++
                if (b and 0x1F == 0x1F) {
                    do {
                        require(i < to) { "truncated tag" }
                        val nb = data[i++].toInt() and 0xFF
                        tag = (tag shl 8) or nb
                    } while (nb and 0x80 != 0)
                }
                require(i < to) { "missing length for tag ${tag.toString(16)}" }
                var len = data[i++].toInt() and 0xFF
                if (len and 0x80 != 0) {
                    val n = len and 0x7F
                    require(n in 1..3 && i + n <= to) { "bad length for tag ${tag.toString(16)}" }
                    len = 0
                    repeat(n) { len = (len shl 8) or (data[i++].toInt() and 0xFF) }
                }
                require(i + len <= to) { "value overruns buffer for tag ${tag.toString(16)}" }
                val value = data.copyOfRange(i, i + len)
                val children = if (isConstructed(tag)) parse(data, i, i + len) else emptyList()
                out += Tlv(tag, value, children)
                i += len
            }
            return out
        }

        fun flatten(nodes: List<Tlv>): List<Tlv> =
            nodes.flatMap { if (it.isConstructed) flatten(it.children) else listOf(it) }
    }
}

// A DOL (e.g. PDOL 9F38) is a list of tag + length pairs with no values.
fun parseDol(dol: ByteArray): List<Pair<Int, Int>> {
    val out = ArrayList<Pair<Int, Int>>()
    var i = 0
    while (i < dol.size) {
        var tag = dol[i++].toInt() and 0xFF
        if (tag and 0x1F == 0x1F) {
            do {
                require(i < dol.size) { "truncated DOL tag" }
                val nb = dol[i++].toInt() and 0xFF
                tag = (tag shl 8) or nb
            } while (nb and 0x80 != 0)
        }
        require(i < dol.size) { "truncated DOL length" }
        out += tag to (dol[i++].toInt() and 0xFF)
    }
    return out
}

fun ByteArray.toHex(): String = joinToString("") { "%02X".format(it.toInt() and 0xFF) }

fun String.hexToBytes(): ByteArray {
    val s = filterNot { it.isWhitespace() }
    require(s.length % 2 == 0) { "odd hex length" }
    return ByteArray(s.length / 2) { s.substring(it * 2, it * 2 + 2).toInt(16).toByte() }
}
