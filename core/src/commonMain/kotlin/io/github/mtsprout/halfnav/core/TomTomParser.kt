package io.github.mtsprout.halfnav.core

import kotlinx.serialization.json.JsonObject

class RouteException(message: String) : Exception(message)

/** Parses a TomTom Calculate Route response (sectionType=traffic&sectionType=motorway, instructions on). */
object TomTomParser {

    @Throws(RouteException::class) // lets Swift catch it instead of crashing
    fun parse(json: String): Route {
        val root = parseJsonObject(json) ?: throw RouteException("Unexpected response from TomTom")
        errorMessage(root)?.let { throw RouteException(it) }

        val routes = root.arr("routes")
        val route = routes?.firstOrNull()?.asObject() ?: throw RouteException("No route found")

        val points = mutableListOf<LatLng>()
        route.arr("legs")?.forEach { leg ->
            leg.asObject()?.arr("points")?.forEach { p ->
                val pt = p.asObject() ?: return@forEach
                val lat = pt.double("latitude") ?: return@forEach
                val lng = pt.double("longitude") ?: return@forEach
                points += LatLng(lat, lng)
            }
        }

        val instructions = mutableListOf<Instruction>()
        route.obj("guidance")?.arr("instructions")?.forEach { e ->
            val ins = e.asObject() ?: return@forEach
            instructions += Instruction(
                pointIndex = ins.int("pointIndex"),
                roadNumbers = ins.arr("roadNumbers").strings(),
                street = ins.str("street").ifBlank { null },
                maneuver = ins.str("maneuver").ifBlank { null },
                message = ins.str("message").ifBlank { null },
            )
        }

        val traffic = mutableListOf<TrafficSection>()
        val motorways = mutableListOf<IntRange>()
        route.arr("sections")?.forEach { e ->
            val s = e.asObject() ?: return@forEach
            val start = s.int("startPointIndex")
            val end = s.int("endPointIndex", start)
            when (s.str("sectionType")) {
                "TRAFFIC" -> traffic += TrafficSection(
                    startPointIndex = start,
                    endPointIndex = end,
                    category = s.str("simpleCategory", "OTHER"),
                    delaySec = s.int("delayInSeconds"),
                    magnitude = s.int("magnitudeOfDelay"),
                )
                "MOTORWAY" -> motorways += start..end
            }
        }

        val travelTime = route.obj("summary")?.int("travelTimeInSeconds") ?: 0
        if (points.size < 2) throw RouteException("Route had no geometry")
        return Route(points, travelTime, instructions, traffic, motorways)
    }

    private fun errorMessage(root: JsonObject): String? {
        root.obj("error")?.let { return it.str("description").ifBlank { "Routing error" } }
        root.obj("detailedError")?.let { return it.str("message").ifBlank { "Routing error" } }
        if (root.has("errorText")) return root.str("errorText")
        return null
    }
}
