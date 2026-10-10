package io.esphome.clion.validation

import java.io.File

/** One validation problem reported by `esphome config <file>`. */
data class EsphomeDiagnostic(
    /** File the error was reported against, as printed by ESPHome. */
    val file: String,
    /** 1-based line of the component the error belongs to; 0 = file-level / unknown. */
    val anchorLine: Int,
    /** Human-readable error message. */
    val message: String,
    /** The offending YAML key, when ESPHome echoed one — used to refine the line. */
    val offendingKey: String?,
    /**
     * The value ESPHome echoed beside [offendingKey], when any — disambiguates a
     * generic key that repeats in the block (e.g. two `id:` lines) and helps
     * locate the source line when ESPHome expanded a shorthand (`x.update: m`
     * dumps as `id: m`, whose value `m` still appears in the source).
     */
    val offendingValue: String? = null,
    /** A literal token to locate in the file when there is no key/line (e.g. a bad platform value). */
    val searchToken: String? = null,
    /**
     * For a "Platform not found: '<domain>.<platform>'" error, the `<platform>`
     * name to locate specifically as a `platform:` value (`- platform: x`) — not
     * just the first occurrence of the bare token, which may appear earlier (e.g.
     * in an `external_components:` list).
     */
    val platformValue: String? = null,
    /**
     * The block's top-level YAML key (e.g. `esphome` from a `sensor.dht` path,
     * that's `sensor`), when [anchorLine] is 0 because ESPHome couldn't resolve a
     * source line for the block at all (dumped as `<path>: None`). Lets the
     * annotator anchor its file-wide offending-key search at the block's own
     * section instead of the top of the file, where a generic key/value (like
     * `name:`) could otherwise collide with an unrelated earlier occurrence.
     */
    val domain: String? = null,
    /** Highlight severity: errors fail the build; warnings are advisory. */
    val severity: EsphomeSeverity = EsphomeSeverity.ERROR,
    /**
     * The nearest enclosing key from ESPHome's echoed dump (e.g.
     * `binary_sensor.template.publish` for an action's `id:` parameter), when
     * resolvable. A bare `key: value` pair can textually match both the actual
     * offender and an unrelated declaration of the same id elsewhere in the file
     * (e.g. an id-reference error's `id: my_id` vs. `my_id`'s own `id:`
     * declaration) — this lets the annotator prefer the match nested under the
     * same enclosing key ESPHome echoed, instead of just the first occurrence.
     */
    val parentKey: String? = null,
    /**
     * 1-based source column, from `--error-format line` / `ESPHOME_ERROR_FORMAT=line`
     * output (see [EsphomeConfigOutputParser.parseLineFormat]). Null for the
     * classic block-dump format, where ESPHome gives no column — just a line to
     * search within for the offending key.
     */
    val column: Int? = null,
    /**
     * A bare `key:` to search for on [anchorLine] specifically, overriding
     * [column] — set only for the message shapes where ESPHome's own column is
     * known to anchor on the *value* instead of the key (see
     * [EsphomeConfigOutputParser.registryEntryKey]'s doc for why). Unlike
     * [searchToken], this is the literal full YAML key text (e.g. `loggr.log`),
     * not a dotted name's last segment.
     */
    val keyHint: String? = null,
)

enum class EsphomeSeverity { ERROR, WARNING }

