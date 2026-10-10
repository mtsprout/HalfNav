package io.github.mtsprout.halfnav.car

import androidx.car.app.CarContext
import androidx.car.app.constraints.ConstraintManager
import androidx.car.app.model.ItemList
import androidx.car.app.model.Row
import io.github.mtsprout.halfnav.core.Place

private fun Place.subtitle(): String =
    listOfNotNull(address.ifBlank { null }, locality?.takeIf { address.isBlank() || !address.contains(it) })
        .joinToString(" · ")

fun placeRow(place: Place, onClick: () -> Unit): Row {
    val b = Row.Builder().setTitle(place.name).setOnClickListener(onClick)
    place.subtitle().takeIf { it.isNotBlank() }?.let { b.addText(it) }
    return b.build()
}

/** The most rows the car will show right now; fewer while driving. */
fun CarContext.listLimit(): Int =
    getCarService(ConstraintManager::class.java).getContentLimit(ConstraintManager.CONTENT_LIMIT_TYPE_LIST)

fun CarContext.placeList(places: List<Place>, onClick: (Place) -> Unit): ItemList {
    val b = ItemList.Builder()
    places.take(listLimit()).forEach { b.addItem(placeRow(it) { onClick(it) }) }
    return b.build()
}
