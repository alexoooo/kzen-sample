package tech.kzen.sample.embed.glue

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import tech.kzen.sample.embed.catalog.CatalogSource
import tech.kzen.auto.common.paradigm.job.api.ChannelOutput
import tech.kzen.auto.common.paradigm.job.control.JobControl
import tech.kzen.auto.server.objects.job.worker.CursorSourceWorker
import tech.kzen.lib.common.exec.data.value.DataValue
import tech.kzen.lib.common.model.location.ObjectLocation
import tech.kzen.lib.common.reflect.Reflect
import tech.kzen.lib.common.reflect.Service
import tech.kzen.sample.embed.host.catalog.ItchCatalog
import tech.kzen.sample.itch.day.DatedSymbolDay

@Reflect
class ItchSourceWorker(
    output: ChannelOutput<DataValue>,
    private val selfLocation: ObjectLocation,
    selection: List<String>,
    symbols: List<String>,
    @Service private val catalog: ItchCatalog
): CursorSourceWorker(output, selfLocation), CatalogSource {
    private val selection = selection.distinct().sorted()
    private val symbols = symbols.distinct().sorted()

    override fun elementClass(): Class<*> = DatedSymbolDay::class.java
    override fun cursorConfigurationKey(): Any = selection to symbols
    override fun configurationError(): String? =
        if (selection.isEmpty()) "Select at least one date" else null

    override suspend fun catalog(refresh: Boolean) = withContext(Dispatchers.IO) { catalog.catalog(refresh) }
    override fun prepare(entries: List<String>) = catalog.prepare(entries)
    override fun cancel(entries: List<String>) = catalog.cancel(entries)

    private var cursor: ItchSourceCursor? = null

    override fun open(control: JobControl): Iterator<DatedSymbolDay> =
        ItchSourceCursor(catalog, selection, symbols)

    override fun onCursorReady(iterator: Iterator<*>, control: JobControl) {
        cursor = (iterator as ItchSourceCursor).also { it.bind(control, selfLocation) }
    }

    override fun progress(snapshot: Any?): Map<String, Any?>? = cursor?.progress()
}
