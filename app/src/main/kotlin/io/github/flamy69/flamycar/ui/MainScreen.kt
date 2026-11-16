package io.github.flamy69.flamycar.ui

import android.Manifest
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.graphics.Rect
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLocale
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.flamy69.flamycar.DashboardPainter
import io.github.flamy69.flamycar.ObdManager
import io.github.flamy69.flamycar.Phase
import io.github.flamy69.flamycar.R
import io.github.flamy69.flamycar.Readings
import io.github.flamy69.flamycar.Status
import io.github.flamy69.flamycar.obd.BluetoothLink
import io.github.flamy69.flamycar.obd.CarInfo
import io.github.flamy69.flamycar.obd.Query

/**
 * The app on the phone: the place to choose the adapter, to see what the car gives, and to
 * look at the gauges the way the display of the car will show them. [watcher] is the name
 * under which the activity announced itself to [obd].
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MainScreen(obd: ObdManager, watcher: Any) {
    val status by obd.status.collectAsStateWithLifecycle()
    val readings by obd.readings.collectAsStateWithLifecycle()
    val context = LocalContext.current
    var choosing by rememberSaveable { mutableStateOf(false) }

    val permission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) {
            obd.restart()
            choosing = true
        }
    }
    val choose = {
        if (BluetoothLink.permitted(context)) choosing = true else permission.launch(Manifest.permission.BLUETOOTH_CONNECT)
    }

    Scaffold(topBar = { TopAppBar(title = { Text(stringResource(R.string.app_name)) }) }) { padding ->
        Column(
            modifier = Modifier
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Preview(obd, watcher, status, readings)
            StatusLine(status, onAllow = choose)
            AdapterCard(status, onChoose = choose, onDemo = obd::setDemo)
            val car = status.car
            if (car != null && status.phase == Phase.LIVE) ValuesCard(car, readings)
            AndroidAutoCard()
            SurveyCard(obd, live = status.phase == Phase.LIVE)
            TroubleshootingCard(obd, car)
        }
    }

    if (choosing) {
        AdapterDialog(
            onChosen = {
                obd.chooseAdapter(it)
                choosing = false
            },
            onDismiss = { choosing = false },
        )
    }
}

/** The gauges as the display of the car shows them. Touching them turns the page. */
@Composable
private fun Preview(obd: ObdManager, watcher: Any, status: Status, readings: Readings) {
    val context = LocalContext.current
    val painter = remember { DashboardPainter(context) }
    var page by rememberSaveable { mutableIntStateOf(0) }
    val live = status.phase == Phase.LIVE

    // What the page shows is known once it was drawn; it changes with the page and the car.
    LaunchedEffect(page, status.car, live) {
        withFrameNanos { }
        obd.show(watcher, painter.visible)
    }
    // Needles glide between the values, so the picture is drawn anew for every frame of the
    // screen while there are values to show.
    val frame by produceState(0L, live) {
        while (live) withFrameNanos { value = it }
    }

    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Canvas(
            modifier = Modifier
                .fillMaxWidth()
                .aspectRatio(16f / 9f)
                .clip(RoundedCornerShape(16.dp))
                .clickable { page = (page + 1) % painter.pages },
        ) {
            // Reading the frame time here is what makes this block run again for the next one.
            frame.hashCode()
            drawIntoCanvas {
                painter.draw(it.nativeCanvas, Rect(0, 0, size.width.toInt(), size.height.toInt()), status, readings, page)
            }
        }
        Text(
            text = stringResource(R.string.preview_hint),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun StatusLine(status: Status, onAllow: () -> Unit) {
    val text = when (status.phase) {
        Phase.IDLE, Phase.CONNECTING ->
            if (status.demo) stringResource(R.string.car_connecting)
            else stringResource(R.string.status_connecting, status.adapter.orEmpty())
        Phase.NO_ADAPTER -> stringResource(R.string.status_no_adapter)
        Phase.NO_PERMISSION -> stringResource(R.string.status_no_permission)
        Phase.BLUETOOTH_OFF -> stringResource(R.string.car_bluetooth_off)
        Phase.WAITING_FOR_CAR -> stringResource(R.string.status_waiting)
        Phase.UNSUPPORTED_CAR -> stringResource(R.string.status_unsupported)
        Phase.LIVE -> stringResource(R.string.status_live, status.car?.adapter.orEmpty())
    }
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(text, style = MaterialTheme.typography.titleMedium)
        val problem = status.problem
        if (problem != null && status.phase == Phase.CONNECTING) {
            Text(problem, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
        }
        if (status.phase == Phase.NO_PERMISSION) {
            OutlinedButton(onClick = onAllow) { Text(stringResource(R.string.permission_allow)) }
        }
    }
}

@Composable
private fun AdapterCard(status: Status, onChoose: () -> Unit, onDemo: (Boolean) -> Unit) {
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(stringResource(R.string.adapter_title), style = MaterialTheme.typography.titleMedium)
                    Text(
                        text = status.adapter ?: stringResource(R.string.adapter_none),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                OutlinedButton(onClick = onChoose) { Text(stringResource(R.string.adapter_choose)) }
            }
            HorizontalDivider()
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(stringResource(R.string.demo_title), style = MaterialTheme.typography.titleMedium)
                    Text(
                        text = stringResource(R.string.demo_text),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Switch(checked = status.demo, onCheckedChange = onDemo, modifier = Modifier.padding(start = 12.dp))
            }
        }
    }
}

