package io.github.mtsprout.halfnav.core

/** What a map link (a tapped address, a shared pin) is asking for: a spot, or text to look up. */
data class MapLink(
    /** A specific spot, when the link has coordinates. */
    val latLng: LatLng? = null,
    /** An address or place name to search for, when the link has no coordinates. */
    val query: String? = null,
    /** A name for the spot, e.g. the "Home" in `geo:29.7,-98.1?q=29.7,-98.1(Home)`. */
    val label: String? = null,
)

/**
 * Reads the links other apps use to open a place in a map app: `geo:` links (what Android sends
 * when you tap an address in Messages, Chrome or Contacts) and `google.navigation:` links.
 *
 * - `geo:29.70,-98.12` and `geo:29.70,-98.12?z=15`: a spot
 * - `geo:0,0?q=123+Main+St,+Town`: an address to look up (0,0 means "no spot")
 * - `geo:0,0?q=29.70,-98.12(Pat's)`: a spot with a name
 * - `google.navigation:q=123+Main+St` or `google.navigation:q=29.70,-98.12&mode=d`
 */
object MapLinks {
    private val latLngText = Regex("""^\s*(-?\d{1,3}(?:\.\d+)?)\s*,\s*(-?\d{1,3}(?:\.\d+)?)\s*(?:\((.*)\))?\s*$""")

    fun parse(uri: String): MapLink? {
        val text = uri.trim()
        val scheme = text.substringBefore(':', "").lowercase()
        val rest = text.substringAfter(':', "")
        val (path, queryString) = when (scheme) {
            "geo" -> rest.substringBefore('?') to rest.substringAfter('?', "")
            "google.navigation" -> "" to rest.removePrefix("//").removePrefix("?")
            else -> return null
        }
        val params = queryString.split('&').filter { it.isNotEmpty() }.associate {
            decode(it.substringBefore('=')).lowercase() to decode(it.substringAfter('=', ""))
        }

        // Coordinates in the path ("29.7,-98.1;u=35" has parameters after a semicolon).
        val pathSpot = latLngText.find(path.substringBefore(';'))?.let { spot(it) }

        val q = params["q"]?.trim()?.ifEmpty { null }
        if (q != null) {
            latLngText.find(q)?.let { m -> spot(m)?.let { return MapLink(latLng = it.first, label = m.groupValues[3].ifBlank { null }) } }
            return MapLink(query = q)
        }
        val (here, _) = pathSpot ?: return null
        return MapLink(latLng = here)
    }

    /** The coordinates in a match of [latLngText], or null for 0,0 and out-of-range values. */
    private fun spot(m: MatchResult): Pair<LatLng, String?>? {
        val lat = m.groupValues[1].toDoubleOrNull() ?: return null
        val lng = m.groupValues[2].toDoubleOrNull() ?: return null
        if (lat == 0.0 && lng == 0.0) return null
        if (lat !in -90.0..90.0 || lng !in -180.0..180.0) return null
        return LatLng(lat, lng) to m.groupValues[3].ifBlank { null }
    }

    /** Percent-decoding with "+" as a space, as in a URL query. */
    private fun decode(s: String): String {
        if ('%' !in s && '+' !in s) return s
        val out = StringBuilder()
        val bytes = ArrayList<Byte>()
        fun flush() {
            if (bytes.isNotEmpty()) {
                out.append(bytes.toByteArray().decodeToString())
                bytes.clear()
            }
        }
        var i = 0
        while (i < s.length) {
            val c = s[i]
            val hex = if (c == '%' && i + 2 < s.length) s.substring(i + 1, i + 3).toIntOrNull(16) else null
            when {
                hex != null -> { bytes += hex.toByte(); i += 3 }
                else -> {
                    flush()
                    out.append(if (c == '+') ' ' else c)
                    i++
                }
            }
        }
        flush()
        return out.toString()
    }
}
