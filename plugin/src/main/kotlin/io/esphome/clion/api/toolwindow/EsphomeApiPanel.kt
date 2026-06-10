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
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBScrollPane
import com.intellij.ui.components.JBTextField
import com.intellij.ui.table.JBTable
import com.intellij.util.ui.JBUI
import io.esphome.clion.api.EsphomeApiConnection
import io.esphome.clion.api.EsphomeApiTarget
import io.esphome.clion.api.proto.ApiEntity
import io.esphome.clion.api.proto.ApiState
import java.awt.BorderLayout
import javax.swing.JButton
import javax.swing.JPanel

/**
 * The **ESPHome Device** tool window: a host:port field (pre-filled from the
 * active config), Connect/Disconnect, a status line, and a live table of the
 * device's entities. Read-only — it subscribes to states and displays them.
 */
class EsphomeApiPanel(private val project: Project) :
    JPanel(BorderLayout()), Disposable, EsphomeApiConnection.Listener {

    private val model = EntityTableModel()
    private val hostField = JBTextField()
    private val connectButton = JButton("Connect")
    private val statusLabel = JBLabel(" ")

    @Volatile private var disposed = false
    @Volatile private var connection: EsphomeApiConnection? = null
    private var target: EsphomeApiTarget.Target? = null

    init {
        val controls = JPanel(BorderLayout(JBUI.scale(6), 0)).apply {
            add(JBLabel("Device:"), BorderLayout.WEST)
            add(hostField, BorderLayout.CENTER)
            add(connectButton, BorderLayout.EAST)
        }
        val header = JPanel(BorderLayout()).apply {
            border = JBUI.Borders.empty(6)
            add(controls, BorderLayout.NORTH)
            add(statusLabel.apply { border = JBUI.Borders.emptyTop(6) }, BorderLayout.SOUTH)
        }
        val table = JBTable(model).apply { fillsViewportHeight = true }
        add(header, BorderLayout.NORTH)
        add(JBScrollPane(table), BorderLayout.CENTER)

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
        statusLabel.text = when {
            !derived.hasApi -> "No api: in the open config — enter host:port to connect."
            derived.encryptionKey != null -> "Encrypted api: detected (encrypted support coming soon)."
            else -> "Ready — Connect to ${derived.deviceName ?: "device"}."
        }
    }

    private fun connect() {
        val raw = hostField.text.trim()
        if (raw.isEmpty()) {
            statusLabel.text = "Enter a host, e.g. living-room.local or 10.0.0.5"
            return
        }
        val (host, port) = parseHostPort(raw)
        model.clear()
        val conn = EsphomeApiConnection(host, port, target?.password, target?.encryptionKey, this)
        connection = conn
        connectButton.text = "Disconnect"
        conn.start()
    }

    private fun parseHostPort(raw: String): Pair<String, Int> {
        val colon = raw.lastIndexOf(':')
        if (colon > 0) {
            val maybePort = raw.substring(colon + 1).toIntOrNull()
            if (maybePort != null) return raw.substring(0, colon) to maybePort
        }
        return raw to (target?.port ?: EsphomeApiTarget.DEFAULT_PORT)
    }

    private fun hostPort(host: String, port: Int): String =
        if (port == EsphomeApiTarget.DEFAULT_PORT) host else "$host:$port"

    // --- EsphomeApiConnection.Listener (background thread) → EDT ---
    override fun onStatus(status: String) = ui { statusLabel.text = status }
    override fun onEntity(entity: ApiEntity) = ui { model.putEntity(entity) }
    override fun onState(state: ApiState) = ui { model.applyState(state) }
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