/**
 * Parses the textual output of `esphome config <file>`.
 *
 * Requests the newer `--error-format line` shape unconditionally (see
 * [EsphomeCommandLines.buildConfig]) via the `ESPHOME_ERROR_FORMAT` env var —
 * an esphome build that predates the feature just ignores an env var it never
 * reads, and prints the classic block dump instead, so [parse] detects which
 * shape actually came back ([isLineFormat]) and dispatches to [parseLineFormat]
 * or the block-dump path accordingly. One line per error (plus a `note:` line
 * on some, dropped here), each carrying its own exact file/line/column — no
 * echoed-config scanning or key disambiguation needed, unlike the block dump.
 *
 * The block dump shapes, still handled for an older esphome:
 *
 * 1. **Component block** — `<path>: [source <file>:<line>]` followed by the
 *    echoed config with error messages *interleaved* right before the offending
 *    line (the dump and the errors are not reliably blank-line separated):
 *    ```
 *    wifi: [source device.yaml:17]
 *      ssid: MyNetwork
 *      WPA password must be at least 8 characters long.   <- error (prose)
 *      password: secret                                   <- offending key
 *    ```
 *    So we scan line by line: a prose (non-`key:`) line is an error message; the
 *    next `key:` line is the offender. ESPHome sometimes can't resolve a source
 *    location for the path (`line_info()` in its `config.py` falls back to the
 *    literal string `None` when no document-range mark was tracked for it, e.g.
 *    for some nested-schema errors) — `<path>: None` instead of `[source …]`.
 *    Still a component block, just with no anchor line; the annotator locates
 *    the offending key by searching the whole file instead.
 *
 * 2. **Top-level** — printed after `Failed config` with no source block, e.g.
 *    `Platform not found: 'binary_sensor.gpxo'`. No line info; we extract a
 *    quoted token (`gpxo`) for the annotator to locate.
 */
object EsphomeConfigOutputParser {

    private val HEADER = Regex("""^(.+):\s+(?:\[source\s+(.+):(\d+)]|None)\s*$""")
    private val YAML_KEY = Regex("""^[\w.\-]+:(\s.*)?$""")
    private val QUOTED = Regex("""'([^']+)'""")
    private val WARNING_LINE = Regex("""^WARNING\s+(.+)$""")
    private val GPIO = Regex("""\bGPIO\d+\b""")
    private val PLATFORM_NOT_FOUND = Regex("""^Platform not found\b""")

    /** `--error-format line`, with a resolved source location: `file:line:col: error|note: message`. */
    private val LINE_LOCATED = Regex("""^(.+):(\d+):(\d+): (error|note): (.*)$""")
    /** `--error-format line`, no resolvable location (e.g. an unreadable file): `file: error|note: message`. */
    private val LINE_UNLOCATED = Regex("""^(.+): (error|note): (.*)$""")

    /**
     * `WARNING …` lines from `esphome config` (e.g. a strapping-pin advisory).
     * Unlike errors these appear even on a *valid* config and carry no source
     * line, so each is anchored by a token located in the file — a pin like
     * `GPIO46` or a quoted name. Warnings without a locatable token are dropped
     * (they're still visible in the run console). Safe to run on any output: it
     * only looks at `WARNING` lines, never the echoed config dump.
     */
    fun parseWarnings(output: String, targetFile: String): List<EsphomeDiagnostic> =
        output.lineSequence()
            .mapNotNull { WARNING_LINE.matchEntire(it.trim())?.groupValues?.get(1)?.trim() }
            .distinct()
            .mapNotNull { message ->
                val token = warningToken(message) ?: return@mapNotNull null
                EsphomeDiagnostic(
                    targetFile, 0, message, offendingKey = null,
                    searchToken = token, severity = EsphomeSeverity.WARNING,
                )
            }
            .toList()

    /** A token to locate a warning: a pin (`GPIO46`), else a quoted name. */
    private fun warningToken(message: String): String? =
        GPIO.find(message)?.value
            ?: QUOTED.find(message)?.groupValues?.get(1)?.substringAfterLast('.')?.takeIf { it.isNotBlank() }

