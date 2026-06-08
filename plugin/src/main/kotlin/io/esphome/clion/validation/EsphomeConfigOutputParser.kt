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
    /** A literal token to locate in the file when there is no key/line (e.g. a bad platform value). */
    val searchToken: String? = null,
)

/**
 * Parses the textual output of `esphome config <file>`.
 *
 * Two error shapes occur:
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
 *    next `key:` line is the offender.
 *
 * 2. **Top-level** — printed after `Failed config` with no source block, e.g.
 *    `Platform not found: 'binary_sensor.gpxo'`. No line info; we extract a
 *    quoted token (`gpxo`) for the annotator to locate.
 */
object EsphomeConfigOutputParser {

    private val HEADER = Regex("""^(.+):\s+\[source\s+(.+):(\d+)]\s*$""")
    private val YAML_KEY = Regex("""^[\w.\-]+:(\s.*)?$""")
    private val QUOTED = Regex("""'([^']+)'""")

    fun parse(output: String, targetFile: String): List<EsphomeDiagnostic> {
        val lines = output.lines()
        val diagnostics = mutableListOf<EsphomeDiagnostic>()
        var failedSeen = false

        var i = 0
        while (i < lines.size) {
            val header = HEADER.matchEntire(lines[i].trimEnd())
            if (header != null) {
                val file = header.groupValues[2]
                val anchorLine = header.groupValues[3].toIntOrNull() ?: 1
                i++
                val body = mutableListOf<String>()
                while (i < lines.size) {
                    val line = lines[i]
                    if (line.isNotBlank() && !line[0].isWhitespace()) break
                    body.add(line)
                    i++
                }
                diagnostics += parseBlock(body, file, anchorLine)
                continue
            }

            val trimmed = lines[i].trim()
            if (trimmed == "Failed config") {
                failedSeen = true
            } else if (failedSeen && isTopLevelError(lines[i])) {
                diagnostics += EsphomeDiagnostic(targetFile, 0, trimmed, null, searchToken(trimmed))
            }
            i++
        }

        return diagnostics.filter { sameFile(it.file, targetFile) }
    }

    /** Scan a component block body: prose line = error, following `key:` = offender. */
    private fun parseBlock(body: List<String>, file: String, anchorLine: Int): List<EsphomeDiagnostic> {
        val diagnostics = mutableListOf<EsphomeDiagnostic>()
        val message = mutableListOf<String>()

        for (index in 0..body.size) {
            val line = body.getOrNull(index)
            val trimmed = line?.trim().orEmpty()
            val isProse = line != null && line.isNotBlank() && !YAML_KEY.matches(trimmed)

            if (isProse) {
                message.add(trimmed)
            } else if (message.isNotEmpty()) {
                val offendingKey = trimmed.takeIf { YAML_KEY.matches(it) }
                    ?.substringBefore(':')?.takeIf { it.isNotBlank() }
                diagnostics += EsphomeDiagnostic(file, anchorLine, message.joinToString(" "), offendingKey)
                message.clear()
            }
        }
        return diagnostics
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

    /** ESPHome may print an absolute or relative path; match on the file name. */
    private fun sameFile(reported: String, target: String): Boolean {
        val a = File(reported).name
        val b = File(target).name
        return a == b && a.isNotEmpty()
    }
}
