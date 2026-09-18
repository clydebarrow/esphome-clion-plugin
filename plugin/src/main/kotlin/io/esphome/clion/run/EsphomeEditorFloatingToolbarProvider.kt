package io.esphome.clion.run

import com.intellij.openapi.Disposable
import com.intellij.openapi.actionSystem.CommonDataKeys
import com.intellij.openapi.actionSystem.DataContext
import com.intellij.openapi.application.runReadActionBlocking
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

    override fun isApplicable(dataContext: DataContext): Boolean =
        // Both the PSI_FILE lookup and isStandaloneConfig touch PSI, so the whole
        // thing runs under a read action — isApplicable may be called off the EDT.
        runReadActionBlocking {
            val file = dataContext.getData(CommonDataKeys.PSI_FILE) as? YAMLFile
            file != null && EsphomeYaml.isStandaloneConfig(file)
        }

    /**
     * Show it right away. A non-auto-hideable toolbar isn't revealed by the
     * platform's mouse-motion watcher (that path is only wired up when
     * [autoHideable] is true), so the provider must call [scheduleShow] itself —
     * otherwise the toolbar is created but never made visible. With auto-hide off,
     * nothing hides it again, so it stays on screen.
     *
     * Overrides the 3-arg `register(DataContext, FloatingToolbarComponent,
     * Disposable)`, not the 2-arg convenience overload `AbstractFloatingToolbarProvider`
     * carried in older platform versions as a source-compat shim (delegated to by
     * the 3-arg one there) — that shim was dropped in the 2026.2 (build 262)
     * platform, so a 2-arg override silently stopped overriding anything: it
     * compiled and ran with no error on either version, just never got called on
     * 262+, since the platform always invokes the 3-arg method. Confirmed by
     * `javap`-diffing `AbstractFloatingToolbarProvider` between the 2026.1.1 and
     * 2026.2 platform jars — 2026.1.1 has both overloads, 2026.2 only the 3-arg
     * one. The 3-arg signature exists on both, so overriding it is the
     * version-independent fix.
     */
    override fun register(dataContext: DataContext, component: FloatingToolbarComponent, parentDisposable: Disposable) {
        component.scheduleShow()
    }

    companion object {
        const val GROUP_ID = "io.esphome.clion.EditorFloatingToolbar"
    }
}
