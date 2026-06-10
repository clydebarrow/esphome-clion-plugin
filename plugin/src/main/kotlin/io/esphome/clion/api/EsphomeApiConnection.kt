package io.esphome.clion.api

import com.intellij.openapi.diagnostic.thisLogger
import io.esphome.clion.api.proto.ApiMessages
import io.esphome.clion.api.transport.FrameHelper
import io.esphome.clion.api.transport.NoiseFrameHelper
import io.esphome.clion.api.transport.PlaintextFrameHelper
import java.net.InetSocketAddress
import java.net.Socket
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
        fun onState(state: io.esphome.clion.api.proto.ApiState)
        fun onError(message: String)
        fun onClosed()
    }

    private val running = AtomicBoolean(false)
    @Volatile private var socket: Socket? = null

    fun start() {
        if (!running.compareAndSet(false, true)) return
        Thread({ run() }, "esphome-api-$host").apply { isDaemon = true }.start()
    }

    fun stop() {
        running.set(false)
        runCatching { socket?.close() }
    }

    private fun run() {
        try {
            listener.onStatus("Connecting to $host:$port…")
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

            readLoop(helper)
        } catch (e: Exception) {
            if (running.get()) listener.onError(e.message ?: e.javaClass.simpleName)
            thisLogger().info("ESPHome API connection to $host:$port ended: ${e.message}")
        } finally {
            running.set(false)
            runCatching { socket?.close() }
            listener.onClosed()
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
                frame.type == ApiMessages.LIST_ENTITIES_DONE -> listener.onStatus("Subscribed — live")
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
    }
}
