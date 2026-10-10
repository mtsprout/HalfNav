package io.github.mtsprout.halfnav.core

/** A parking lot or garage TomTom knows about. */
data class ParkingLot(
    val name: String,
    val position: LatLng,
    /** Where you drive in, if TomTom knows; otherwise the lot itself. */
    val entrance: LatLng,
)

object Parking {
    /** TomTom category IDs: open parking area, parking garage. */
    const val CATEGORY_SET = "7369,7313"
    /** Look this far around a destination for its lot. */
    const val SEARCH_METERS = 300

    private val PARKING_CATEGORIES = setOf("open parking area", "parking garage")

    fun isParking(place: Place): Boolean = place.category?.lowercase() in PARKING_CATEGORIES

    /** Parses a TomTom Nearby Search response for parking. */
    @Throws(RouteException::class)
    fun parse(json: String): List<ParkingLot> {
        val root = parseJsonObject(json) ?: throw RouteException("Unexpected response from TomTom search")
        root.obj("error")?.let { throw RouteException(it.str("description").ifBlank { "Search error" }) }
        root.str("errorText").takeIf { it.isNotBlank() }?.let { throw RouteException(it) }
        return root.arr("results").orEmpty().mapNotNull { e ->
            val r = e.asObject() ?: return@mapNotNull null
            val pos = r.obj("position") ?: return@mapNotNull null
            val at = LatLng(pos.double("lat") ?: return@mapNotNull null, pos.double("lon") ?: return@mapNotNull null)
            val name = r.obj("poi")?.str("name")?.ifBlank { null } ?: return@mapNotNull null
            val entries = r.arr("entryPoints").orEmpty().mapNotNull { it.asObject() }
            val entry = (entries.firstOrNull { it.str("type") == "main" } ?: entries.firstOrNull())
                ?.obj("position")
                ?.let { p -> p.double("lat")?.let { lat -> p.double("lon")?.let { LatLng(lat, it) } } }
            ParkingLot(name, at, entry ?: at)
        }
    }

    /**
     * The lot that belongs to [destination]: TomTom names a place's own lot after it
     * ("Indigo Saints Peter & Paul Catholic Church"). Any other lot nearby may be someone's paid
     * lot, so it isn't used.
     */
    fun lotFor(destination: Place, lots: List<ParkingLot>): ParkingLot? {
        val words = SearchRanking.nameWords(destination.name)
        if (words.isEmpty()) return null
        return lots
            .filter { SearchRanking.nameWords(it.name).containsAll(words) }
            .filter { Geo.distanceMeters(it.position, destination.latLng) <= SEARCH_METERS }
            .minByOrNull { Geo.distanceMeters(it.position, destination.latLng) }
    }
}
