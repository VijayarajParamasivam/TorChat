package com.torchat.tor

import com.torchat.core.net.SocketStream
import com.torchat.core.port.Stream
import com.torchat.core.wire.DialFailure
import com.torchat.core.wire.LinkException
import java.io.DataInputStream
import java.net.InetSocketAddress
import java.net.Socket

/**
 * A minimal SOCKS5 client, just enough to ask Tor for a connection (§6.2).
 *
 * The address is sent as a **domain name**, never as a resolved IP. That is not
 * convenience: it means Tor resolves the `.onion` inside the network, and this
 * device never performs a lookup that could leak who it is trying to reach.
 * Resolving locally first would defeat the entire point.
 */
object Socks5 {

    private const val VERSION = 0x05
    private const val CMD_CONNECT = 0x01
    private const val ADDR_IPV4 = 0x01
    private const val ADDR_DOMAIN = 0x03
    private const val ADDR_IPV6 = 0x04

    private val REPLY_ERRORS = mapOf(
        1 to "general failure",
        2 to "connection not allowed",
        3 to "network unreachable",
        4 to "host unreachable — the onion service is probably offline",
        5 to "connection refused",
        6 to "TTL expired",
        7 to "command not supported",
        8 to "address type not supported",
    )

    /**
     * Open a connection through Tor's SOCKS port.
     *
     * The returned stream is the *same* object the negotiation read through.
     * Tor routinely delivers the peer's first protocol frame in the same TCP
     * segment as the SOCKS reply; wrapping the socket in a fresh buffer after
     * negotiating would discard that frame, and the link would then wait forever
     * for a `hello` that had already arrived — presenting exactly as an
     * unreachable peer.
     */
    fun connect(
        socksHost: String,
        socksPort: Int,
        host: String,
        port: Int,
        timeoutMs: Long,
    ): Stream {
        val socket = Socket()
        val stream = try {
            socket.connect(InetSocketAddress(socksHost, socksPort), timeoutMs.toInt())
            socket.soTimeout = timeoutMs.toInt()
            SocketStream(socket)
        } catch (failure: Exception) {
            runCatching { socket.close() }
            throw LinkException(
                "could not reach tor's socks port: ${failure.message}",
                DialFailure.NO_TOR,
            )
        }

        try {
            negotiate(stream, host, port)
        } catch (failure: Throwable) {
            stream.close()
            throw failure
        }

        // Back to a normal read timeout: a live link is idle most of the time,
        // and the keepalive in §7.7 is what notices a peer that has gone away.
        runCatching { socket.soTimeout = 0 }
        return stream
    }

    private fun negotiate(stream: Stream, host: String, port: Int) {
        val input = DataInputStream(stream.input)

        // Version 5, one method offered, "no authentication".
        stream.output.write(byteArrayOf(VERSION.toByte(), 0x01, 0x00))
        stream.output.flush()

        val greeting = ByteArray(2).also(input::readFully)
        if (greeting[0].toInt() != VERSION || greeting[1].toInt() != 0x00) {
            throw LinkException("tor refused the SOCKS handshake", DialFailure.OTHER)
        }

        stream.output.write(buildConnectRequest(host, port))
        stream.output.flush()

        val head = ByteArray(4).also(input::readFully) // version, status, reserved, type
        if (head[0].toInt() != VERSION) {
            throw LinkException("bad SOCKS reply", DialFailure.OTHER)
        }

        val status = head[1].toInt() and 0xFF
        if (status != 0x00) {
            val reason = REPLY_ERRORS[status] ?: "code $status"
            throw LinkException(
                "tor could not connect: $reason",
                // Code 4 is the ordinary case of a friend having the app closed.
                if (status == 4) DialFailure.UNREACHABLE else DialFailure.OTHER,
            )
        }

        // The bound address is of no use to us, but it has to be consumed to
        // leave the stream positioned at the first tunnelled byte.
        val addressBytes = when (val type = head[3].toInt() and 0xFF) {
            ADDR_IPV4 -> 4
            ADDR_IPV6 -> 16
            ADDR_DOMAIN -> input.readUnsignedByte()
            else -> throw LinkException("bad SOCKS address type $type", DialFailure.OTHER)
        }
        input.readFully(ByteArray(addressBytes + 2)) // address plus the bound port
    }

    /** The CONNECT request for a hostname. Pure; throws if the name cannot fit. */
    fun buildConnectRequest(host: String, port: Int): ByteArray {
        val name = host.toByteArray(Charsets.UTF_8)
        if (name.size > 255) {
            throw LinkException("onion address is too long", DialFailure.OTHER)
        }

        val request = ByteArray(7 + name.size)
        request[0] = VERSION.toByte()
        request[1] = CMD_CONNECT.toByte()
        request[2] = 0x00 // reserved
        request[3] = ADDR_DOMAIN.toByte()
        request[4] = name.size.toByte()
        name.copyInto(request, 5)
        request[5 + name.size] = (port ushr 8).toByte()
        request[6 + name.size] = port.toByte()
        return request
    }
}
