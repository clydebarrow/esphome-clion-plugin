package io.esphome.clion.validation

import com.intellij.execution.ExecutionException
import com.intellij.execution.configurations.GeneralCommandLine
import com.intellij.execution.process.CapturingProcessHandler
import com.intellij.notification.NotificationGroupManager
import com.intellij.notification.NotificationType
import com.intellij.lang.annotation.AnnotationHolder
import com.intellij.lang.annotation.ExternalAnnotator
import com.intellij.lang.annotation.HighlightSeverity
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.diagnostic.thisLogger
import com.intellij.openapi.editor.Document
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.project.Project
import com.intellij.psi.PsiDocumentManager
import com.intellij.openapi.util.TextRange
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.psi.PsiFile
import com.intellij.psi.PsiManager
import io.esphome.clion.psi.EsphomeYaml
import io.esphome.clion.services.EsphomeIncludeGraph
import io.esphome.clion.settings.EsphomeSettings
import org.jetbrains.yaml.psi.YAMLFile
import java.nio.charset.StandardCharsets

/**
 * Validates ESPHome configs by running the real `esphome config <file>` and
 * mapping its reported errors onto the editor — the same ground-truth ESPHome
 * uses, rather than approximating validation from a schema.
 *
 * Runs in the [ExternalAnnotator] background phase, so the subprocess never
 * blocks the UI.
 */
class EsphomeValidationAnnotator : ExternalAnnotator<EsphomeValidationAnnotator.Info, List<EsphomeDiagnostic>>() {

    /**
     * [targetFile]/[targetPath] is the open file we annotate; [configPath] is what
     * `esphome config` actually runs on. For a device root they're the same file;
     * for an `!include`d fragment, [configPath] is the device root that pulls it
     * in (a fragment is only valid in that context), while diagnostics are still
     * filtered and mapped back to the fragment.
     */
    data class Info(
        val targetFile: VirtualFile,
        val targetPath: String,
        val configPath: String,
        val workDir: String?,
    )

    override fun collectInformation(file: PsiFile): Info? {
        if (file !is YAMLFile) return null
        val virtualFile = file.virtualFile ?: return null
        if (!virtualFile.isInLocalFileSystem) return null

        val configFile = if (EsphomeYaml.isEsphomeFile(file)) {
            virtualFile // a device root validates itself
        } else {
            // a fragment compiles only inside a device — validate the root that includes it
            includingRoot(file.project, virtualFile) ?: return null
        }
        return Info(virtualFile, virtualFile.path, configFile.path, configFile.parent?.path)
    }

    /**
     * The device root that includes [fragment], if any. Prefers a real ESPHome
     * config (top-level `esphome:`) among the topmost includers; an orphan
     * fragment (included by nothing, or no root is a device) yields null and is
     * not validated, since running it standalone would report spurious errors.
     */
    internal fun includingRoot(project: Project, fragment: VirtualFile): VirtualFile? {
        val psiManager = PsiManager.getInstance(project)
        return EsphomeIncludeGraph.getInstance(project).rootsOf(fragment)
            .asSequence()
            .filter { it != fragment }
            .filter { root -> (psiManager.findFile(root) as? YAMLFile)?.let(EsphomeYaml::isEsphomeFile) == true }
            .minByOrNull { it.path }
    }

    override fun doAnnotate(info: Info): List<EsphomeDiagnostic> {
        val executable = EsphomeSettings.getInstance().resolveExecutable()
        if (executable == null) {
            warnExecutableMissingOnce()
            return emptyList()
        }
        flushUnsavedChanges(info.targetFile)
        val command = GeneralCommandLine(executable, "config", info.configPath)
            .withCharset(StandardCharsets.UTF_8)
        info.workDir?.let(command::withWorkDirectory)

        return try {
            val output = CapturingProcessHandler(command).runProcess(TIMEOUT_MS)
            // Filter to the open file: errors in other files of the graph (incl.
            // the root) surface when those files are themselves open.
            val diagnostics =
                if (output.exitCode == 0) {
                    emptyList()
                } else {
                    EsphomeConfigOutputParser.parse(
                        output.stdout + "\n" + output.stderr,
                        info.targetPath,
                        includeTopLevelErrors = info.configPath == info.targetPath,
                    )
                }
            thisLogger().info(
                "esphome config ${info.configPath} (for ${info.targetPath}): " +
                    "exit=${output.exitCode}, ${diagnostics.size} diagnostic(s)",
            )
            diagnostics
        } catch (e: ExecutionException) {
            thisLogger().warn("Could not run '$executable config'", e)
            emptyList()
        }
    }

