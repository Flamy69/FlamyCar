package io.github.flamy69.flamycar

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import io.github.flamy69.flamycar.ui.FlamyCarTheme
import io.github.flamy69.flamycar.ui.MainScreen

class MainActivity : ComponentActivity() {
    private val obd get() = (application as FlamyCarApp).obd

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            FlamyCarTheme {
                MainScreen(obd, watcher = this)
            }
        }
    }

    // The car is talked to while the app is on screen; with the app in the background, only
    // the display of the car keeps the connection up.
    override fun onStart() {
        super.onStart()
        obd.attach(this)
    }

    override fun onStop() {
        obd.detach(this)
        super.onStop()
    }
}
