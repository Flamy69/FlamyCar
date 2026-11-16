package io.github.flamy69.flamycar

import android.app.Application

class FlamyCarApp : Application() {
    /** The one connection to the car, shared by the phone and the display of the car. */
    val obd: ObdManager by lazy { ObdManager(this) }
}
