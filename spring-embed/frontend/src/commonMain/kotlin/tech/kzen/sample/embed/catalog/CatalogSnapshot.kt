package tech.kzen.sample.embed.catalog

import kotlinx.serialization.Serializable

@Serializable
data class CatalogSnapshot(val entries: List<CatalogEntry>, val error: String?)
