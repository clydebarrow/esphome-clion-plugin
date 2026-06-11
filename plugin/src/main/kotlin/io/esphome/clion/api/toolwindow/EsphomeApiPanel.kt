package io.esphome.clion.api.toolwindow

import com.intellij.openapi.Disposable
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.ModalityState
import com.intellij.openapi.fileEditor.FileEditorManager
import com.intellij.openapi.fileEditor.FileEditorManagerEvent
import com.intellij.openapi.fileEditor.FileEditorManagerListener
import com.intellij.openapi.project.DumbService
import com.intellij.openapi.project.IndexNotReadyException
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBScrollPane
import com.intellij.ui.components.JBTextField
import com.intellij.util.concurrency.AppExecutorUtil
import com.intellij.util.ui.JBUI
import io.esphome.clion.api.EsphomeApiConnection
import io.esphome.clion.api.EsphomeApiTarget
import io.esphome.clion.api.proto.ApiEntity
import io.esphome.clion.api.proto.ApiState
import java.awt.BorderLayout
import java.awt.GridBagConstraints
import java.awt.GridBagLayout
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.TimeUnit
import javax.swing.JButton
import javax.swing.JPanel

/**
 * The **ESPHome Device** tool window: a Connect/Disconnect button, host and key
 * fields (pre-filled from the active config), a status line, and a live view of
 * the device's entities (with controls for the actionable ones). Opening it via
 * the context-menu action auto-connects when a host is known, and an unexpected
 * drop (e.g. the device reboots) reconnects on a backing-off schedule.
 */
