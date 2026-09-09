package tech.kzen.sample.embed.catalog

import tech.kzen.lib.common.model.location.ObjectLocation

object CatalogConventions {
    val actions = ObjectLocation.parse("auto-jvm/kzen-sample-embed/catalog-source.yaml#CatalogActions")
    const val action = "action"
    const val source = "source"
    const val selection = "selection"
    const val symbols = "symbols"
}