    /**
     * @param includeTopLevelErrors whether to attribute headerless top-level
     *   errors (no `[source …]`) to [targetFile]. False when validating a
     *   fragment via the device root that includes it: a top-level error then
     *   belongs to the root, not the fragment, so it is surfaced when the root
     *   itself is open — not pinned onto the fragment.
     */
    fun parse(
        output: String,
        targetFile: String,
        includeTopLevelErrors: Boolean = true,
    ): List<EsphomeDiagnostic> {
        if (isLineFormat(output)) return parseLineFormat(output, targetFile, includeTopLevelErrors)
        val lines = output.lines()
        val diagnostics = mutableListOf<EsphomeDiagnostic>()
        var failedSeen = false

        var i = 0
        while (i < lines.size) {
            val header = HEADER.matchEntire(lines[i].trimEnd())
            if (header != null) {
                // group(2) only participates on the `[source file:line]` branch;
                // it's absent (null) when ESPHome dumped the bare `None` fallback.
                val hasSource = header.groups[2] != null
                i++
                val body = mutableListOf<String>()
                while (i < lines.size) {
                    val line = lines[i]
                    if (line.isNotBlank() && !line[0].isWhitespace()) break
                    body.add(line)
                    i++
                }
                if (hasSource) {
                    diagnostics += parseBlock(body, header.groupValues[2], header.groupValues[3].toIntOrNull() ?: 1)
                } else if (includeTopLevelErrors) {
                    // No source location to attribute this to a specific file by, so
                    // only trust it for the file actually being validated — not a
                    // fragment pulled in via its device root, where it could belong
                    // to any file in the include graph. Pass the block's top-level
                    // key (e.g. `esphome` from `esphome`, or `sensor` from
                    // `sensor.dht`) as a fallback search anchor.
                    val domain = header.groupValues[1].substringBefore('.')
                    diagnostics += parseBlock(body, targetFile, anchorLine = 0, domain = domain)
                }
                continue
            }

            val trimmed = lines[i].trim()
            if (trimmed == "Failed config") {
                failedSeen = true
            } else if (includeTopLevelErrors && failedSeen && isTopLevelError(lines[i])) {
                diagnostics += EsphomeDiagnostic(
                    targetFile, 0, trimmed, offendingKey = null, searchToken = searchToken(trimmed),
                    platformValue = platformValue(trimmed),
                )
            }
            i++
        }

        return diagnostics.filter { sameFile(it.file, targetFile) }
    }

    /**
     * Whether [output] is in the `--error-format line` shape rather than the
     * classic block dump. `": error: "`/`": note: "` is distinctive enough
     * (the block dump never produces it — its prose lines carry no location
     * prefix) that a single matching line is a safe signal, with no need to
     * probe the esphome version separately.
     */
    private fun isLineFormat(output: String): Boolean =
        output.lineSequence().any {
            val trimmed = it.trimEnd()
            LINE_LOCATED.matches(trimmed) || LINE_UNLOCATED.matches(trimmed)
        }

    /**
     * Parses `--error-format line` output: one `file:line:col: error: message`
     * line per problem (an unresolvable location drops the `:line:col` down to
     * just `file: error: message` — see [EsphomeDiagnostic.column]'s doc).
     * `note:` lines (YAML-error context, or "included from here" on a fragment's
     * wrapped error) are supplementary to the `error:` line right before them,
     * not separate problems, so they're dropped rather than turned into their
     * own diagnostic.
     */
    private fun parseLineFormat(
        output: String,
        targetFile: String,
        includeTopLevelErrors: Boolean,
    ): List<EsphomeDiagnostic> {
        val diagnostics = mutableListOf<EsphomeDiagnostic>()
        for (raw in output.lineSequence()) {
            val trimmed = raw.trimEnd()
            val located = LINE_LOCATED.matchEntire(trimmed)
            if (located != null) {
                val (file, lineStr, colStr, kind, message) = located.destructured
                if (kind == "error") {
                    diagnostics += EsphomeDiagnostic(
                        file, lineStr.toIntOrNull() ?: 0, message, offendingKey = null,
                        column = colStr.toIntOrNull(),
                    )
                }
                continue
            }
            val unlocated = LINE_UNLOCATED.matchEntire(trimmed) ?: continue
            val (_, kind, message) = unlocated.destructured
            // No file/line to attribute this to by itself — same ambiguity as a
            // headerless block-dump error, so it's only trusted for the file
            // actually being validated (see `includeTopLevelErrors`'s doc on [parse]).
            if (kind == "error" && includeTopLevelErrors) {
                diagnostics += EsphomeDiagnostic(targetFile, 0, message, offendingKey = null)
            }
        }
        return diagnostics.filter { sameFile(it.file, targetFile) }
    }

