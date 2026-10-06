package io.github.mtsprout.halfnav.core

import org.json.JSONArray
import org.json.JSONObject

class RouteException(message: String) : Exception(message)

/** Parses a TomTom Calculate Route response (sectionType=traffic&sectionType=motorway, instructions on). */
object TomTomParser {

    fun parse(json: String): Route {
        val root = try {
            JSONObject(json)
        } catch (e: Exception) {
            throw RouteException("Unexpected response from TomTom")
        }
        errorMessage(root)?.let { throw RouteException(it) }

        val routes = root.optJSONArray("routes")
        if (routes == null || routes.length() == 0) throw RouteException("No route found")
        val route = routes.getJSONObject(0)

        val points = mutableListOf<LatLng>()
        val legs = route.getJSONArray("legs")
        for (l in 0 until legs.length()) {
            val legPoints = legs.getJSONObject(l).getJSONArray("points")
            for (p in 0 until legPoints.length()) {
                val pt = legPoints.getJSONObject(p)
                points += LatLng(pt.getDouble("latitude"), pt.getDouble("longitude"))
            }
        }

        val instructions = mutableListOf<Instruction>()
        route.optJSONObject("guidance")?.optJSONArray("instructions")?.let { arr ->
            for (i in 0 until arr.length()) {
                val ins = arr.getJSONObject(i)
                instructions += Instruction(
                    pointIndex = ins.optInt("pointIndex", 0),
                    roadNumbers = ins.optJSONArray("roadNumbers").strings(),
                    street = ins.optString("street").ifBlank { null },
                    maneuver = ins.optString("maneuver").ifBlank { null },
                    message = ins.optString("message").ifBlank { null },
                )
            }
        }

        val traffic = mutableListOf<TrafficSection>()
        val motorways = mutableListOf<IntRange>()
        route.optJSONArray("sections")?.let { arr ->
            for (i in 0 until arr.length()) {
                val s = arr.getJSONObject(i)
                val start = s.optInt("startPointIndex", 0)
                val end = s.optInt("endPointIndex", start)
                when (s.optString("sectionType")) {
                    "TRAFFIC" -> traffic += TrafficSection(
                        startPointIndex = start,
                        endPointIndex = end,
                        category = s.optString("simpleCategory", "OTHER"),
                        delaySec = s.optInt("delayInSeconds", 0),
                        magnitude = s.optInt("magnitudeOfDelay", 0),
                    )
                    "MOTORWAY" -> motorways += start..end
                }
            }
        }

        val travelTime = route.optJSONObject("summary")?.optInt("travelTimeInSeconds", 0) ?: 0
        if (points.size < 2) throw RouteException("Route had no geometry")
        return Route(points, travelTime, instructions, traffic, motorways)
    }

    private fun errorMessage(root: JSONObject): String? {
        root.optJSONObject("error")?.let { return it.optString("description").ifBlank { "Routing error" } }
        root.optJSONObject("detailedError")?.let { return it.optString("message").ifBlank { "Routing error" } }
        if (root.has("errorText")) return root.optString("errorText")
        return null
    }

    private fun JSONArray?.strings(): List<String> =
        if (this == null) emptyList() else (0 until length()).map { getString(it) }
}
