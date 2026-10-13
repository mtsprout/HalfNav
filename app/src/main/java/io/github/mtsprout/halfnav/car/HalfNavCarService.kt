package io.github.mtsprout.halfnav.car

import android.content.Intent
import android.content.pm.ApplicationInfo
import androidx.car.app.CarAppService
import androidx.car.app.Screen
import androidx.car.app.Session
import androidx.car.app.validation.HostValidator

/** Entry point for Android Auto. The car host binds to this service. */
class HalfNavCarService : CarAppService() {
    // Debug builds accept any host, so the emulator and sideloaded testing work. Release builds
    // accept only Google's Android Auto host, using the allow-list that ships with the library.
    override fun createHostValidator(): HostValidator =
        if (applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE != 0) {
            HostValidator.ALLOW_ALL_HOSTS_VALIDATOR
        } else {
            HostValidator.Builder(applicationContext)
                .addAllowedHosts(androidx.car.app.R.array.hosts_allowlist_sample)
                .build()
        }

    override fun onCreateSession(): Session = HalfNavSession()
}

class HalfNavSession : Session() {
    override fun onCreateScreen(intent: Intent): Screen = HomeScreen(carContext)
}
