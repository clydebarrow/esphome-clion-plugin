package io.esphome.clion.secrets

import com.intellij.codeInsight.folding.impl.FoldingUtil
import com.intellij.openapi.Disposable
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.runReadAction
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
import com.intellij.openapi.fileEditor.FileEditorManager
import com.intellij.openapi.fileEditor.FileEditorManagerListener
import com.intellij.openapi.project.Project
import com.intellij.openapi.startup.ProjectActivity
import com.intellij.openapi.util.Disposer
import com.intellij.openapi.util.Key
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.util.Alarm
import io.esphome.clion.references.EsphomeSecret

/**
 * Masks the values in an open `secrets.yaml`, revealing only the value(s) on the
 * caret's line — so a shoulder-surfer or a screen-share doesn't see every key.
 *
 * Each value is hidden behind a collapsed fold region whose placeholder is a row
 * of dots; the caret's line is expanded so it stays editable. Masking is purely
 * visual (the file on disk and `!secret` resolution are unchanged) and applies to
 * any file named `secrets.yaml`/`secrets.yml`, any `.yaml`/`.yml` file that
 * declares itself a secrets file with a top-level `is_secrets_file: true` marker
 * key, or a file directly `<<: !include`d by one of the above (ESPHome's way of
 * splitting secrets across multiple files) — see [EsphomeSecret.isIncludedBySecretsFile].
 * The marker key itself is never masked, nor is a leading Jekyll-style front-matter
 * block (`---` … `---`) it might sit in — see [EsphomeSecretLines.frontMatterLineRange].
 * Marker-based status is rechecked on every edit, so toggling it takes effect
 * immediately — no reopen needed. Inclusion-based status is checked once per
 * editor (it depends on another file's content, which this listener doesn't
 * track, so editing *this* file's own content can't change it).
 */
private val MASKER_KEY = Key.create<EsphomeSecretMasker>("esphome.secret.masker")
private val INCLUDED_KEY = Key.create<Boolean>("esphome.secret.masker.included")

class EsphomeSecretMaskingStartup : ProjectActivity {

    override suspend fun execute(project: Project) {
        val factory = EditorFactory.getInstance()
        val parent = EsphomeSecretMaskingService.getInstance(project)
        factory.addEditorFactoryListener(
            object : EditorFactoryListener {
                override fun editorCreated(event: EditorFactoryEvent) = reclassify(event.editor, project)
                override fun editorReleased(event: EditorFactoryEvent) = detach(event.editor)
            },
            parent,
        )
        // The `is_secrets_file:` marker can be added, removed, or edited at any
        // time, so a file's secrets-file status isn't fixed at open time — recheck
        // every editor of a changed document on every edit, not just on open.
        factory.eventMulticaster.addDocumentListener(
            object : DocumentListener {
                override fun documentChanged(event: DocumentEvent) {
                    factory.getEditors(event.document, project).forEach { reclassify(it, project) }
                }
            },
            parent,
        )
        // Files reopened at startup already have editors — classify those too.
        // This alone isn't reliable: `ProjectActivity`s can run *before* session-
        // restored tabs' editors exist yet, so this sweep can find nothing for
        // them, and no later `editorCreated` ever fires since those editors were
        // never (re)created after this listener was registered (confirmed by
        // logging a real report: a restored `secrets.yaml` tab got no
        // reclassify call at all this way — see the `fileOpened` subscription
        // below for the reliable catch).
        ApplicationManager.getApplication().invokeLater {
            factory.allEditors.forEach { reclassify(it, project) }
        }
        // The reliable catch for session-restored tabs: `fileOpened` fires for
        // every file that becomes visible, including ones restored from the
        // previous session (unlike `editorCreated`, which only fires for editors
        // created *after* this listener is registered).
        project.messageBus.connect(parent).subscribe(
            FileEditorManagerListener.FILE_EDITOR_MANAGER,
            object : FileEditorManagerListener {
                override fun fileOpened(source: FileEditorManager, file: VirtualFile) {
                    val document = FileDocumentManager.getInstance().getDocument(file) ?: return
                    factory.getEditors(document, project).forEach { reclassify(it, project) }
                }
            },
        )
    }

    private companion object {
        /** Attach or detach the masker on [editor] to match its current secrets-file status. */
        fun reclassify(editor: Editor, project: Project) {
            if (editor.project != project) return
            val virtualFile = FileDocumentManager.getInstance().getFile(editor.document) ?: return
            val name = virtualFile.name
            if (!name.endsWith(".yaml", true) && !name.endsWith(".yml", true)) return
            val byName = name.equals("secrets.yaml", true) || name.equals("secrets.yml", true)
            val byMarker = EsphomeSecretLines.declaresSecretsFile(editor.document.immutableCharSequence)
            // Cached per editor: this depends on another file's `<<: !include`
            // line, which document/editor-factory events on *this* file never
            // report, so there's no point re-checking it on every keystroke here.
            val byInclusion = if (byName || byMarker) {
                false
            } else {
                editor.getUserData(INCLUDED_KEY) ?: runReadAction {
                    EsphomeSecret.isIncludedBySecretsFile(project, virtualFile)
                }.also { editor.putUserData(INCLUDED_KEY, it) }
            }
            val classified = byName || byMarker || byInclusion
            val attached = editor.getUserData(MASKER_KEY) != null
            if (classified == attached) return
            if (classified) {
                EsphomeSecretMasker(editor).also {
                    editor.putUserData(MASKER_KEY, it)
                    // Own the masker from the project service too, so a plugin unload (or
                    // project close) disposes it even while the editor stays open — no
                    // dangling listeners holding the plugin classloader (dynamic unload).
                    Disposer.register(EsphomeSecretMaskingService.getInstance(project), it)
                    it.start()
                }
            } else {
                detach(editor)
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
        val lines = (0 until document.lineCount).map { line ->
            document.getText(com.intellij.openapi.util.TextRange(document.getLineStartOffset(line), document.getLineEndOffset(line)))
        }
        val frontMatter = EsphomeSecretLines.frontMatterLineRange(lines)
        folding.runBatchFoldingOperation {
            ourRegions.forEach { if (it.isValid) folding.removeFoldRegion(it) }
            ourRegions.clear()
            for (line in 0 until document.lineCount) {
                if (frontMatter != null && line in frontMatter) continue
                val lineStart = document.getLineStartOffset(line)
                val cols = EsphomeSecretLines.valueColumns(lines[line]) ?: continue
                val start = lineStart + cols.first
                val end = lineStart + cols.last + 1
                // The IDE persists fold-region state per file across restarts,
                // independent of us — on a session-restored tab it recreates a
                // region at this exact range *before* this rebuild runs (whether
                // expanded or collapsed), so addFoldRegion returns null (a
                // region already exists there). Adopt it instead of losing
                // track of it: without this, a restored tab loses all its
                // masking (nothing left in `ourRegions` to re-collapse) until
                // closed and reopened.
                val region = FoldingUtil.findFoldRegion(editor, start, end)
                    ?: (folding as FoldingModelEx).addFoldRegion(start, end, MASK)
                    ?: continue
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
