package tech.kzen.sample.embed.catalog

import kotlinx.serialization.Serializable

@Serializable
data class CatalogSnapshot(
    val entries: List<CatalogEntry>,
    val error: String?,
    val sourceDirectory: String? = null,
    val indexDirectory: String? = null
)
