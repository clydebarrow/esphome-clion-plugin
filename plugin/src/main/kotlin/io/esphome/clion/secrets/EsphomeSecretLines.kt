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

        var start = colon + 1
        while (start < line.length && (line[start] == ' ' || line[start] == '\t')) start++
        var end = line.length
        while (end > start && line[end - 1].isWhitespace()) end--
        if (start >= end) return null // empty value (a bare `key:` / nested mapping)

        return start until end
    }
}