@Composable
private fun ValuesCard(car: CarInfo, readings: Readings) {
    val signals = remember(car) { car.signals.entries.sortedBy { it.key.ordinal } }
    val locale = LocalLocale.current.platformLocale
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(stringResource(R.string.values_title), style = MaterialTheme.typography.titleMedium)
            for ((signal, source) in signals) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(stringResource(signal.label), Modifier.weight(1f), style = MaterialTheme.typography.bodyLarge)
                    if (source is Query && !source.standard) {
                        Text(
                            text = source.title,
                            modifier = Modifier.padding(end = 12.dp),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    val value = readings.values[signal]
                    Text(
                        text = if (value == null) "–" else String.format(locale, "%.${signal.decimals}f %s", value, signal.unit),
                        style = MaterialTheme.typography.bodyLarge,
                    )
                }
            }
            if (!car.longRequests) {
                Text(
                    text = stringResource(R.string.values_adapter_limited),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                )
            }
            if (signals.any { (_, source) -> source is Query && !source.standard }) {
                Text(
                    text = stringResource(R.string.values_unverified),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

@Composable
private fun AndroidAutoCard() {
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(stringResource(R.string.auto_title), style = MaterialTheme.typography.titleMedium)
            Text(stringResource(R.string.auto_text), style = MaterialTheme.typography.bodyMedium)
        }
    }
}

/**
 * A look around the engine control unit, for finding values the app does not know where to
 * read yet. What it finds goes out with the record of the card below.
 */
@Composable
private fun SurveyCard(obd: ObdManager, live: Boolean) {
    val state by obd.surveyState.collectAsStateWithLifecycle()
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(stringResource(R.string.survey_title), style = MaterialTheme.typography.titleMedium)
            Text(stringResource(R.string.survey_text), style = MaterialTheme.typography.bodyMedium)
            if (state.running) {
                if (state.total > 0) {
                    LinearProgressIndicator(
                        progress = { state.done.toFloat() / state.total },
                        modifier = Modifier.fillMaxWidth(),
                    )
                    Text(
                        stringResource(R.string.survey_progress, state.done, state.total),
                        style = MaterialTheme.typography.bodySmall,
                    )
                } else {
                    LinearProgressIndicator(Modifier.fillMaxWidth())
                }
            } else {
                if (state.readings > 0) {
                    Text(stringResource(R.string.survey_readings, state.readings), style = MaterialTheme.typography.bodyMedium)
                }
                state.problem?.let {
                    Text(
                        stringResource(R.string.survey_problem, it),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error,
                    )
                }
                OutlinedButton(onClick = obd::startSurvey, enabled = live) {
                    Text(stringResource(if (state.readings == 0) R.string.survey_start else R.string.survey_again))
                }
            }
        }
    }
}

/**
 * What to send when something does not work: what the car was found to give and what was said
 * between the app and the adapter.
 */
@Composable
private fun TroubleshootingCard(obd: ObdManager, car: CarInfo?) {
    val context = LocalContext.current
    val title = stringResource(R.string.report_title)
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(title, style = MaterialTheme.typography.titleMedium)
            Text(stringResource(R.string.report_text), style = MaterialTheme.typography.bodyMedium)
            if (car != null) {
                Text(
                    text = car.report.trimEnd(),
                    modifier = Modifier.horizontalScroll(rememberScrollState()),
                    style = MaterialTheme.typography.bodySmall,
                    fontFamily = FontFamily.Monospace,
                    softWrap = false,
                )
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(
                    onClick = {
                        val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                        clipboard.setPrimaryClip(ClipData.newPlainText(title, obd.diagnostics()))
                    },
                ) { Text(stringResource(R.string.report_copy)) }
                OutlinedButton(
                    onClick = {
                        val send = Intent(Intent.ACTION_SEND)
                            .setType("text/plain")
                            .putExtra(Intent.EXTRA_TEXT, obd.diagnostics())
                        context.startActivity(Intent.createChooser(send, title))
                    },
                ) { Text(stringResource(R.string.report_share)) }
            }
        }
    }
}

@Composable
private fun AdapterDialog(onChosen: (BluetoothLink.Device) -> Unit, onDismiss: () -> Unit) {
    val context = LocalContext.current
    val state = remember { BluetoothLink.state(context) }
    val devices = remember { BluetoothLink.paired(context) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.adapter_dialog_title)) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                if (devices.isEmpty()) {
                    Text(
                        stringResource(
                            if (state == BluetoothLink.State.READY) R.string.adapter_dialog_empty else R.string.adapter_dialog_off,
                        ),
                    )
                }
                for (device in devices) {
                    Column(
                        Modifier
                            .fillMaxWidth()
                            .clickable { onChosen(device) }
                            .padding(vertical = 10.dp),
                    ) {
                        Text(device.name, style = MaterialTheme.typography.bodyLarge)
                        Text(
                            text = device.address,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
        },
        confirmButton = {
            TextButton(
                onClick = {
                    context.startActivity(Intent(Settings.ACTION_BLUETOOTH_SETTINGS))
                    onDismiss()
                },
            ) { Text(stringResource(R.string.bluetooth_settings)) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel)) } },
    )
}
