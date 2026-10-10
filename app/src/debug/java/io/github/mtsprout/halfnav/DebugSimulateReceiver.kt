package io.github.mtsprout.halfnav

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

/**
 * Debug builds only. Drives the running trip along its route without moving the phone:
 *
 *   adb shell am broadcast -a io.github.mtsprout.halfnav.SIMULATE -p io.github.mtsprout.halfnav \
 *       --ef speed 25 --ef skip 0
 *
 * speed: metres per second (default 20, about 45 mph). skip: metres to jump ahead first.
 * Add `--ez stop true` to end the simulation and go back to real GPS.
 */
class DebugSimulateReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
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
}
