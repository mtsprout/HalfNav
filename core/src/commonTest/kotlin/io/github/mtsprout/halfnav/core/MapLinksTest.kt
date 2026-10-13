package io.github.mtsprout.halfnav.core

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class MapLinksTest {

    @Test
    fun readsASpot() {
        assertEquals(MapLink(latLng = LatLng(29.7024, -98.1251)), MapLinks.parse("geo:29.7024,-98.1251"))
        assertEquals(MapLink(latLng = LatLng(29.7024, -98.1251)), MapLinks.parse("geo:29.7024,-98.1251?z=15"))
        assertEquals(MapLink(latLng = LatLng(-33.86, 151.21)), MapLinks.parse("GEO:-33.86,151.21;u=35"))
    }

    @Test
    fun readsAnAddressToLookUp() {
        // What Android sends for a tapped address: 0,0 means "no spot".
        assertEquals(
            MapLink(query = "202 S Union Ave, New Braunfels, TX 78130"),
            MapLinks.parse("geo:0,0?q=202+S+Union+Ave,+New+Braunfels,+TX+78130"),
        )
        assertEquals(
            MapLink(query = "1600 Amphitheatre Pkwy, Mountain View"),
            MapLinks.parse("geo:0,0?q=1600%20Amphitheatre%20Pkwy%2C%20Mountain%20View"),
        )
        // A search near a spot is still a search.
        assertEquals(MapLink(query = "pizza"), MapLinks.parse("geo:29.7,-98.1?q=pizza"))
    }

    @Test
    fun readsANamedSpot() {
        assertEquals(
            MapLink(latLng = LatLng(29.7, -98.1), label = "Pat's Place"),
            MapLinks.parse("geo:0,0?q=29.7,-98.1(Pat's%20Place)"),
        )
        assertEquals(MapLink(latLng = LatLng(29.7, -98.1)), MapLinks.parse("geo:0,0?q=29.7,-98.1"))
    }

    @Test
    fun decodesNonAscii() {
        assertEquals(MapLink(query = "Café Münch"), MapLinks.parse("geo:0,0?q=Caf%C3%A9+M%C3%BCnch"))
    }

    @Test
    fun readsGoogleNavigationLinks() {
        assertEquals(MapLink(query = "123 Main St"), MapLinks.parse("google.navigation:q=123+Main+St&mode=d"))
        assertEquals(MapLink(latLng = LatLng(29.7, -98.1)), MapLinks.parse("google.navigation:q=29.7,-98.1&mode=d"))
    }

    @Test
    fun ignoresWhatItCantUse() {
        assertNull(MapLinks.parse("geo:0,0"))
        assertNull(MapLinks.parse("geo:"))
        assertNull(MapLinks.parse("geo:999,999"))
        assertNull(MapLinks.parse("https://example.com/?q=hi"))
        assertNull(MapLinks.parse("geo:0,0?q="))
    }
}
