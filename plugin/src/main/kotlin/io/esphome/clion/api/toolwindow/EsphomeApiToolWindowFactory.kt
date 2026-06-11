package io.esphome.clion.api.toolwindow

import com.intellij.openapi.project.DumbAware
import com.intellij.openapi.project.Project
import com.intellij.openapi.wm.ToolWindow
import com.intellij.openapi.wm.ToolWindowFactory
import com.intellij.ui.content.ContentFactory

/** Registers the **ESPHome Device** tool window hosting [EsphomeApiPanel]. */
class EsphomeApiToolWindowFactory : ToolWindowFactory, DumbAware {
    override fun createToolWindowContent(project: Project, toolWindow: ToolWindow) {
        val panel = EsphomeApiPanel(project)
        val content = ContentFactory.getInstance().createContent(panel, "", false)
        content.setDisposer(panel) // stop the connection when the content/project closes
        toolWindow.contentManager.addContent(content)
    }

    companion object {
        /** Must match the `<toolWindow id>` in plugin.xml. */
        const val ID = "ESPHome Device"
    }
}
