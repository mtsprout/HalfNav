package io.github.mtsprout.halfnav

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Place
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.PathParser
import androidx.compose.ui.unit.dp
import io.github.mtsprout.halfnav.core.PlaceKind

/**
 * Outline of a few buildings, 24 x 24 (Material Design's "location city" shape, Apache 2.0). It's
 * not in the core icon set the app uses, so it's drawn here. [CITY_PATH] is also used on the car
 * screen.
 */
const val CITY_PATH =
    "M15,11V5l-3,-3 -3,3v2H3v14h18V11H15zM7,19H5v-2h2V19zM7,15H5v-2h2V15zM7,11H5V9h2V11zM13,19h-2v-2h2V19z" +
        "M13,15h-2v-2h2V15zM13,11h-2V9h2V11zM13,7h-2V5h2V7zM19,19h-2v-2h2V19zM19,15h-2v-2h2V15z"

private val CityIcon: ImageVector by lazy {
    ImageVector.Builder("City", 24.dp, 24.dp, 24f, 24f)
        .addPath(PathParser().parsePathString(CITY_PATH).toNodes(), fill = SolidColor(Color.Black))
        .build()
}

/** The icon for a search result: a skyline for cities, the usual pin for everything else. */
fun placeIcon(kind: PlaceKind?): ImageVector = if (kind == PlaceKind.CITY) CityIcon else Icons.Filled.Place
