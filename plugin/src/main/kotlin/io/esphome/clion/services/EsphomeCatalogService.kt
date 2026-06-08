package io.esphome.clion.services

import com.intellij.openapi.components.Service
import com.intellij.openapi.components.service
import io.esphome.clion.catalog.CatalogRepository
import io.esphome.clion.catalog.CatalogSource

/**
 * Application-level holder for the ESPHome [CatalogRepository], backed by the
 * catalog vendored into the plugin's resources at `/esphome/definitions/`.
 *
 * The repository is lazy, so constructing the service is cheap; the index is
 * parsed on first completion and component bodies are loaded on demand.
 */
@Service
class EsphomeCatalogService {

    private val source = CatalogSource { relativePath ->
        javaClass.getResourceAsStream("$RESOURCE_ROOT/$relativePath")
            ?.use { it.reader(Charsets.UTF_8).readText() }
    }

    val repository: CatalogRepository = CatalogRepository(source)

    companion object {
        private const val RESOURCE_ROOT = "/esphome/definitions"

        fun getInstance(): EsphomeCatalogService = service()
    }
}
