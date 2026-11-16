package io.github.flamy69.flamycar.car

import android.content.Intent
import androidx.car.app.CarAppService
import androidx.car.app.Screen
import androidx.car.app.Session
import androidx.car.app.validation.HostValidator

/**
 * The door through which Android Auto reaches the app. Android Auto binds to this service when
 * the app is opened on the display of the car, and asks it for the screen to show.
 */
class GaugeCarAppService : CarAppService() {
    // Apps in the store let in only Android Auto itself, recognized by the certificate Google
    // signs it with. This app is installed by hand and shows nothing but gauges, so it would
    // gain nothing from that, and would stop working the day the certificate changes.
    override fun createHostValidator(): HostValidator = HostValidator.ALLOW_ALL_HOSTS_VALIDATOR

    override fun onCreateSession(): Session = object : Session() {
        override fun onCreateScreen(intent: Intent): Screen = GaugeScreen(carContext)
    }
}
