package io.esphome.clion.api

import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.psi.PsiManager
import io.esphome.clion.psi.EsphomeYaml
import io.esphome.clion.services.EsphomeIncludeGraph
import io.esphome.clion.services.EsphomeSubstitutions
import org.jetbrains.yaml.psi.YAMLFile
import org.jetbrains.yaml.psi.YAMLMapping

/**
 * Best-effort connection parameters for a device, derived from the open config
 * across its `!include` graph: host (`wifi:/ethernet: use_address:`, else
 * `<esphome: name>.local`), `api:` port/password/encryption key, with
 * `${substitution}`s expanded. The tool window pre-fills these and lets the user
 * override — so packages/edge cases the parse misses are still reachable.
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
        val subs = EsphomeSubstitutions.getInstance(project)
        val psiManager = PsiManager.getInstance(project)
        var name: String? = null
        var useAddress: String? = null
        var port: Int? = null
        var password: String? = null
        var key: String? = null
        var hasApi = false

        fun expand(vf: VirtualFile, raw: String?): String? =
            raw?.let { subs.expandText(it, vf).trim() }?.takeIf { it.isNotEmpty() }

        for (vf in EsphomeIncludeGraph.getInstance(project).connectedFiles(file)) {
            val yaml = psiManager.findFile(vf) as? YAMLFile ?: continue
            for (doc in yaml.documents) {
                val top = EsphomeYaml.topLevelMapping(doc) ?: continue

                (top.getKeyValueByKey("esphome")?.value as? YAMLMapping)
                    ?.getKeyValueByKey("name")?.valueText
                    ?.let { name = name ?: expand(vf, it) }

                top.getKeyValueByKey("api")?.let { api ->
                    hasApi = true
                    (api.value as? YAMLMapping)?.let { apiMap ->
                        apiMap.getKeyValueByKey("port")?.valueText?.toIntOrNull()?.let { port = port ?: it }
                        password = password ?: expand(vf, apiMap.getKeyValueByKey("password")?.valueText)
                        (apiMap.getKeyValueByKey("encryption")?.value as? YAMLMapping)
                            ?.getKeyValueByKey("key")?.valueText
                            ?.let { key = key ?: expand(vf, it) }
                    }
                }

                for (net in listOf("wifi", "ethernet")) {
                    (top.getKeyValueByKey(net)?.value as? YAMLMapping)
                        ?.getKeyValueByKey("use_address")?.valueText
                        ?.let { useAddress = useAddress ?: expand(vf, it) }
                }
            }
        }

        val host = useAddress ?: name?.let { "$it.local" }
        return Target(host, port ?: DEFAULT_PORT, password, key, name, hasApi)
    }
}
