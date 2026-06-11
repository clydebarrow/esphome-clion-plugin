package io.esphome.clion.api

import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.psi.PsiManager
import io.esphome.clion.psi.EsphomeYaml
import io.esphome.clion.services.EsphomeIncludeGraph
import io.esphome.clion.services.EsphomeSubstitutions
import org.jetbrains.yaml.psi.YAMLFile
import org.jetbrains.yaml.psi.YAMLKeyValue
import org.jetbrains.yaml.psi.YAMLMapping
import org.jetbrains.yaml.psi.YAMLScalar

/**
 * Best-effort connection parameters for a device, derived from the open config
 * across its `!include` graph: host (`wifi:/ethernet: use_address:`, else
 * `<esphome: name>.local`), `api:` port/password/encryption key, with
 * `${substitution}`s expanded and `!secret` values resolved from `secrets.yaml`.
 * The tool window pre-fills these and lets the user override — so packages and
 * edge cases the parse misses are still reachable.
 */
object EsphomeApiTarget {
    const val DEFAULT_PORT = 6053

    data class Target(
        val host: String?,
        val port: Int,
        val password: String?,
        val encryptionKey: String?,
        val deviceName: String?,
        /** True when an `api:` block exists — the feature only works if it does. */
        val hasApi: Boolean,
    )

    fun forFile(project: Project, file: VirtualFile): Target {
        val psiManager = PsiManager.getInstance(project)
        var name: String? = null
        var useAddress: String? = null
        var port: Int? = null
        var password: String? = null
        var key: String? = null
        var hasApi = false

        for (vf in EsphomeIncludeGraph.getInstance(project).connectedFiles(file)) {
            val yaml = psiManager.findFile(vf) as? YAMLFile ?: continue
            for (doc in yaml.documents) {
                val top = EsphomeYaml.topLevelMapping(doc) ?: continue

                (top.getKeyValueByKey("esphome")?.value as? YAMLMapping)
                    ?.getKeyValueByKey("name")
                    ?.let { name = name ?: resolve(project, vf, it) }

                top.getKeyValueByKey("api")?.let { api ->
                    hasApi = true
                    (api.value as? YAMLMapping)?.let { apiMap ->
                        apiMap.getKeyValueByKey("port")?.valueText?.toIntOrNull()?.let { port = port ?: it }
                        password = password ?: resolve(project, vf, apiMap.getKeyValueByKey("password"))
                        key = key ?: resolve(
                            project, vf,
                            (apiMap.getKeyValueByKey("encryption")?.value as? YAMLMapping)?.getKeyValueByKey("key"),
                        )
                    }
                }

                for (net in listOf("wifi", "ethernet")) {
                    (top.getKeyValueByKey(net)?.value as? YAMLMapping)
                        ?.getKeyValueByKey("use_address")
                        ?.let { useAddress = useAddress ?: resolve(project, vf, it) }
                }
            }
        }

        val host = useAddress ?: name?.let { "$it.local" }
        return Target(host, port ?: DEFAULT_PORT, password, key, name, hasApi)
    }

    /**
     * The effective string value of [kv]: a `!secret name` is looked up in
     * `secrets.yaml`; otherwise `${substitution}`s are expanded. Blank → null.
     */
    private fun resolve(project: Project, vf: VirtualFile, kv: YAMLKeyValue?): String? {
        val scalar = kv?.value as? YAMLScalar ?: return null
        val raw = scalar.textValue.trim().ifEmpty { return null }
        return if (EsphomeYaml.tagName(scalar) == "secret") {
            secretValue(project, vf, raw)
        } else {
            EsphomeSubstitutions.getInstance(project).expandText(raw, vf).trim().ifEmpty { null }
        }
    }

    /** Look up [name] in the nearest `secrets.yaml` at or above [vf]'s directory. */
    private fun secretValue(project: Project, vf: VirtualFile, name: String): String? {
        var dir = vf.parent
        val psiManager = PsiManager.getInstance(project)
        while (dir != null) {
            val secrets = dir.findChild("secrets.yaml")
            if (secrets != null) {
                val yaml = psiManager.findFile(secrets) as? YAMLFile
                yaml?.documents?.forEach { doc ->
                    EsphomeYaml.topLevelMapping(doc)?.getKeyValueByKey(name)?.valueText
                        ?.trim()?.ifEmpty { null }?.let { return it }
                }
            }
            dir = dir.parent
        }
        return null
    }
}