class EsphomeApiPanel(private val project: Project) :
    JPanel(BorderLayout()), Disposable {

    private val entityView = EntityListView()
    private val pendingEntities = mutableListOf<ApiEntity>()
    private val hostField = JBTextField(22)
    private val keyField = JBTextField(44).apply { emptyText.text = "blank = plaintext; base64 key for encrypted api:" }
    private val connectButton = JButton("Disconnect").apply {
        // Freeze the width at the wider label so toggling Connect/Disconnect
        // doesn't resize the button and shift the row.
        preferredSize = preferredSize
        text = "Connect"
    }
    private val statusLabel = JBLabel(" ")

    @Volatile private var disposed = false
    @Volatile private var connection: EsphomeApiConnection? = null
    private var target: EsphomeApiTarget.Target? = null

    /**
     * The user wants a live connection: set by Connect (and auto-connect),
     * cleared by Disconnect / dispose. While true, an unexpected drop (e.g. the
     * device reboots) schedules a reconnect; a user Disconnect does not.
     */
    @Volatile private var active = false
    private var reconnectAttempts = 0
    @Volatile private var reconnectFuture: ScheduledFuture<*>? = null

    /**
     * Identifies the current connection generation. Each [connect] bumps it; a
     * prior connection that is still shutting down keeps its old token, so its
     * late callbacks are dropped instead of corrupting the new connection's
     * state (e.g. its `onClosed` nulling out the live [connection]). EDT-only.
     */
    private var connectionToken = 0L

    init {
        val controls = JPanel(GridBagLayout()).apply {
            add(connectButton, gbc(0, 0))
            add(JBLabel("Host:"), gbc(1, 0))
            add(hostField, gbc(2, 0))
            add(JBLabel("Key:"), gbc(1, 1))
            add(keyField, gbc(2, 1))
            // Trailing glue: keep the fields at their natural width, left-aligned,
            // instead of stretching the whole tool window wide.
            add(JPanel().apply { isOpaque = false }, gbc(3, 0, grow = true, height = 2))
        }
        val header = JPanel(BorderLayout()).apply {
            border = JBUI.Borders.empty(6)
            add(controls, BorderLayout.NORTH)
            add(statusLabel.apply { border = JBUI.Borders.emptyTop(6) }, BorderLayout.SOUTH)
        }
        entityView.commandSink = CommandSink { type, payload -> connection?.send(type, payload) }
        add(header, BorderLayout.NORTH)
        add(JBScrollPane(entityView), BorderLayout.CENTER)

        connectButton.addActionListener { if (active) stopConnection() else connect() }
        hostField.addActionListener { if (!active) connect() }

        project.messageBus.connect(this).subscribe(
            FileEditorManagerListener.FILE_EDITOR_MANAGER,
            object : FileEditorManagerListener {
                override fun selectionChanged(event: FileEditorManagerEvent) = refreshTarget()
            },
        )
        refreshTarget()
    }

    /** Re-derive the default target from the active editor (when not connected). */
    private fun refreshTarget() {
        if (disposed || DumbService.getInstance(project).isDumb) return
        val file = FileEditorManager.getInstance(project).selectedFiles.firstOrNull() ?: return
        val derived = try {
            EsphomeApiTarget.forFile(project, file)
        } catch (_: IndexNotReadyException) {
            return
        }
        target = derived
        // Don't touch the fields/status while connected or mid-reconnect.
        if (connection != null || active) return
        if (hostField.text.isBlank() && derived.host != null) hostField.text = hostPort(derived.host, derived.port)
        if (keyField.text.isBlank() && derived.encryptionKey != null) keyField.text = derived.encryptionKey
        updateReadyStatus(derived)
    }

    /**
     * Pre-fill host (and key, when the config has one) from [file] on explicit
     * request — the context-menu action — overriding the current host. Leaves an
     * already-pasted key in place when the config has none, and never touches a
     * live connection (the fields apply on the next Connect).
     */
    fun prefill(file: VirtualFile) {
        if (disposed) return
        val derived = try {
            EsphomeApiTarget.forFile(project, file)
        } catch (_: IndexNotReadyException) {
            return
        }
        target = derived
        derived.host?.let { hostField.text = hostPort(it, derived.port) }
        derived.encryptionKey?.let { keyField.text = it }
        if (connection == null) updateReadyStatus(derived)
        // Opened by explicit user action: if we now have a host and aren't
        // already connected, connect right away rather than making them click.
        if (!active && hostField.text.isNotBlank()) connect()
    }

    private fun updateReadyStatus(derived: EsphomeApiTarget.Target) {
        val device = derived.deviceName ?: "device"
        statusLabel.text = when {
            !derived.hasApi -> "No api: in the open config — enter host:port to connect."
            derived.encryptionKey != null || keyField.text.isNotBlank() -> "Ready — Connect to $device (encrypted)."
            else -> "Ready — Connect to $device (no key found; paste one if it's encrypted)."
        }
    }

    private fun gbc(x: Int, y: Int, grow: Boolean = false, width: Int = 1, height: Int = 1): GridBagConstraints =
        GridBagConstraints().apply {
            gridx = x; gridy = y; gridwidth = width; gridheight = height
            insets = JBUI.insets(2)
            anchor = GridBagConstraints.WEST
            if (grow) { weightx = 1.0; fill = GridBagConstraints.HORIZONTAL }
        }

    private fun connect() {
        val raw = hostField.text.trim()
        if (raw.isEmpty()) {
            statusLabel.text = "Enter a host, e.g. living-room.local or 10.0.0.5"
            return
        }
        reconnectFuture?.cancel(false)
        reconnectFuture = null
        active = true
        // New generation: a previous connection still tearing down keeps its old
        // token, so its trailing callbacks are ignored by [ui].
        val token = ++connectionToken
        val (host, port) = parseHostPort(raw)
        pendingEntities.clear()
        entityView.clear()
        val key = keyField.text.trim().ifEmpty { null }
        val conn = EsphomeApiConnection(host, port, target?.password, key, ConnectionListener(token))
        connection = conn
        connectButton.text = "Disconnect"
        conn.start()
    }

    /** User-initiated disconnect: clear intent so no reconnect is scheduled. */
    private fun stopConnection() {
        active = false
        reconnectFuture?.cancel(false)
        reconnectFuture = null
        reconnectAttempts = 0
        connectButton.text = "Connect"
        connection?.stop()
    }

    /**
     * Schedule a reconnect after an unexpected drop, backing off
     * [RECONNECT_BASE_SEC] × attempt up to [RECONNECT_MAX_SEC]. Re-armed each
     * failure; the counter resets once a connection streams entities.
     */
    private fun scheduleReconnect() {
        reconnectAttempts++
        val delaySec = (RECONNECT_BASE_SEC * reconnectAttempts).coerceAtMost(RECONNECT_MAX_SEC)
        connectButton.text = "Disconnect"
        statusLabel.text = "Disconnected — reconnecting in ${delaySec}s…"
        reconnectFuture = AppExecutorUtil.getAppScheduledExecutorService().schedule(
            { ui { if (active && connection == null) connect() } },
            delaySec.toLong(),
            TimeUnit.SECONDS,
        )
    }

    private fun parseHostPort(raw: String): Pair<String, Int> {
        val defaultPort = target?.port ?: EsphomeApiTarget.DEFAULT_PORT
        // Bracketed IPv6, e.g. [fe80::1] or [fe80::1]:6053
        if (raw.startsWith("[")) {
            val close = raw.indexOf(']')
            if (close > 1) {
                val host = raw.substring(1, close)
                val rest = raw.substring(close + 1)
                val port = if (rest.startsWith(":")) validPort(rest.substring(1)) else null
                return host to (port ?: defaultPort)
            }
        }
        // host:port only with a single colon — a bare IPv6 literal has several.
        if (raw.count { it == ':' } == 1) {
            val host = raw.substringBefore(':')
            val port = validPort(raw.substringAfter(':'))
            if (host.isNotEmpty() && port != null) return host to port
        }
        return raw to defaultPort
    }

    private fun validPort(s: String): Int? = s.toIntOrNull()?.takeIf { it in 1..65535 }

    private fun hostPort(host: String, port: Int): String =
        if (port == EsphomeApiTarget.DEFAULT_PORT) host else "$host:$port"

    /**
     * Listener bound to one connection generation ([token]). Callbacks hop to the
     * EDT and are dropped unless this is still the current generation, so a
     * superseded connection (the user reconnected before it finished closing)
     * can't mutate the live panel state.
     */
    private inner class ConnectionListener(private val token: Long) : EsphomeApiConnection.Listener {
        override fun onStatus(status: String) = ui(token) { statusLabel.text = status }
        override fun onEntity(entity: ApiEntity) = ui(token) { pendingEntities.add(entity) }
        override fun onEntitiesComplete() = ui(token) {
            reconnectAttempts = 0 // a clean stream: restart the backoff for next time
            entityView.setEntities(pendingEntities.toList())
        }
        override fun onState(state: ApiState) = ui(token) { entityView.updateState(state) }
        override fun onError(message: String) = ui(token) { statusLabel.text = "Error: $message" }
        override fun onClosed() = ui(token) {
            connection = null
            // Keep trying while the user still wants a connection (device rebooted,
            // network blip); a user Disconnect cleared `active`, so it stays closed.
            if (active && !disposed) scheduleReconnect() else connectButton.text = "Connect"
        }
    }

    /** Run [action] on the EDT only if [token] is still the current generation. */
    private inline fun ui(token: Long, crossinline action: () -> Unit) =
        ui { if (token == connectionToken) action() }

    private inline fun ui(crossinline action: () -> Unit) {
        if (disposed) return
        ApplicationManager.getApplication().invokeLater({ if (!disposed) action() }, ModalityState.any())
    }

    override fun dispose() {
        disposed = true
        active = false
        reconnectFuture?.cancel(false)
        connection?.stop()
    }

    private companion object {
        const val RECONNECT_BASE_SEC = 5
        const val RECONNECT_MAX_SEC = 30
    }
}
