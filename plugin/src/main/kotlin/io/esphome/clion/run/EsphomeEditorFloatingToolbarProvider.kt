package io.esphome.clion.run

import com.intellij.openapi.Disposable
import com.intellij.openapi.actionSystem.CommonDataKeys
import com.intellij.openapi.actionSystem.DataContext
import com.intellij.openapi.application.runReadAction
import com.intellij.openapi.editor.toolbar.floating.AbstractFloatingToolbarProvider
import com.intellij.openapi.editor.toolbar.floating.FloatingToolbarComponent
import io.esphome.clion.psi.EsphomeYaml
import org.jetbrains.yaml.psi.YAMLFile

/**
 * Surfaces the ESPHome actions (Run / Logs / Open Device Window) as a small
 * always-visible floating toolbar in the top-right of the editor, so the common
 * commands live in one obvious place instead of only the context menu. Shown only
 * on a standalone ESPHome config; the buttons come from the
 * `io.esphome.clion.EditorFloatingToolbar` action group in `plugin.xml`.
 *
 * Extends the platform abstract class (not the `FloatingToolbarProvider`
 * interface directly) to avoid Kotlin default-method bridges into the platform.
 */
class EsphomeEditorFloatingToolbarProvider : AbstractFloatingToolbarProvider(GROUP_ID) {

    /** Stay visible at all times (don't fade out when idle), so the actions are always at hand. */
    override val autoHideable: Boolean = false

    override fun isApplicable(dataContext: DataContext): Boolean {
        val file = dataContext.getData(CommonDataKeys.PSI_FILE) as? YAMLFile ?: return false
        return runReadAction { EsphomeYaml.isStandaloneConfig(file) }
    }

    /**
     * Show it right away. A non-auto-hideable toolbar isn't revealed by the
     * platform's mouse-motion watcher (that path is only wired up when
     * [autoHideable] is true), so the provider must call [scheduleShow] itself —
     * otherwise the toolbar is created but never made visible. With auto-hide off,
     * nothing hides it again, so it stays on screen.
     */
    override fun register(component: FloatingToolbarComponent, parentDisposable: Disposable) {
        component.scheduleShow()
    }

    companion object {
        const val GROUP_ID = "io.esphome.clion.EditorFloatingToolbar"
    }
}
