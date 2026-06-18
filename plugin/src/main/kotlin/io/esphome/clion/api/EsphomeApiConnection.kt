package io.esphome.clion.api

import com.intellij.openapi.diagnostic.thisLogger
import com.intellij.util.concurrency.AppExecutorUtil
import io.esphome.clion.api.proto.ApiMessages
import io.esphome.clion.api.transport.FrameHelper
import io.esphome.clion.api.transport.NoiseFrameHelper
import io.esphome.clion.api.transport.PlaintextFrameHelper
import java.net.InetSocketAddress
import java.net.Socket
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/**
 * A live connection to one ESPHome device over the native API. Runs the whole
 * exchange — connect, (encryption handshake), Hello, optional legacy auth,
 * `ListEntities`, `SubscribeStates`, then stream state updates — on a single
 * background thread, pushing results to [listener]. Callbacks arrive on that
 * thread; the UI marshals them to the EDT.
 */
class EsphomeApiConnection(
    private val host: String,
    private val port: Int,
    private val password: String?,
    private val encryptionKey: String?,
    private val listener: Listener,
) {
    interface Listener {
        fun onStatus(status: String)
        fun onEntity(entity: io.esphome.clion.api.proto.ApiEntity)
        /** All `ListEntities*` received (before states start streaming). */
        fun onEntitiesComplete()
        fun onState(state: io.esphome.clion.api.proto.ApiState)
        fun onError(message: String)
        fun onClosed()
    }

    private val running = AtomicBoolean(false)
    @Volatile private var thread: Thread? = null
    @Volatile private var socket: Socket? = null
    /** The transport, exposed for sending commands only once streaming. */
    @Volatile private var frameHelper: FrameHelper? = null

    /** Wall-clock of the last frame received; the watchdog uses it to spot a dead link. */
    @Volatile private var lastActivityAt = 0L
    @Volatile private var watchdog: ScheduledFuture<*>? = null
    /** Set when the watchdog closes a dead link, so the read failure isn't reported as an error. */
    @Volatile private var closedAsDead = false
    /** Guards [Listener.onClosed] to exactly once, whichever path closes the link first. */
    private val closedNotified = AtomicBoolean(false)

    fun start() {
        if (!running.compareAndSet(false, true)) return
        thread = Thread({ run() }, "esphome-api-$host").apply { isDaemon = true; start() }
    }

    fun stop() {
        running.set(false)
        // Cancel the recurring watchdog now (not just in run()'s finally) so a
        // stopped connection doesn't keep a task — and the plugin classloader —
        // pinned in the shared scheduled executor while the reader thread winds down.
        watchdog?.cancel(false)
        watchdog = null
        runCatching { socket?.close() }
    }

    /**
     * Stop and wait up to [timeoutMs] for the reader thread to actually finish.
     * Closing the socket unblocks its read at once, so this returns in
     * milliseconds; the cap just guards a stuck close. Used on dispose / plugin
     * unload so no lingering thread keeps the plugin's classloader pinned (which
     * would force an IDE restart to reload the plugin).
     */
    fun stopAndAwait(timeoutMs: Long) {
        stop()
        runCatching { thread?.takeIf { it !== Thread.currentThread() }?.join(timeoutMs) }
    }

    /**
     * Send a command message to the device (e.g. a switch toggle). Off the EDT,
     * and a no-op until the connection is streaming. The frame helper serializes
     * the write against the reader thread's ping responses.
     */
    fun send(type: Int, payload: ByteArray) {
        val helper = frameHelper ?: return
        AppExecutorUtil.getAppExecutorService().execute {
            runCatching { helper.writeMessage(type, payload) }
                .onFailure { thisLogger().warn("Failed to send command $type to $host:$port", it) }
        }
    }

    private fun run() {
        try {
            val encrypted = !encryptionKey.isNullOrBlank()
            thisLogger().info("ESPHome API connecting to $host:$port (${if (encrypted) "noise/encrypted" else "plaintext"})")
            listener.onStatus("Connecting to $host:$port (${if (encrypted) "encrypted" else "plaintext"})…")
            val sock = Socket().also { socket = it }
            sock.connect(InetSocketAddress(host, port), CONNECT_TIMEOUT_MS)
            sock.tcpNoDelay = true
            val helper = createFrameHelper(sock)
            helper.handshake()

            helper.writeMessage(ApiMessages.HELLO_REQUEST, ApiMessages.helloRequest(CLIENT_INFO))
            handshakeFlow(helper)
            if (!running.get()) return

            helper.writeMessage(ApiMessages.DEVICE_INFO_REQUEST, ApiMessages.EMPTY)
            helper.writeMessage(ApiMessages.LIST_ENTITIES_REQUEST, ApiMessages.EMPTY)
            helper.writeMessage(ApiMessages.SUBSCRIBE_STATES_REQUEST, ApiMessages.EMPTY)

            frameHelper = helper // streaming: commands may now be sent
            lastActivityAt = System.currentTimeMillis()
            startWatchdog()
            readLoop(helper)
        } catch (e: Exception) {
            // A watchdog-forced close is an expected teardown, not an error to surface;
            // the panel reconnects via onClosed.
            if (closedAsDead) {
                thisLogger().info("ESPHome API connection to $host:$port closed (device unresponsive)")
            } else {
                thisLogger().warn("ESPHome API connection to $host:$port failed", e)
                if (running.get()) listener.onError(describeError(e))
            }
        } finally {
            running.set(false)
            watchdog?.cancel(false)
            watchdog = null
            frameHelper = null
            runCatching { socket?.close() }
            notifyClosed()
        }
    }

    /** Notify the listener the link is gone, at most once across all close paths. */
    private fun notifyClosed() {
        if (closedNotified.compareAndSet(false, true)) listener.onClosed()
    }

    /**
     * A silent device (powered off, Wi-Fi drop) sends no FIN, so [readLoop]'s
     * blocking read never returns and the link looks alive but frozen. This
     * watchdog pings on an interval and, when no frame has arrived for
     * [DEAD_TIMEOUT_MS], closes the socket — unblocking the read with an
     * exception so the panel's reconnect kicks in. Closing (rather than a socket
     * read timeout) avoids leaving a half-read frame in the stream.
     */
    private fun startWatchdog() {
        watchdog = AppExecutorUtil.getAppScheduledExecutorService().scheduleWithFixedDelay(
            ::checkLiveness, PING_INTERVAL_MS, PING_INTERVAL_MS, TimeUnit.MILLISECONDS,
        )
    }

    private fun checkLiveness() {
        if (!running.get()) return
        val idleMs = System.currentTimeMillis() - lastActivityAt
        if (idleMs > DEAD_TIMEOUT_MS) {
            thisLogger().info("ESPHome device $host:$port silent for ${idleMs}ms; closing to reconnect")
            closedAsDead = true
            listener.onStatus("Device not responding — reconnecting…")
            // Force a full teardown, don't just close the socket. If the reader
            // thread is wedged — e.g. a ping write blocked on the dead socket is
            // holding the write lock the reader needs, and close() doesn't unblock
            // a blocked write on this platform — then run()'s finally never fires
            // and the panel never reconnects, leaving us stuck on this message.
            // stop() cancels this watchdog (so it stops re-asserting) and closes
            // the socket; notifyClosed() drives the reconnect regardless.
            stop()
            notifyClosed()
        } else {
            // Elicit a PingResponse; a live device answers in milliseconds, a dead
            // one stays silent and trips the timeout above on a later tick.
            send(ApiMessages.PING_REQUEST, ApiMessages.EMPTY)
        }
    }

    /** Noise when the device's api: has an encryption key, plaintext otherwise. */
    private fun createFrameHelper(sock: Socket): FrameHelper {
        val input = sock.getInputStream().buffered()
        val output = sock.getOutputStream()
        return if (encryptionKey.isNullOrBlank()) {
            PlaintextFrameHelper(input, output)
        } else {
            NoiseFrameHelper(input, output, encryptionKey)
        }
    }

    /** Turn a low-level failure into an actionable, never-blank status message. */
    private fun describeError(e: Throwable): String = when (e) {
        is java.net.UnknownHostException ->
            "Host not found: $host — is mDNS resolving '$host'? Try the device's IP address."
        is java.net.SocketTimeoutException -> "Timed out connecting to $host:$port."
        is java.net.ConnectException ->
            "Can't reach $host:$port (${e.message ?: "connection refused"}) — is the device on and api: enabled?"
        is java.io.EOFException ->
            "Device closed the connection during the handshake. It likely requires API encryption " +
                "(set api: encryption: key:), the key is wrong, or api: is disabled."
        is java.net.SocketException -> "Connection lost: ${e.message ?: "reset by the device"}."
        is SecurityException -> e.message ?: "Authentication failed."
        else -> e.message?.takeIf { it.isNotBlank() } ?: "${e.javaClass.simpleName} — see idea.log for details."
    }

    /** Read HelloResponse, then run legacy password auth only if a password is set. */
    private fun handshakeFlow(helper: FrameHelper) {
        val hello = helper.readMessage()
        if (hello.type == ApiMessages.HELLO_RESPONSE) {
            val name = ApiMessages.decodeHelloResponse(hello.payload)
            listener.onStatus(if (name.isNotEmpty()) "Connected to $name" else "Connected")
        }
        if (!password.isNullOrEmpty()) {
            helper.writeMessage(ApiMessages.AUTH_REQUEST, ApiMessages.authRequest(password))
            val resp = helper.readMessage()
            if (resp.type == ApiMessages.AUTH_RESPONSE && ApiMessages.decodeAuthInvalid(resp.payload)) {
                throw SecurityException("Invalid API password")
            }
        }
    }

    private fun readLoop(helper: FrameHelper) {
        while (running.get()) {
            val frame = helper.readMessage()
            lastActivityAt = System.currentTimeMillis() // any frame (incl. a PingResponse) proves liveness
            when {
                frame.type == ApiMessages.PING_REQUEST ->
                    helper.writeMessage(ApiMessages.PING_RESPONSE, ApiMessages.EMPTY)
                frame.type == ApiMessages.DISCONNECT_REQUEST -> {
                    runCatching { helper.writeMessage(ApiMessages.DISCONNECT_RESPONSE, ApiMessages.EMPTY) }
                    return
                }
                frame.type == ApiMessages.DEVICE_INFO_RESPONSE -> {
                    val info = ApiMessages.decodeDeviceInfo(frame.payload)
                    listener.onStatus("Connected to ${info.name} (ESPHome ${info.esphomeVersion})")
                }
                frame.type == ApiMessages.LIST_ENTITIES_DONE -> {
                    listener.onEntitiesComplete()
                    listener.onStatus("Subscribed — live")
                }
                ApiMessages.isEntityList(frame.type) ->
                    listener.onEntity(ApiMessages.decodeEntity(frame.type, frame.payload))
                ApiMessages.isStateResponse(frame.type) ->
                    ApiMessages.decodeState(frame.type, frame.payload)?.let(listener::onState)
            }
        }
    }

    companion object {
        private const val CONNECT_TIMEOUT_MS = 5_000
        private const val CLIENT_INFO = "ESPHome plugin for JetBrains IDEs"
        /** How often the watchdog pings / checks for traffic once streaming. */
        private const val PING_INTERVAL_MS = 10_000L
        /** No frame for this long ⇒ treat the device as gone and reconnect. */
        private const val DEAD_TIMEOUT_MS = 25_000L
    }
}
