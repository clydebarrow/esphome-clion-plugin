package io.esphome.clion.secrets

/**
 * Pure parsing of a `secrets.yaml` line into the column span of its *value* — the
 * part to mask. Kept editor-free so it can be unit-tested; the editor masker maps
 * these columns to document offsets.
 *
 * `secrets.yaml` is a flat `key: value` file referenced by `!secret <key>`, so we
 * only mask an inline scalar value: the run after the first `key:` colon, with
 * surrounding whitespace trimmed. Blank lines, comment lines, and keys with no
 * inline value (a mapping/empty) have nothing to mask and return null.
 */
object EsphomeSecretLines {

    /**
     * The half-open `[start, end)` column range of the value on [line], or null
     * when there's nothing to mask.
     */
    fun valueColumns(line: String): IntRange? {
        val firstNonSpace = line.indexOfFirst { !it.isWhitespace() }
        if (firstNonSpace < 0 || line[firstNonSpace] == '#') return null // blank or comment

        val colon = line.indexOf(':')
        if (colon <= firstNonSpace) return null // no key, or `:` is the first glyph

        // The top-level `is_secrets_file` marker key is metadata for this plugin,
        // not a secret value — leave it in plain text.
        if (firstNonSpace == 0 && line.substring(firstNonSpace, colon).trim() == MARKER_KEY) return null

        var start = colon + 1
        while (start < line.length && (line[start] == ' ' || line[start] == '\t')) start++
        var end = line.length
        while (end > start && line[end - 1].isWhitespace()) end--
        if (start >= end) return null // empty value (a bare `key:` / nested mapping)

        // A `!include ...` value is a structural reference to another file, not a
        // secret — most commonly the YAML merge key `<<: !include secrets/other.yaml`
        // splitting secrets across files. Leave it in plain text.
        if (line.startsWith("!include", start)) return null

        return start until end
    }

    /**
     * Whether [text] declares itself a secrets file via a top-level
     * `is_secrets_file: true` marker key, letting masking apply to a secrets
     * file that isn't named `secrets.yaml`/`secrets.yml` (e.g. one split out
     * and pulled in via `<<: !include`).
     */
    fun declaresSecretsFile(text: CharSequence): Boolean =
        text.lineSequence().any { line ->
            if (line.isEmpty() || line[0].isWhitespace()) return@any false // only a top-level key
            val colon = line.indexOf(':')
            if (colon < 0) return@any false
            val key = line.substring(0, colon).trim()
            val value = line.substring(colon + 1).trim().trim('"', '\'')
            key == MARKER_KEY && value.equals("true", ignoreCase = true)
        }

    /**
     * The `[0, end]` line-number range of a leading front-matter block, inclusive
     * of both `---` delimiter lines — or null if [lines] doesn't open with one.
     * Front matter (Jekyll-style: a `---` line, some metadata, a closing `---`
     * line) holds file metadata such as the `is_secrets_file` marker and is never
     * masked, unlike the file's actual secret content that follows it.
     */
    fun frontMatterLineRange(lines: List<String>): IntRange? {
        if (lines.isEmpty() || lines[0].trim() != "---") return null
        for (i in 1 until lines.size) {
            if (lines[i].trim() == "---") return 0..i
        }
        return null // opening `---` with no closing `---` — not a front-matter block
    }

    private const val MARKER_KEY = "is_secrets_file"
}
