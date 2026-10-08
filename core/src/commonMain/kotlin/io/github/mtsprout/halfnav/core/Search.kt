package io.github.mtsprout.halfnav.core

import kotlinx.serialization.json.JsonObject

/** A destination: a business or landmark ("The Alamo") or a plain address. */
data class Place(
    val name: String,
    val address: String,
    val latLng: LatLng,
    val category: String? = null,
    /** Town and state/region, e.g. "New Braunfels, TX", so look-alike places can be told apart. */
    val locality: String? = null,
) {
    /**
     * A business or public place (church, store, park) rather than a plain street address.
     * TomTom gives those a category; addresses, streets and saved Home have none.
     */
    val isBusiness: Boolean get() = category != null
}

/** Parses a TomTom Search API (fuzzy search) response. */
object TomTomSearchParser {
    /** Results closer than this with the same name are treated as duplicates. */
    private const val DUPLICATE_METERS = 300.0

    @Throws(RouteException::class) // lets Swift catch it instead of crashing
    fun parse(json: String): List<Place> {
        val root = parseJsonObject(json) ?: throw RouteException("Unexpected response from TomTom search")
        root.obj("error")?.let { throw RouteException(it.str("description").ifBlank { "Search error" }) }
        root.str("errorText").takeIf { it.isNotBlank() }?.let { throw RouteException(it) }

        val results = root.arr("results") ?: return emptyList()
        val places = mutableListOf<Place>()
        for (e in results) {
            val r = e.asObject() ?: continue
            val pos = r.obj("position") ?: continue
            val lat = pos.double("lat") ?: continue
            val lng = pos.double("lon") ?: continue
            val addr = r.obj("address")
            val freeform = addr?.str("freeformAddress").orEmpty()
            val poi = r.obj("poi")
            val name = poi?.str("name")?.ifBlank { null }
                ?: listOfNotNull(
                    addr?.str("streetNumber")?.ifBlank { null },
                    addr?.str("streetName")?.ifBlank { null },
                ).joinToString(" ").ifBlank { null }
                ?: freeform.substringBefore(',').ifBlank { null }
                ?: continue
            val category = poi?.arr("categories").strings().firstOrNull()?.ifBlank { null }
            val place = Place(name, freeform, LatLng(lat, lng), category, locality(addr))
            val duplicate = places.any {
                it.name.equals(place.name, ignoreCase = true) &&
                    Geo.distanceMeters(it.latLng, place.latLng) < DUPLICATE_METERS
            }
            if (!duplicate) places += place
        }
        return places
    }

    /**
     * Parses a TomTom Reverse Geocode response into the street address at [at], or null if TomTom
     * has none. The place keeps [at] as its position, so it's exactly where you were standing.
     */
    @Throws(RouteException::class)
    fun parseAddress(json: String, at: LatLng): Place? {
        val root = parseJsonObject(json) ?: throw RouteException("Unexpected response from TomTom")
        root.obj("error")?.let { throw RouteException(it.str("description").ifBlank { "Address lookup error" }) }
        root.str("errorText").takeIf { it.isNotBlank() }?.let { throw RouteException(it) }

        val addr = root.arr("addresses")?.firstOrNull()?.asObject()?.obj("address") ?: return null
        val freeform = addr.str("freeformAddress").ifBlank { return null }
        val street = listOfNotNull(
            addr.str("streetNumber").ifBlank { null },
            addr.str("streetName").ifBlank { addr.str("street") }.ifBlank { null },
        ).joinToString(" ").ifBlank { freeform.substringBefore(',') }
        return Place(street, freeform, at, null, locality(addr))
    }

    /** "New Braunfels, TX" in the US; "Scarborough, ON, Canada" elsewhere. */
    private fun locality(addr: JsonObject?): String? {
        if (addr == null) return null
        val town = addr.str("municipality").ifBlank { addr.str("localName") }.ifBlank { null }
        val region = addr.str("countrySubdivisionCode").ifBlank { addr.str("countrySubdivision") }.ifBlank { null }
        val country = addr.str("countryCode").takeIf { it.isNotBlank() && it != "US" }
            ?.let { addr.str("country").ifBlank { it } }
        return listOfNotNull(town, region, country).joinToString(", ").ifBlank { null }
    }
}