    /**
     * `esphome config` reads the file from disk, so flush unsaved editor changes
     * before validating. Otherwise a just-corrected error stays highlighted:
     * validation re-runs against stale on-disk content and re-reports the problem.
     *
     * Runs from the background [doAnnotate] phase (no read lock held), so it can
     * legally hop to the EDT to save — unlike [collectInformation], which executes
     * inside a read action where `invokeAndWait` would deadlock.
     */
    private fun flushUnsavedChanges(file: VirtualFile) {
        val fileDocumentManager = FileDocumentManager.getInstance()
        ApplicationManager.getApplication().invokeAndWait {
            fileDocumentManager.getDocument(file)
                ?.takeIf(fileDocumentManager::isDocumentUnsaved)
                ?.let(fileDocumentManager::saveDocument)
        }
    }

    /** Surfaces the most common failure mode (esphome not on PATH) once per session. */
    private fun warnExecutableMissingOnce() {
        thisLogger().warn("ESPHome executable not found; set it in Settings | Tools | ESPHome.")
        if (!executableWarningShown.compareAndSet(false, true)) return
        NotificationGroupManager.getInstance()
            .getNotificationGroup("ESPHome")
            .createNotification(
                "ESPHome executable not found",
                "Config validation is disabled. Set the esphome path in Settings | Tools | ESPHome, or add it to PATH.",
                NotificationType.WARNING,
            )
            .notify(null)
    }

    override fun apply(file: PsiFile, annotationResult: List<EsphomeDiagnostic>, holder: AnnotationHolder) {
        if (annotationResult.isEmpty()) return
        val document = PsiDocumentManager.getInstance(file.project).getDocument(file) ?: return
        if (document.lineCount == 0) return

        for (diagnostic in annotationResult) {
            val range = rangeFor(document, diagnostic)
            holder.newAnnotation(HighlightSeverity.ERROR, diagnostic.message)
                .range(range)
                .create()
        }
    }

    /**
     * Choose the range to underline:
     *  - file-level errors (no source line) locate their token, e.g. a bad
     *    platform value, and fall back to the first line;
     *  - "missing"/"required" errors keep the component anchor (the echoed key is
     *    just nearby context — the real problem is an *absent* key);
     *  - otherwise refine to the offending key's line.
     */
    private fun rangeFor(document: Document, diagnostic: EsphomeDiagnostic): TextRange {
        if (diagnostic.anchorLine <= 0) {
            diagnostic.searchToken?.let { findToken(document, it) }?.let { return it }
            return trimmedLineRange(document, 0)
        }
        val anchor = (diagnostic.anchorLine - 1).coerceIn(0, document.lineCount - 1)
        val line = diagnostic.offendingKey
            ?.takeUnless { ABSENCE_ERROR.containsMatchIn(diagnostic.message) }
            ?.let { findKeyLine(document, anchor, it) }
            ?: anchor
        return trimmedLineRange(document, line)
    }

    /** Range of the first literal occurrence of [token] in the document. */
    private fun findToken(document: Document, token: String): TextRange? {
        val index = document.charsSequence.indexOf(token)
        return if (index < 0) null else TextRange(index, index + token.length)
    }

    private fun findKeyLine(document: Document, fromLine: Int, key: String): Int? {
        val needle = Regex("""^\s*${Regex.escape(key)}:""")
        val text = document.charsSequence
        for (line in fromLine until document.lineCount) {
            val slice = text.subSequence(document.getLineStartOffset(line), document.getLineEndOffset(line))
            if (needle.containsMatchIn(slice)) return line
        }
        return null
    }

    private fun trimmedLineRange(document: Document, line: Int): TextRange {
        val lineStart = document.getLineStartOffset(line)
        val lineEnd = document.getLineEndOffset(line)
        val text = document.charsSequence
        var start = lineStart
        var end = lineEnd
        while (start < end && text[start].isWhitespace()) start++
        while (end > start && text[end - 1].isWhitespace()) end--
        return if (start == end) TextRange(lineStart, lineEnd) else TextRange(start, end)
    }

    companion object {
        private const val TIMEOUT_MS = 30_000
        private val executableWarningShown = java.util.concurrent.atomic.AtomicBoolean(false)
        private val ABSENCE_ERROR =
            Regex("""(?i)\b(missing|required|must include|must specify|must provide|not provided)\b""")
    }
}