    /**
     * Scan a component block body: prose line = error, following `key:` =
     * offender. Also tracks the nesting of structural keys (indent -> key name)
     * so each diagnostic can carry its immediate enclosing key — e.g.
     * `binary_sensor.template.publish` for an `id:` parameter nested under an
     * action — letting the annotator tell an id-reference apart from an
     * unrelated `id:` declaration of the same value elsewhere in the file.
     */
    private fun parseBlock(
        body: List<String>,
        file: String,
        anchorLine: Int,
        domain: String? = null,
    ): List<EsphomeDiagnostic> {
        val diagnostics = mutableListOf<EsphomeDiagnostic>()
        val message = mutableListOf<String>()
        val ancestry = ArrayDeque<Pair<Int, String>>()

        for (index in 0..body.size) {
            val line = body.getOrNull(index)
            val trimmed = line?.trim().orEmpty()
            // Prose = an error sentence. Echoed-config lines (`key:`, `key: value`,
            // and YAML list items like `- lvgl.resume:`) are structural, not prose —
            // otherwise a list item in the dump is mistaken for a second error.
            val isProse = line != null && line.isNotBlank() && !isStructuralLine(trimmed)
            // Blank lines carry no indentation info — leave the ancestry untouched.
            val indent = if (line != null && trimmed.isNotEmpty()) structuralIndent(line) else null
            if (indent != null) {
                while (ancestry.isNotEmpty() && ancestry.last().first >= indent) ancestry.removeLast()
            }

            if (isProse) {
                message.add(trimmed)
            } else if (message.isNotEmpty()) {
                val isKey = YAML_KEY.matches(trimmed)
                val offendingKey = trimmed.takeIf { isKey }?.substringBefore(':')?.takeIf { it.isNotBlank() }
                val offendingValue = trimmed.takeIf { isKey }
                    ?.substringAfter(':', "")?.trim()?.takeIf { it.isNotBlank() }
                diagnostics += EsphomeDiagnostic(
                    file, anchorLine, message.joinToString(" "), offendingKey, offendingValue,
                    domain = domain,
                    parentKey = ancestry.lastOrNull()?.second,
                )
                message.clear()
            }

            if (indent != null) {
                structuralKey(trimmed)?.let { ancestry.addLast(indent to it) }
            }
        }
        return diagnostics
    }

    /** A line from the echoed config: a `key:`/`key: value`, or a YAML list item (`- …`). */
    private fun isStructuralLine(trimmed: String): Boolean =
        YAML_KEY.matches(trimmed) || trimmed == "-" || trimmed.startsWith("- ")

    /** Indentation depth of a dump line's content, counting a `"- "` list marker as +2 columns. */
    private fun structuralIndent(line: String): Int {
        var indent = 0
        while (indent < line.length && line[indent] == ' ') indent++
        if (line.startsWith("- ", indent)) indent += 2
        return indent
    }

    /** The key name opening [trimmed] (`"switch:"` / `"- button:"` -> the key), or null if it isn't a key line. */
    private fun structuralKey(trimmed: String): String? {
        val content = trimmed.removePrefix("-").trim()
        if (!YAML_KEY.matches(content)) return null
        return content.substringBefore(':').trim().takeIf { it.isNotEmpty() }
    }

    private fun isTopLevelError(raw: String): Boolean {
        if (raw.isBlank() || raw[0].isWhitespace()) return false
        val trimmed = raw.trim()
        return trimmed != "Failed config" &&
            !trimmed.startsWith("INFO ") &&
            !trimmed.startsWith("WARNING ") &&
            HEADER.matchEntire(trimmed) == null
    }

    /** A quoted token to locate in the file, e.g. `'binary_sensor.gpxo'` -> `gpxo`. */
    private fun searchToken(message: String): String? =
        QUOTED.find(message)?.groupValues?.get(1)?.substringAfterLast('.')?.takeIf { it.isNotBlank() }

    /**
     * The platform name from a "Platform not found: '<domain>.<platform>'" error,
     * to anchor on the `- platform: <platform>` line rather than the first bare
     * occurrence of the token.
     */
    private fun platformValue(message: String): String? =
        if (PLATFORM_NOT_FOUND.containsMatchIn(message)) searchToken(message) else null

    /** ESPHome may print an absolute or relative path; match on the file name. */
    private fun sameFile(reported: String, target: String): Boolean {
        val a = File(reported).name
        val b = File(target).name
        return a == b && a.isNotEmpty()
    }
}
