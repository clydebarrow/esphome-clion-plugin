package io.esphome.clion.references

import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.psi.PsiManager
import org.jetbrains.yaml.psi.YAMLFile
import org.jetbrains.yaml.psi.YAMLKeyValue
import org.jetbrains.yaml.psi.YAMLMapping
import org.jetbrains.yaml.psi.YAMLScalar
import org.jetbrains.yaml.psi.YAMLValue

/**
 * Recognises and resolves ESPHome `!secret <name>` references. ESPHome substitutes
 * such a value from a `secrets.yaml` sitting in (or above) the config directory,
 * so navigation follows the same lookup: the nearest `secrets.yaml`/`secrets.yml`
 * at or above the referencing file, within the project.
 */
object EsphomeSecret {

    private const val SECRET_TAG = "secret"
    private val SECRETS_FILES = listOf("secrets.yaml", "secrets.yml")

    private fun tagName(value: YAMLValue?): String? = value?.tag?.text?.trim()?.removePrefix("!")

    /** The key named by a `!secret <name>` scalar, or null when it isn't one. */
    fun secretNameOf(scalar: YAMLScalar): String? =
        if (tagName(scalar) == SECRET_TAG) scalar.textValue.takeIf { it.isNotBlank() } else null

    /** The nearest `secrets.yaml` at or above [from] within the project, or null. */
    fun findSecretsFile(from: VirtualFile, project: Project): VirtualFile? {
        val base = project.basePath
        var dir: VirtualFile? = from.parent
        while (dir != null) {
            for (name in SECRETS_FILES) dir.findChild(name)?.takeIf { it.isValid && !it.isDirectory }?.let { return it }
            if (base != null && dir.path == base) break // don't escape the project
            dir = dir.parent
        }
        return null
    }

    /** Resolve `!secret [name]` (used in [from]) to its `name:` declaration, or null. */
    fun resolveSecret(project: Project, from: VirtualFile, name: String): YAMLKeyValue? {
        val secrets = findSecretsFile(from, project) ?: return null
        val yaml = PsiManager.getInstance(project).findFile(secrets) as? YAMLFile ?: return null
        return secretEntries(yaml)[name]
    }

    /** Every top-level `key: value` in a secrets file, keyed by name (first wins). */
    fun secretEntries(yaml: YAMLFile): Map<String, YAMLKeyValue> {
        val mapping = yaml.documents.firstOrNull()?.topLevelValue as? YAMLMapping ?: return emptyMap()
        val result = LinkedHashMap<String, YAMLKeyValue>()
        for (keyValue in mapping.keyValues) {
            val key = keyValue.keyText.takeIf { it.isNotEmpty() } ?: continue
            result.putIfAbsent(key, keyValue)
        }
        return result
    }
}
