package io.github.mtsprout.halfnav.car

import android.content.Intent
import androidx.car.app.CarAppService
import androidx.car.app.Screen
import androidx.car.app.Session
import androidx.car.app.validation.HostValidator

/** Entry point for Android Auto. The car host binds to this service. */
class HalfNavCarService : CarAppService() {
    // Allow any host: the app is sideloaded for personal use. Tighten to the known
    // Google hosts (HostValidator.ALLOW_ALL_HOSTS_VALIDATOR replacement) before a Play release.
    override fun createHostValidator(): HostValidator = HostValidator.ALLOW_ALL_HOSTS_VALIDATOR

    override fun onCreateSession(): Session = HalfNavSession()
}

class HalfNavSession : Session() {
    override fun onCreateScreen(intent: Intent): Screen = HomeScreen(carContext)
}
