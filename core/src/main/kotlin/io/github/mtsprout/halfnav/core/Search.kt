package io.github.mtsprout.halfnav.core

import org.json.JSONObject

/** A destination: a business or landmark ("The Alamo") or a plain address. */
data class Place(
    val name: String,
    val address: String,
    val latLng: LatLng,
    val category: String? = null,
    /** Town and state/region, e.g. "New Braunfels, TX", so look-alike places can be told apart. */
    val locality: String? = null,
)

/** Parses a TomTom Search API (fuzzy search) response. */
object TomTomSearchParser {
    /** Results closer than this with the same name are treated as duplicates. */
    private const val DUPLICATE_METERS = 300.0

    fun parse(json: String): List<Place> {
        val root = try {
            JSONObject(json)
        } catch (e: Exception) {
            throw RouteException("Unexpected response from TomTom search")
        }
        root.optJSONObject("error")?.let { throw RouteException(it.optString("description").ifBlank { "Search error" }) }
        root.optString("errorText").takeIf { it.isNotBlank() }?.let { throw RouteException(it) }

        val results = root.optJSONArray("results") ?: return emptyList()
        val places = mutableListOf<Place>()
        for (i in 0 until results.length()) {
            val r = results.getJSONObject(i)
            val pos = r.optJSONObject("position") ?: continue
            val addr = r.optJSONObject("address")
            val freeform = addr?.optString("freeformAddress").orEmpty()
            val poi = r.optJSONObject("poi")
            val name = poi?.optString("name")?.ifBlank { null }
                ?: listOfNotNull(
                    addr?.optString("streetNumber")?.ifBlank { null },
                    addr?.optString("streetName")?.ifBlank { null },
                ).joinToString(" ").ifBlank { null }
                ?: freeform.substringBefore(',').ifBlank { null }
                ?: continue
            val category = poi?.optJSONArray("categories")?.optString(0)?.ifBlank { null }
            val place = Place(name, freeform, LatLng(pos.getDouble("lat"), pos.getDouble("lon")), category, locality(addr))
            val duplicate = places.any {
                it.name.equals(place.name, ignoreCase = true) &&
                    Geo.distanceMeters(it.latLng, place.latLng) < DUPLICATE_METERS
            }
            if (!duplicate) places += place
        }
        return places
    }

    /** "New Braunfels, TX" in the US; "Scarborough, ON, Canada" elsewhere. */
    private fun locality(addr: JSONObject?): String? {
        if (addr == null) return null
        val town = addr.optString("municipality").ifBlank { addr.optString("localName") }.ifBlank { null }
        val region = addr.optString("countrySubdivisionCode").ifBlank { addr.optString("countrySubdivision") }.ifBlank { null }
        val country = addr.optString("countryCode").takeIf { it.isNotBlank() && it != "US" }
            ?.let { addr.optString("country").ifBlank { it } }
        return listOfNotNull(town, region, country).joinToString(", ").ifBlank { null }
    }
}
