package io.esphome.clion.secrets

import com.intellij.openapi.Disposable
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.service
import com.intellij.openapi.editor.Editor
import com.intellij.openapi.editor.EditorFactory
import com.intellij.openapi.editor.event.CaretEvent
import com.intellij.openapi.editor.event.CaretListener
import com.intellij.openapi.editor.event.DocumentEvent
import com.intellij.openapi.editor.event.DocumentListener
import com.intellij.openapi.editor.event.EditorFactoryEvent
import com.intellij.openapi.editor.event.EditorFactoryListener
import com.intellij.openapi.editor.ex.FoldingModelEx
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.startup.ProjectActivity
import com.intellij.openapi.util.Disposer
import com.intellij.openapi.util.Key
import com.intellij.util.Alarm

/**
 * Masks the values in an open `secrets.yaml`, revealing only the value(s) on the
 * caret's line — so a shoulder-surfer or a screen-share doesn't see every key.
 *
 * Each value is hidden behind a collapsed fold region whose placeholder is a row
 * of dots; the caret's line is expanded so it stays editable. Masking is purely
 * visual (the file on disk and `!secret` resolution are unchanged) and applies to
 * any file named `secrets.yaml`/`secrets.yml`.
 */
private val MASKER_KEY = Key.create<EsphomeSecretMasker>("esphome.secret.masker")

class EsphomeSecretMaskingStartup : ProjectActivity {

    override suspend fun execute(project: Project) {
        val factory = EditorFactory.getInstance()
        val parent = EsphomeSecretMaskingService.getInstance(project)
        factory.addEditorFactoryListener(
            object : EditorFactoryListener {
                override fun editorCreated(event: EditorFactoryEvent) = attachIfSecrets(event.editor, project)
                override fun editorReleased(event: EditorFactoryEvent) = detach(event.editor)
            },
            parent,
        )
        // Files reopened at startup already have editors — mask those too.
        ApplicationManager.getApplication().invokeLater {
            factory.allEditors.forEach { attachIfSecrets(it, project) }
        }
    }

    private companion object {
        fun attachIfSecrets(editor: Editor, project: Project) {
            if (editor.project != project || editor.getUserData(MASKER_KEY) != null) return
            val name = FileDocumentManager.getInstance().getFile(editor.document)?.name ?: return
            if (!name.equals("secrets.yaml", true) && !name.equals("secrets.yml", true)) return
            EsphomeSecretMasker(editor).also {
                editor.putUserData(MASKER_KEY, it)
                // Own the masker from the project service too, so a plugin unload (or
                // project close) disposes it even while the editor stays open — no
                // dangling listeners holding the plugin classloader (dynamic unload).
                Disposer.register(EsphomeSecretMaskingService.getInstance(project), it)
                it.start()
            }
        }

        fun detach(editor: Editor) {
            editor.getUserData(MASKER_KEY)?.let {
                editor.putUserData(MASKER_KEY, null)
                if (!Disposer.isDisposed(it)) Disposer.dispose(it)
            }
        }
    }
}

/** Project-scoped parent disposable for the app-level editor-factory listener. */
@Service(Service.Level.PROJECT)
class EsphomeSecretMaskingService : Disposable {
    override fun dispose() = Unit

    companion object {
        fun getInstance(project: Project): EsphomeSecretMaskingService = project.service()
    }
}

/** Owns one editor's secret fold regions and keeps them in sync with caret/edits. */
private class EsphomeSecretMasker(private val editor: Editor) : Disposable {

    private val document = editor.document
    private val folding = editor.foldingModel
    private val ourRegions = mutableListOf<com.intellij.openapi.editor.FoldRegion>()
    private val rebuildAlarm = Alarm(Alarm.ThreadToUse.SWING_THREAD, this)

    fun start() {
        rebuild()
        editor.caretModel.addCaretListener(
            object : CaretListener {
                override fun caretPositionChanged(event: CaretEvent) = applyReveal()
            },
            this,
        )
        document.addDocumentListener(
            object : DocumentListener {
                // Edits move offsets (handled by the fold range markers) but can also
                // add/remove secret lines, so rebuild once typing settles.
                override fun documentChanged(event: DocumentEvent) {
                    rebuildAlarm.cancelAllRequests()
                    rebuildAlarm.addRequest(::rebuild, REBUILD_DELAY_MS)
                }
            },
            this,
        )
    }

    /** Re-create a collapsed fold over every value; the caret line(s) stay revealed. */
    private fun rebuild() {
        if (editor.isDisposed) return
        val caretLines = caretLines()
        folding.runBatchFoldingOperation {
            ourRegions.forEach { if (it.isValid) folding.removeFoldRegion(it) }
            ourRegions.clear()
            for (line in 0 until document.lineCount) {
                val lineStart = document.getLineStartOffset(line)
                val text = document.getText(com.intellij.openapi.util.TextRange(lineStart, document.getLineEndOffset(line)))
                val cols = EsphomeSecretLines.valueColumns(text) ?: continue
                val region = (folding as FoldingModelEx)
                    .addFoldRegion(lineStart + cols.first, lineStart + cols.last + 1, MASK) ?: continue
                region.isExpanded = line in caretLines
                ourRegions.add(region)
            }
        }
    }

    /** Expand the fold(s) on the caret line(s), collapse the rest — no rebuild. */
    private fun applyReveal() {
        if (editor.isDisposed || ourRegions.isEmpty()) return
        val caretLines = caretLines()
        folding.runBatchFoldingOperation {
            ourRegions.forEach { region ->
                if (region.isValid) region.isExpanded = document.getLineNumber(region.startOffset) in caretLines
            }
        }
    }

    private fun caretLines(): Set<Int> =
        editor.caretModel.allCarets
            .map { document.getLineNumber(it.offset.coerceIn(0, document.textLength)) }
            .toSet()

    // Caret/document listeners and the rebuild alarm are parented to this masker,
    // so Disposer removes them. Also drop our fold regions and the editor's marker
    // so a plugin reload can re-attach cleanly (unmasking the values as we go).
    override fun dispose() {
        if (!editor.isDisposed) {
            editor.putUserData(MASKER_KEY, null)
            folding.runBatchFoldingOperation {
                ourRegions.forEach { if (it.isValid) folding.removeFoldRegion(it) }
            }
        }
        ourRegions.clear()
    }

    private companion object {
        const val MASK = "••••••" // ••••••, fixed length so it doesn't leak the real length
        const val REBUILD_DELAY_MS = 250
    }
}
