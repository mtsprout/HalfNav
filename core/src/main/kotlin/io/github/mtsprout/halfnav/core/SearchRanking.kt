package io.github.mtsprout.halfnav.core

import kotlin.math.floor

/**
 * Re-orders search results so that, among places with nearly the same name, the closest comes
 * first. A search for "Sts Peter and Paul" should list the church 9 miles away above the
 * identically named one 450 miles away.
 *
 * Results with unrelated names keep TomTom's order: each group of look-alikes is re-sorted
 * within the list positions it already occupies.
 */
object SearchRanking {
    /** Two names are look-alikes when they share at least this share of their words. */
    private const val SIMILARITY = 0.75
    /** Distances within the same mile count as a tie, so TomTom's order decides. */
    private const val TIE_METERS = Geo.METERS_PER_MILE

    private val STOP_WORDS = setOf("the", "and", "of", "s")
    private val SYNONYMS = mapOf(
        "st" to "saint", "sts" to "saint", "ste" to "saint", "saints" to "saint",
        "mt" to "mount", "ft" to "fort",
    )

    fun nearbyFirst(places: List<Place>, here: LatLng?): List<Place> {
        if (here == null || places.size < 2) return places
        val words = places.map { nameWords(it.name) }
        val result = places.toMutableList()
        val grouped = BooleanArray(places.size)
        for (i in places.indices) {
            if (grouped[i]) continue
            val slots = places.indices.filter { j -> !grouped[j] && similar(words[i], words[j]) }
            slots.forEach { grouped[it] = true }
            if (slots.size < 2) continue
            val sorted = slots.map { places[it] }
                .sortedBy { floor(Geo.distanceMeters(here, it.latLng) / TIE_METERS) } // stable: ties keep order
            slots.forEachIndexed { k, slot -> result[slot] = sorted[k] }
        }
        return result
    }

    /** "Sts. Peter & Paul's Church" -> {saint, peter, paul, church}. */
    internal fun nameWords(name: String): Set<String> =
        name.lowercase()
            .replace("&", " and ")
            .replace(Regex("['’]s\\b"), "")
            .split(Regex("[^\\p{L}\\p{N}]+"))
            .filter { it.isNotBlank() }
            .map { SYNONYMS[it] ?: it }
            .filterNot { it in STOP_WORDS }
            .toSet()

    private fun similar(a: Set<String>, b: Set<String>): Boolean {
        if (a.isEmpty() || b.isEmpty()) return false
        val shared = a.intersect(b).size.toDouble()
        return shared / a.union(b).size >= SIMILARITY
    }
}
