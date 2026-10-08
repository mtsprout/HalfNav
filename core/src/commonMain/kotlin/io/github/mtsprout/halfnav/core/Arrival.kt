package io.github.mtsprout.halfnav.core

/**
 * Decides when the drive view says "You've arrived".
 *
 * A street address (a house) means the driveway: you've arrived only at the end of the route or
 * right at the address. A business or public place (a church, a store) means anywhere in its
 * parking lot will do.
 */
object Arrival {
    /** Address, on the route: this little road left is in front of the house. */
    const val ADDRESS_ROUTE_LEFT_METERS = 15.0
    /**
     * Address, off the route line and slowing: this close to the address point is the driveway.
     * Not used on the route, since a corner house is this close to the side street too.
     */
    const val ADDRESS_NEAR_METERS = 30.0

    /** Business: this little route left, or this close to the place itself, is arrived. */
    const val PLACE_ARRIVED_METERS = 40.0
    /**
     * Business, off the route and crawling: this close counts too, since you're parking somewhere
     * in its lot. Never applies while driving down the route.
     */
    const val PARKED_NEAR_METERS = 120.0
    /** About 7 mph: parking-lot speed. */
    const val PARKED_SPEED_MPS = 3.0

    fun reached(
        destination: Place,
        onRoute: Boolean,
        routeLeftMeters: Double,
        toDestinationMeters: Double,
        toRouteEndMeters: Double,
        speedMps: Double,
    ): Boolean {
        if (!destination.isBusiness) {
            return if (onRoute) routeLeftMeters < ADDRESS_ROUTE_LEFT_METERS
            else speedMps < PARKED_SPEED_MPS && toDestinationMeters < ADDRESS_NEAR_METERS
        }
        if (toDestinationMeters < PLACE_ARRIVED_METERS) return true
        if (onRoute) return routeLeftMeters < PLACE_ARRIVED_METERS
        return speedMps < PARKED_SPEED_MPS &&
            (toDestinationMeters < PARKED_NEAR_METERS || toRouteEndMeters < PARKED_NEAR_METERS)
    }
}
