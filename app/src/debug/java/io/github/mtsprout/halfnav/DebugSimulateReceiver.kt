package io.github.mtsprout.halfnav

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import io.github.mtsprout.halfnav.core.ConstructionWarning
import io.github.mtsprout.halfnav.core.WorkKind

/**
 * Debug builds only. Drives the running trip along its route without moving the phone:
 *
 *   adb shell am broadcast -a io.github.mtsprout.halfnav.SIMULATE -p io.github.mtsprout.halfnav \
 *       --ef speed 25 --ef skip 0
 *
 * speed: metres per second (default 20, about 45 mph). skip: metres to jump ahead first.
 * Add `--ez stop true` to end the simulation and go back to real GPS.
 *
 * Fake construction ahead of the trip's current position, to see the zones on the map:
 *
 *   adb shell am broadcast -a io.github.mtsprout.halfnav.FAKE_WORK -p io.github.mtsprout.halfnav
 *
 * Add `--ez clear true` to remove them.
 */
class DebugSimulateReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action == ACTION_FAKE_WORK) {
            fakeWork(intent.getBooleanExtra("clear", false))
            return
        }
        if (intent.getBooleanExtra("stop", false)) {
            DriveService.simulation.value = null
            return
        }
        if (DriveService.trip.value == null) return
        DriveService.simulation.value = DriveService.Companion.Sim(
            speedMps = intent.getFloatExtra("speed", 20f),
            jumpMeters = intent.getFloatExtra("skip", 0f).toDouble(),
        )
    }

    private fun fakeWork(clear: Boolean) {
        val trip = DriveService.trip.value ?: return
        if (clear) {
            DriveService.replaceWarnings(emptyList())
            return
        }
        val here = DriveService.progress.value.offsetMeters
        val length = trip.route.lengthMeters
        // (distance ahead, length, kind, delay seconds, magnitude)
        val zones = listOf(
            listOf(1500.0, 600.0, WorkKind.ROAD_WORK, 120, 2),
            listOf(4000.0, 300.0, WorkKind.ROAD_CLOSURE, 600, 4),
            listOf(7000.0, 1200.0, WorkKind.ROAD_WORK, 300, 3),
        )
        val warnings = zones.mapNotNull { z ->
            val start = here + z[0] as Double
            val end = start + z[1] as Double
            if (end > length) return@mapNotNull null
            ConstructionWarning(
                kind = z[2] as WorkKind,
                startOffsetMeters = start,
                lengthMeters = z[1] as Double,
                delaySec = z[3] as Int,
                magnitude = z[4] as Int,
                road = "Test Rd",
                unguided = trip.handoff?.let { !it.reachesDestination && end > it.offsetMeters } ?: false,
            )
        }
        DriveService.replaceWarnings(warnings)
    }

    private companion object {
        const val ACTION_FAKE_WORK = "io.github.mtsprout.halfnav.FAKE_WORK"
    }
}
