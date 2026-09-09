package tech.kzen.sample.embed.client.catalog

import react.State

external interface CatalogSourceDisplayState: State {
    var catalogState: CatalogStore.State?
    var selection: List<String>
    var symbols: List<String>
    var datesOpen: Boolean?
    var symbolsOpen: Boolean
    var dateFilter: String
    var dateSearch: String
    var symbolSearch: String
    var saving: Boolean
    var error: String?
}
