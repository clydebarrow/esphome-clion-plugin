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
import com.intellij.util.ui.JBUI
import io.esphome.clion.api.EsphomeApiConnection
import io.esphome.clion.api.EsphomeApiTarget
import io.esphome.clion.api.proto.ApiEntity
import io.esphome.clion.api.proto.ApiState
import java.awt.BorderLayout
import java.awt.GridBagConstraints
import java.awt.GridBagLayout
import javax.swing.JButton
import javax.swing.JPanel

/**
 * The **ESPHome Device** tool window: a host:port field (pre-filled from the
 * active config), Connect/Disconnect, a status line, and a live table of the
 * device's entities. Read-only — it subscribes to states and displays them.
 */
class EsphomeApiPanel(private val project: Project) :
    JPanel(BorderLayout()), Disposable, EsphomeApiConnection.Listener {

    private val entityView = EntityListView()
    private val pendingEntities = mutableListOf<ApiEntity>()
    private val hostField = JBTextField()
    private val keyField = JBTextField().apply { emptyText.text = "blank = plaintext; base64 key for encrypted api:" }
    private val connectButton = JButton("Connect")
    private val statusLabel = JBLabel(" ")

    @Volatile private var disposed = false
    @Volatile private var connection: EsphomeApiConnection? = null
    private var target: EsphomeApiTarget.Target? = null

    init {
        val controls = JPanel(GridBagLayout()).apply {
            add(JBLabel("Host:"), gbc(0, 0))
            add(hostField, gbc(1, 0, grow = true))
            add(connectButton, gbc(2, 0))
            add(JBLabel("Key:"), gbc(0, 1))
            add(keyField, gbc(1, 1, grow = true, width = 2))
        }
        val header = JPanel(BorderLayout()).apply {
            border = JBUI.Borders.empty(6)
            add(controls, BorderLayout.NORTH)
            add(statusLabel.apply { border = JBUI.Borders.emptyTop(6) }, BorderLayout.SOUTH)
        }
        entityView.commandSink = CommandSink { type, payload -> connection?.send(type, payload) }
        add(header, BorderLayout.NORTH)
        add(JBScrollPane(entityView), BorderLayout.CENTER)

        connectButton.addActionListener { if (connection == null) connect() else connection?.stop() }
        hostField.addActionListener { if (connection == null) connect() }

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
        if (connection != null) return
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
    }

    private fun updateReadyStatus(derived: EsphomeApiTarget.Target) {
        val device = derived.deviceName ?: "device"
        statusLabel.text = when {
            !derived.hasApi -> "No api: in the open config — enter host:port to connect."
            derived.encryptionKey != null || keyField.text.isNotBlank() -> "Ready — Connect to $device (encrypted)."
            else -> "Ready — Connect to $device (no key found; paste one if it's encrypted)."
        }
    }

    private fun gbc(x: Int, y: Int, grow: Boolean = false, width: Int = 1): GridBagConstraints =
        GridBagConstraints().apply {
            gridx = x; gridy = y; gridwidth = width
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
        val (host, port) = parseHostPort(raw)
        pendingEntities.clear()
        entityView.clear()
        val key = keyField.text.trim().ifEmpty { null }
        val conn = EsphomeApiConnection(host, port, target?.password, key, this)
        connection = conn
        connectButton.text = "Disconnect"
        conn.start()
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

    // --- EsphomeApiConnection.Listener (background thread) → EDT ---
    override fun onStatus(status: String) = ui { statusLabel.text = status }
    override fun onEntity(entity: ApiEntity) = ui { pendingEntities.add(entity) }
    override fun onEntitiesComplete() = ui { entityView.setEntities(pendingEntities.toList()) }
    override fun onState(state: ApiState) = ui { entityView.updateState(state) }
    override fun onError(message: String) = ui { statusLabel.text = "Error: $message" }
    override fun onClosed() = ui {
        connection = null
        connectButton.text = "Connect"
    }

    private inline fun ui(crossinline action: () -> Unit) {
        if (disposed) return
        ApplicationManager.getApplication().invokeLater({ if (!disposed) action() }, ModalityState.any())
    }

    override fun dispose() {
        disposed = true
        connection?.stop()
    }
}
