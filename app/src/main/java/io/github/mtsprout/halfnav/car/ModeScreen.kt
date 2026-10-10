package io.github.mtsprout.halfnav.car

import androidx.car.app.CarContext
import androidx.car.app.Screen
import androidx.car.app.model.Action
import androidx.car.app.model.ItemList
import androidx.car.app.model.ListTemplate
import androidx.car.app.model.Row
import androidx.car.app.model.Template
import androidx.lifecycle.lifecycleScope
import io.github.mtsprout.halfnav.Mode
import io.github.mtsprout.halfnav.Prefs
import io.github.mtsprout.halfnav.Settings
import io.github.mtsprout.halfnav.formatMiles
import kotlinx.coroutines.launch

/** Picks where HalfNav guides: to the interstate, the first miles, or the last miles. */
class ModeScreen(carContext: CarContext, private val settings: Settings) : Screen(carContext) {

    override fun onGetTemplate(): Template {
        val modes = listOf(
            Triple(Mode.START_INTERSTATE, "To the interstate", "Guide the way to the first highway, then you're on your own"),
            Triple(Mode.START_MILES, "First ${formatMiles(settings.startMiles.toDouble())}", "Guide the start of the trip only"),
            Triple(Mode.END_MILES, "Last ${formatMiles(settings.endMiles.toDouble())}", "Quiet until you're close, then guide to the door"),
        )
        val list = ItemList.Builder()
        modes.forEach { (mode, title, hint) ->
            list.addItem(
                Row.Builder()
                    .setTitle(if (mode == settings.mode) "$title  ✓" else title)
                    .addText(hint)
                    .setOnClickListener { choose(mode) }
                    .build()
            )
        }
        return ListTemplate.Builder()
            .setTitle("Guide me")
            .setHeaderAction(Action.BACK)
            .setSingleList(list.build())
            .build()
    }

    private fun choose(mode: Mode) {
        lifecycleScope.launch {
            Prefs(carContext).setMode(mode)
            setResult(mode)
            finish()
        }
    }
}
