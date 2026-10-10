package com.torchat.core.net

import com.torchat.core.port.Listener
import com.torchat.core.port.Stream
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.InputStream
import java.io.OutputStream
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket

/**
 * A [Stream] over a TCP socket.
 *
 * The input side is buffered here, once, and that buffer is what every later
 * reader sees — including any SOCKS negotiation performed before the stream is
 * handed to the wire layer. See the note on [Stream.input] for why re-wrapping
 * it later loses the peer's first frame.
 */
class SocketStream(private val socket: Socket) : Stream {

    override val input: InputStream = BufferedInputStream(socket.getInputStream())
    override val output: OutputStream = BufferedOutputStream(socket.getOutputStream())

    override fun setKeepAlive(millis: Int) {
        runCatching {
            socket.keepAlive = true
            // Java exposes no portable way to set the keepalive interval; the OS
            // default applies. Enabling it at all is what matters, since a dead
            // circuit that never sends a FIN is otherwise only noticed by the
            // application-level ping.
        }
    }

    override fun close() {
        runCatching { socket.close() }
    }
}

/**
 * A TCP listener bound to **loopback with an ephemeral port** (§6.3).
 *
 * Loopback is deliberate. The only thing that should ever connect here is our
 * own Tor, handing over a rendezvous stream. Binding a real interface would
 * accept direct connections and give away the address the onion service exists
 * to keep private.
 *
 * Nothing is recorded about where an inbound peer came from. Over Tor the remote
 * address is always 127.0.0.1 — there is nothing true to learn, and asking would
 * be pointless as well as unwanted.
 */
class LoopbackListener(private val server: ServerSocket) : Listener {

    override val port: Int get() = server.localPort

    /** Blocks until a peer connects. Throws once [close] has been called. */
    fun accept(): Stream = SocketStream(server.accept())

    override fun close() {
        runCatching { server.close() }
    }

    companion object {
        fun bind(port: Int = 0): LoopbackListener =
            LoopbackListener(ServerSocket(port, BACKLOG, InetAddress.getByName("127.0.0.1")))

        private const val BACKLOG = 8
    }
}
