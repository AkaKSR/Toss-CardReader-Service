package com.toss.cardreader

data class TrackInfo(
    val pan: String?,
    val holder: String?,
    val expiry: String?,
    val serviceCode: String?,
) {
    override fun toString() = "TrackInfo(pan=${pan != null}, holder=${holder != null})"
}

// ISO 7813: track 1 is "%B<PAN>^<NAME>^<YYMM><SVC>...?<LRC>", track 2 is ";<PAN>=<YYMM><SVC>...?<LRC>".
// The reader may or may not pass the sentinels through, so both forms are accepted.
object Tracks {
    fun track1(raw: String?): TrackInfo? {
        if (raw.isNullOrBlank()) return null
        var t = raw.trim().removePrefix("%")
        t = t.substringBefore('?')
        if (t.firstOrNull()?.isLetter() == true) t = t.substring(1)
        val parts = t.split('^')
        if (parts.size < 3) return null
        val pan = parts[0].filter { it.isDigit() }.ifEmpty { null }
        val holder = parts[1].trim().ifEmpty { null }
        val tail = parts[2]
        return TrackInfo(
            pan = pan,
            holder = holder,
            expiry = tail.take(4).takeIf { it.length == 4 && it.all(Char::isDigit) },
            serviceCode = tail.drop(4).take(3).takeIf { it.length == 3 && it.all(Char::isDigit) },
        )
    }

    fun track2(raw: String?): TrackInfo? {
        if (raw.isNullOrBlank()) return null
        val t = raw.trim().removePrefix(";").substringBefore('?')
        val sep = t.indexOfFirst { it == '=' || it == 'D' || it == 'd' }
        if (sep <= 0) return null
        val pan = t.substring(0, sep).filter { it.isDigit() }.ifEmpty { null }
        val tail = t.substring(sep + 1)
        return TrackInfo(
            pan = pan,
            holder = null,
            expiry = tail.take(4).takeIf { it.length == 4 && it.all(Char::isDigit) },
            serviceCode = tail.drop(4).take(3).takeIf { it.length == 3 && it.all(Char::isDigit) },
        )
    }
}
