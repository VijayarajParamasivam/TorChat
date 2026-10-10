package com.torchat.core.port

import java.io.Closeable
import java.io.InputStream
import java.io.OutputStream

/**
 * A bidirectional byte stream, and the only thing the wire layer knows about the
 * outside world.
 *
 * The transport speaks the same protocol over this that it would over a LAN
 * cable. It is never told whether the bytes are travelling through a Tor circuit
 * or a loopback socket, and it does not trust the carrier to keep any promises —
 * a relay carrying these bytes sees exactly what a LAN eavesdropper sees.
 *
 * **[input] must be buffered by whoever creates the stream**, and must be the
 * same buffer any preceding negotiation read through. Tor routinely delivers the
 * peer's first protocol frame in the same TCP segment as the SOCKS reply; a
 * dialler that negotiates through one buffer and then hands over a freshly
 * wrapped one discards the peer's `hello` and the link waits forever for a frame
 * that already arrived (§6.2).
 */
interface Stream : Closeable {
    val input: InputStream
    val output: OutputStream

    /** Best-effort TCP keepalive. Ignored by transports that have no socket. */
    fun setKeepAlive(millis: Int) {}
}

/** Accepts inbound streams. Bound to loopback, always (§6.3). */
interface Listener : Closeable {
    val port: Int
}
