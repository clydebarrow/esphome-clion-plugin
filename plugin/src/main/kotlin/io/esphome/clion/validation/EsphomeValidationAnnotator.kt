package io.esphome.clion.validation

import com.intellij.execution.ExecutionException
import com.intellij.execution.process.CapturingProcessHandler
import com.intellij.notification.NotificationGroupManager
import com.intellij.notification.NotificationType
import com.intellij.lang.annotation.AnnotationHolder
import com.intellij.lang.annotation.ExternalAnnotator
import com.intellij.lang.annotation.HighlightSeverity
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.diagnostic.thisLogger
import com.intellij.openapi.editor.Document
import com.intellij.openapi.progress.ProgressManager
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.psi.PsiDocumentManager
import com.intellij.openapi.util.TextRange
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.psi.PsiFile
import io.esphome.clion.run.EsphomeBackend
import io.esphome.clion.run.EsphomeCommandLines
import io.esphome.clion.run.EsphomeExecutables
import io.esphome.clion.services.EsphomeConfigRoots
import io.esphome.clion.settings.EsphomeSettings
import org.jetbrains.yaml.psi.YAMLFile
import java.io.File

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
        val configFile: VirtualFile,
        val configPath: String,
    )

    override fun collectInformation(file: PsiFile): Info? {
        if (file !is YAMLFile) return null
        val virtualFile = file.virtualFile ?: return null
        if (!virtualFile.isInLocalFileSystem) return null

        // Validate against the device root this file belongs to: itself when it is
        // top-level, otherwise the including config — so a shared package (which has
        // an `esphome:` block but is `!include`d) is validated through a real device
        // and its `${substitutions}` resolve, rather than erroring standalone. The
        // ambiguous "which device" is resolved by the selected run configuration.
        // Null = orphan fragment → skip.
        val configFile = EsphomeConfigRoots.effectiveRoot(file.project, virtualFile) ?: return null
        return Info(virtualFile, virtualFile.path, configFile, configFile.path)
    }

    override fun doAnnotate(info: Info): List<EsphomeDiagnostic> {
        val settings = EsphomeSettings.getInstance()
        // Validate with the same backend (and esphome) a run would use, so the
        // editor's verdict matches what the user actually builds with. Follows the
        // default backend from the Run-configuration defaults.
        val backend = EsphomeBackend.of(settings.state.defaultBackend)
        val executable = EsphomeExecutables.forBackend(backend)
        // LOCAL/VENV need a host esphome; DOCKER needs the docker binary (it
        // provides esphome in the image). If the backend can't run, say why once
        // rather than failing silently with empty annotations.
        val unavailable = when (backend) {
            EsphomeBackend.DOCKER -> EsphomeCommandLines.findDocker() == null
            else -> executable == null
        }
        if (unavailable) {
            warnBackendUnavailableOnce(backend)
            return emptyList()
        }
        // `esphome config` reads from disk: flush the open file and, for an
        // included fragment, the device root it actually runs against.
        flushUnsavedChanges(info.targetFile)
        if (info.configFile != info.targetFile) flushUnsavedChanges(info.configFile)
        val command = EsphomeCommandLines.buildConfig(
            backend = backend,
            configFile = File(info.configPath),
            executable = executable,
            dockerImage = settings.state.dockerImage ?: EsphomeSettings.DEFAULT_DOCKER_IMAGE,
            dockerExecutable = EsphomeCommandLines.resolveDocker(),
            cacheDir = settings.takeIf { it.state.dockerCacheMount }?.dockerCacheDir(),
        )

        return try {
            // Run under the daemon's progress indicator so the subprocess is
            // *cancellable*: when validation is superseded — or the plugin is
            // unloaded — the process is destroyed promptly instead of blocking a
            // thread (and the pending unload write action) for up to TIMEOUT_MS.
            val handler = CapturingProcessHandler(command)
            val indicator = ProgressManager.getInstance().progressIndicator
            val output = if (indicator != null) {
                handler.runProcessWithProgressIndicator(indicator, TIMEOUT_MS)
            } else {
                handler.runProcess(TIMEOUT_MS)
            }
            val combined = output.stdout + "\n" + output.stderr
            // Errors come only from a failed run (a valid config's echoed dump
            // would be mis-parsed); warnings appear either way, so parse them
            // separately and always. Filter to the open file: errors in other
            // graph files surface when those files are themselves open.
            val errors =
                if (output.exitCode == 0) {
                    emptyList()
                } else {
                    EsphomeConfigOutputParser.parse(
                        combined,
                        info.targetPath,
                        includeTopLevelErrors = info.configPath == info.targetPath,
                    )
                }
            val diagnostics = errors + EsphomeConfigOutputParser.parseWarnings(combined, info.targetPath)
            thisLogger().info(
                "esphome config ${info.configPath} (for ${info.targetPath}): " +
                    "exit=${output.exitCode}, ${diagnostics.size} diagnostic(s)",
            )
            diagnostics
        } catch (e: ExecutionException) {
            thisLogger().warn("Could not run '${command.commandLineString}'", e)
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
     *
     * Uses `saveDocumentAsIs`, not `saveDocument`: the latter runs the IDE's
     * on-save processors (strip trailing spaces, remove trailing blank lines,
     * ensure a final newline), which would silently reformat the user's file as a
     * side effect of validating it — e.g. deleting a blank line just typed at the
     * end of the file and yanking the caret back. Validation should persist the
     * content verbatim and leave formatting to the user's own saves.
     */
    private fun flushUnsavedChanges(file: VirtualFile) {
        val application = ApplicationManager.getApplication()
        // Saving needs the EDT/write lock, which can't be acquired from within a
        // read action. The real [doAnnotate] phase holds no read lock, so this
        // runs; a context that does (e.g. the test harness running passes under a
        // read action) is skipped rather than tripping the deadlock guard.
        if (application.isReadAccessAllowed) return
        val fileDocumentManager = FileDocumentManager.getInstance()
        application.invokeAndWait {
            fileDocumentManager.getDocument(file)
                ?.takeIf(fileDocumentManager::isDocumentUnsaved)
                ?.let(fileDocumentManager::saveDocumentAsIs)
        }
    }

    /**
     * Explains, once per session, why the chosen validation backend can't run —
     * with a title and remedy specific to the backend (missing esphome,
     * un-provisioned venv, or no docker) so the action needed is obvious.
     */
    private fun warnBackendUnavailableOnce(backend: EsphomeBackend) {
        val (title, detail) = when (backend) {
            EsphomeBackend.VENV ->
                "ESPHome venv not set up" to
                    "Run \"Set up / update venv\" in Settings | Tools | ESPHome, or change the default backend."
            EsphomeBackend.DOCKER ->
                "Docker not found" to
                    "Install Docker (or start Docker Desktop), or change the default backend in Settings | Tools | ESPHome."
            else ->
                "ESPHome executable not found" to
                    "Set the esphome path in Settings | Tools | ESPHome, or add it to PATH."
        }
        thisLogger().warn("$title; config validation disabled for the $backend backend.")
        if (!executableWarningShown.compareAndSet(false, true)) return
        NotificationGroupManager.getInstance()
            .getNotificationGroup("ESPHome")
            .createNotification(title, "Config validation is disabled. $detail", NotificationType.WARNING)
            .notify(null)
    }

    override fun apply(file: PsiFile, annotationResult: List<EsphomeDiagnostic>, holder: AnnotationHolder) {
        if (annotationResult.isEmpty()) return
        val document = PsiDocumentManager.getInstance(file.project).getDocument(file) ?: return
        if (document.lineCount == 0) return

        for (diagnostic in annotationResult) {
            val range = rangeFor(document, diagnostic) ?: continue
            val severity = if (diagnostic.severity == EsphomeSeverity.WARNING) {
                HighlightSeverity.WARNING
            } else {
                HighlightSeverity.ERROR
            }
            holder.newAnnotation(severity, diagnostic.message)
                .range(range)
                .create()
        }
    }

    /**
     * Choose the range to underline, or null to skip:
     *  - file-level errors (no source line) locate their token, e.g. a bad
     *    platform value, and fall back to the first line;
     *  - a warning is anchored only at its token — if it isn't in this file, the
     *    warning is skipped (it belongs to wherever the token lives);
     *  - "missing"/"required" errors keep the component anchor (the echoed key is
     *    just nearby context — the real problem is an *absent* key);
     *  - otherwise refine to the offending key's line.
     */
    private fun rangeFor(document: Document, diagnostic: EsphomeDiagnostic): TextRange? {
        if (diagnostic.anchorLine <= 0) {
            // A "Platform not found" error: anchor on the `- platform: <value>`
            // declaration, not the first stray occurrence of the token.
            diagnostic.platformValue?.let { findPlatformLine(document, it) }?.let { return it }
            diagnostic.searchToken?.let { findToken(document, it) }?.let { return it }
            return if (diagnostic.severity == EsphomeSeverity.WARNING) null else trimmedLineRange(document, 0)
        }
        val anchor = (diagnostic.anchorLine - 1).coerceIn(0, document.lineCount - 1)
        val line = diagnostic.offendingKey
            ?.takeUnless { ABSENCE_ERROR.containsMatchIn(diagnostic.message) }
            ?.let { findOffendingLine(document, anchor, it, diagnostic.offendingValue) }
            ?: anchor
        return trimmedLineRange(document, line)
    }

    /** Range of the first *whole-word* occurrence of [token] in the document. */
    private fun findToken(document: Document, token: String): TextRange? {
        val index = wholeWordIndex(document.charsSequence, token)
        return if (index < 0) null else TextRange(index, index + token.length)
    }

    /**
     * Range of the `<value>` token on a `platform: <value>` line, anchoring a
     * "Platform not found" error on the actual platform declaration instead of an
     * earlier stray occurrence of the token (e.g. in `external_components:`).
     */
    private fun findPlatformLine(document: Document, value: String): TextRange? =
        platformValueRange(document.charsSequence, value)?.let { TextRange(it.first, it.last + 1) }

    /**
     * Locate the offending source line at or after [fromLine]. Prefer the exact
     * `key: value` pair — disambiguating a generic key (`id:`) that repeats in
     * the block. When that isn't present (ESPHome expanded a shorthand, e.g.
     * `component.update: m` is dumped as `id: m`), fall back to the line whose
     * *value* is that id token (the `component.update: m` line). Finally fall
     * back to the first line with the bare key — or null if none.
     */
    private fun findOffendingLine(document: Document, fromLine: Int, key: String, value: String?): Int? {
        val escapedKey = Regex.escape(key)
        if (!value.isNullOrEmpty()) {
            val escapedValue = Regex.escape(value)
            // `key: value` (tolerating quotes ESPHome may strip/add around the value).
            matchLine(document, fromLine, Regex("""^\s*$escapedKey:\s*["']?$escapedValue["']?\s*$"""))?.let { return it }
            // The value as some key's whole value — finds the shorthand the dump expanded.
            if (value.matches(ID_TOKEN)) {
                matchLine(document, fromLine, Regex(""":\s*$escapedValue\s*$"""))?.let { return it }
            }
        }
        return matchLine(document, fromLine, Regex("""^\s*$escapedKey:"""))
    }

    private fun matchLine(document: Document, fromLine: Int, needle: Regex): Int? {
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
        /** An id token, so the value-as-id fallback only fires on identifier values. */
        private val ID_TOKEN = Regex("""[A-Za-z_][A-Za-z0-9_]*""")

        /**
         * Index of the first occurrence of [token] in [text] not bordered by an
         * identifier char (letter/digit/`_`), or -1. Whole-word so a pin like
         * `GPIO3` doesn't match inside `GPIO38` (nor `sensor` inside `my_sensor`).
         */
        fun wholeWordIndex(text: CharSequence, token: String): Int {
            if (token.isEmpty()) return -1
            var from = 0
            while (true) {
                val index = text.indexOf(token, from)
                if (index < 0) return -1
                val borderedBefore = index > 0 && isWordChar(text[index - 1])
                val after = index + token.length
                val borderedAfter = after < text.length && isWordChar(text[after])
                if (!borderedBefore && !borderedAfter) return index
                from = index + 1
            }
        }

        private fun isWordChar(c: Char): Boolean = c.isLetterOrDigit() || c == '_'

        /**
         * Range of the `<value>` token on a `platform: <value>` line in [text],
         * tolerating the `- ` list-item prefix, quotes, and a trailing comment; or
         * null if no such line exists.
         */
        fun platformValueRange(text: CharSequence, value: String): IntRange? {
            val regex = Regex("""(?m)^\s*(?:-\s+)?platform:\s*["']?(${Regex.escape(value)})["']?\s*(?:#.*)?$""")
            return regex.find(text)?.groups?.get(1)?.range
        }
    }
}
