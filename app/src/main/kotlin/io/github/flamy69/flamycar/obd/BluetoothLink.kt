package io.github.flamy69.flamycar.obd

import android.Manifest
import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothSocket
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import java.io.Closeable
import java.io.IOException
import java.util.UUID

/**
 * The Bluetooth side of the adapter. ELM327 adapters with classic Bluetooth appear as a serial
 * port: they are paired in the settings of the phone (the PIN is usually 1234 or 0000) and
 * then opened here like a socket.
 */
object BluetoothLink {
    class Device(val address: String, val name: String)

    enum class State { READY, NO_PERMISSION, OFF, MISSING }

    /** The serial port profile, the same for every device that offers one. */
    private val SERIAL_PORT = UUID.fromString("00001101-0000-1000-8000-00805F9B34FB")

    private val LIKELY_NAMES = listOf("OBD", "ELM", "LINK", "VGATE", "ICAR", "KONNWEI", "SCAN")

    fun state(context: Context): State {
        val adapter = adapter(context) ?: return State.MISSING
        if (!permitted(context)) return State.NO_PERMISSION
        return if (adapter.isEnabled) State.READY else State.OFF
    }

    /** Since Android 12 the user has to allow the app to use paired devices. */
    fun permitted(context: Context): Boolean =
        Build.VERSION.SDK_INT < Build.VERSION_CODES.S ||
            context.checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT) == PackageManager.PERMISSION_GRANTED

    /** The devices paired with the phone, those that look like an OBD adapter first. */
    @SuppressLint("MissingPermission")
    fun paired(context: Context): List<Device> {
        if (state(context) != State.READY) return emptyList()
        val devices = try {
            adapter(context)?.bondedDevices.orEmpty().map { Device(it.address, it.name ?: it.address) }
        } catch (_: SecurityException) {
            emptyList()
        }
        return devices.sortedWith(
            compareBy<Device> { device -> LIKELY_NAMES.none { device.name.uppercase().contains(it) } }
                .thenBy { it.name.lowercase() },
        )
    }

    /**
     * Opens the serial port of a paired adapter. This blocks for as long as the adapter takes
     * to answer, several seconds when it is out of reach; [onSocket] hands out what to close to
     * give up earlier.
     */
    @SuppressLint("MissingPermission")
    @Throws(IOException::class)
    fun connect(context: Context, address: String, onSocket: (Closeable) -> Unit): ElmTransport {
        val adapter = adapter(context) ?: throw IOException("This phone has no Bluetooth")
        try {
            val device = adapter.getRemoteDevice(address)
            // Cheap adapters are picky about how they are approached. The proper way first;
            // then the link without encryption, which many of them insist on; then, for those
            // that do not even announce their serial port properly, straight to the channel
            // it is always on.
            val ways = listOf<(BluetoothDevice) -> BluetoothSocket>(
                { it.createRfcommSocketToServiceRecord(SERIAL_PORT) },
                { it.createInsecureRfcommSocketToServiceRecord(SERIAL_PORT) },
                { firstChannel(it) },
            )
            var failure: IOException? = null
            for (way in ways) {
                val socket = try {
                    way(device)
                } catch (e: IOException) {
                    failure = failure ?: e
                    continue
                }
                onSocket(socket)
                try {
                    socket.connect()
                    return StreamTransport(socket.inputStream, socket.outputStream, socket)
                } catch (e: IOException) {
                    failure = failure ?: e
                    socket.close()
                }
            }
            throw failure ?: IOException("Cannot connect to $address")
        } catch (e: SecurityException) {
            throw IOException("Not allowed to use Bluetooth", e)
        } catch (e: IllegalArgumentException) {
            throw IOException("$address is not a Bluetooth address", e)
        }
    }

    /**
     * A socket to RFCOMM channel 1 without asking the device which channel its serial port is
     * on. Android has no public way of saying that, so the method is looked up by name.
     */
    private fun firstChannel(device: BluetoothDevice): BluetoothSocket =
        try {
            device.javaClass.getMethod("createRfcommSocket", Int::class.javaPrimitiveType).invoke(device, 1) as BluetoothSocket
        } catch (e: ReflectiveOperationException) {
            throw IOException("This phone cannot open a channel directly", e)
        } catch (e: ClassCastException) {
            throw IOException("This phone cannot open a channel directly", e)
        }

    private fun adapter(context: Context): BluetoothAdapter? =
        context.getSystemService(BluetoothManager::class.java)?.adapter
}
