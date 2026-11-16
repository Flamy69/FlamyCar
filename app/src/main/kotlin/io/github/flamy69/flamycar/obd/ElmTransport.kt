package io.github.flamy69.flamycar.obd

import java.io.Closeable
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread

/** The serial line to an ELM327 adapter: bytes out, bytes in. */
interface ElmTransport : Closeable {
    @Throws(IOException::class)
    fun write(data: ByteArray)

    /** The next byte from the adapter, or [TIMEOUT] when none arrived in time. */
    @Throws(IOException::class)
    fun read(timeoutMs: Long): Int

    companion object {
        const val TIMEOUT = -1
    }
}

/**
 * A transport over a pair of streams, such as those of a Bluetooth socket. A stream cannot be
 * read with a timeout, so a thread of its own reads it into a queue; an adapter that stops
 * answering then shows up as a timeout instead of blocking forever.
 */
class StreamTransport(
    private val input: InputStream,
    private val output: OutputStream,
    private val connection: Closeable,
) : ElmTransport {
    private val received = LinkedBlockingQueue<Int>()

    init {
        thread(name = "elm-reader", isDaemon = true) {
            try {
                val buffer = ByteArray(256)
                while (true) {
                    val count = input.read(buffer)
                    if (count < 0) break
                    for (i in 0 until count) received.put(buffer[i].toInt() and 0xFF)
                }
            } catch (_: IOException) {
                // The connection is gone; the reader of the queue is told below.
            }
            received.put(CLOSED)
        }
    }

    override fun write(data: ByteArray) {
        output.write(data)
        output.flush()
    }

    override fun read(timeoutMs: Long): Int {
        val value = received.poll(timeoutMs.coerceAtLeast(0), TimeUnit.MILLISECONDS)
            ?: return ElmTransport.TIMEOUT
        if (value == CLOSED) {
            received.put(CLOSED)
            throw IOException("The connection to the adapter was closed")
        }
        return value
    }

    override fun close() {
        try {
            connection.close()
        } catch (_: IOException) {
            // Closing twice, or closing a connection that already failed, is fine.
        }
    }

    private companion object {
        const val CLOSED = -2
    }
}
