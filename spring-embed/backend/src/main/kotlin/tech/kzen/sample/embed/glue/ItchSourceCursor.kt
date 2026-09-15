package tech.kzen.sample.embed.glue

import tech.kzen.auto.common.paradigm.job.control.JobControl
import tech.kzen.lib.common.model.location.ObjectLocation
import tech.kzen.sample.embed.host.catalog.ItchCatalog
import tech.kzen.sample.itch.day.DatedSymbolDay
import tech.kzen.sample.itch.day.MaterializationProgress
import tech.kzen.sample.itch.day.SymbolDaySession
import tech.kzen.sample.embed.host.catalog.ItchPreparationRequest
import tech.kzen.sample.itch.message.ItchHeader

/** The selection and progress travel with the cursor across a live edit. No loaded batch is retained here. */
internal class ItchSourceCursor(
    private val catalog: ItchCatalog,
    selection: List<String>,
    symbols: List<String>
): Iterator<DatedSymbolDay>, AutoCloseable {
    private data class UnitOfWork(val file: String, val date: String, val symbol: String, val messages: Long, val bytes: Long)

    private class Analysis(selected: ItchCatalog.Selection, symbols: List<String>) {
        val entries = selected.files()
        val stores = selected.stores()
        val units = entries.flatMap { entry ->
            val store = stores.getValue(entry.id())
            val available = store.symbols().keys
            val selected = if (symbols.isEmpty()) available.sorted() else symbols.filter { it in available }.distinct().sorted()
            selected.map { symbol ->
                val locate = store.locate(symbol)
                val own = store.stats(locate)
                val shared = if (locate == ItchHeader.marketWideLocate) null else store.partitions()[ItchHeader.marketWideLocate]
                UnitOfWork(entry.id(), entry.date().toString(), symbol,
                    own.messages() + (shared?.messages() ?: 0), own.bytes() + (shared?.bytes() ?: 0))
            }
        }
        val files = entries.associate { entry -> entry.id() to units.filter { it.file == entry.id() } }
        val messagesBefore = units.runningFold(0L) { count, unit -> count + unit.messages }
        val bytesBefore = units.runningFold(0L) { count, unit -> count + unit.bytes }
        val fileRanges = run {
            var offset = 0
            files.mapValues { (_, selected) -> (offset until offset + selected.size).also { offset += selected.size } }
        }
    }

    private val entries = catalog.selectionFiles(selection)
    private val symbols = symbols.toList()
    private var analysis: Analysis? = null
    private val units get() = checkNotNull(analysis).units
    private val stores get() = checkNotNull(analysis).stores
    private val files get() = checkNotNull(analysis).files
    private val messagesBefore get() = checkNotNull(analysis).messagesBefore
    private val bytesBefore get() = checkNotNull(analysis).bytesBefore
    private val fileRanges get() = checkNotNull(analysis).fileRanges
    private var preparation: ItchPreparationRequest? = null
    private var preparationProgress: ItchPreparationRequest.Progress? = null
    @Volatile private var closed = false
    private var activeIndex = 0
    private var index = 0
    private var messages = 0L
    private var bytes = 0L
    private var phase = "queued"
    private var control: JobControl? = null
    private var location: ObjectLocation? = null
    private var lastPublished = 0L
    private var session: SymbolDaySession? = null
    private var sessionFile: String? = null

    private fun initialize() {
        check(!closed) { "ITCH cursor is closed" }
        if (analysis != null) return
        val request = synchronized(this) {
            check(!closed) { "ITCH cursor is closed" }
            catalog.requestPreparation(entries.map { it.id() }).also { preparation = it }
        }
        try {
            val selected = request.await { progress ->
                preparationProgress = progress
                val changed = phase != progress.phase()
                phase = progress.phase()
                publish(changed)
            }
            val ready = Analysis(selected, symbols)
            check(ready.units.isNotEmpty()) { "No selected symbols occur on the selected dates" }
            synchronized(this) {
                check(!closed) { "ITCH cursor is closed" }
                analysis = ready
            }
            phase = "reading"
            publish(true)
        }
        catch (failure: Throwable) {
            analysis = null
            phase = "stopped"
            try { request.close() } catch (closeFailure: Throwable) { failure.addSuppressed(closeFailure) }
            try { publish(true) } catch (progressFailure: Throwable) { failure.addSuppressed(progressFailure) }
            throw failure
        }
    }

    fun bind(control: JobControl, location: ObjectLocation) {
        this.control = control
        this.location = location
        publish(true)
    }

    override fun hasNext(): Boolean {
        initialize()
        if (index < units.size) return true
        phase = "complete"
        return false
    }

    override fun next(): DatedSymbolDay {
        if (!hasNext()) throw NoSuchElementException()
        activeIndex = index
        val unit = units[index]
        messages = 0; bytes = 0; phase = "reading"
        publish()
        try {
            if (sessionFile != unit.file) {
                session?.close()
                session = catalog.openSession(stores.getValue(unit.file), files.getValue(unit.file)
                    .dropWhile { it.symbol != unit.symbol }.map { it.symbol })
                sessionFile = unit.file
            }
            val batch = checkNotNull(session).next(object : MaterializationProgress {
                override fun update(count: Long, size: Long) {
                    messages = count; bytes = size; phase = "reading"
                    publish()
                }
                override fun waiting() {
                    phase = "waiting"
                    publish(true)
                }
            })
            val entry = entries.first { it.id() == unit.file }
            val day = DatedSymbolDay(unit.date, unit.symbol, entry.url().toString(), batch)
            index++
            messages = 0; bytes = 0
            phase = if (index == units.size) "complete" else "downstream"
            try {
                // Publish before send can park: all bytes are read even while downstream still owns work.
                publish(index == 1 || index == units.size)
            }
            catch (failure: Throwable) {
                // The framework has not adopted the returned batch yet.
                index--
                try { day.close() } catch (closeFailure: Throwable) { failure.addSuppressed(closeFailure) }
                try { session?.close() } catch (closeFailure: Throwable) { failure.addSuppressed(closeFailure) }
                session = null
                sessionFile = null
                throw failure
            }
            return day
        }
        catch (e: Throwable) {
            phase = "stopped"
            try { publish(true) } catch (progressFailure: Throwable) {
                if (progressFailure !== e) e.addSuppressed(progressFailure)
            }
            throw e
        }
    }

    fun progress(): Map<String, Any?> {
        if (analysis == null) {
            val pending = preparationProgress
            return mapOf("itch" to mapOf(
                "phase" to phase, "preparation" to true,
                "file" to (pending?.file() ?: entries.first().id()),
                "date" to (pending?.date() ?: entries.first().date().toString()),
                "fileIndex" to (pending?.fileIndex() ?: 1).toString(),
                "totalFiles" to entries.size.toString(),
                "downloadBytes" to (pending?.bytes() ?: 0).toString(),
                "downloadTotalBytes" to (pending?.totalBytes() ?: -1).toString(),
                "detail" to (pending?.detail() ?: "Waiting to prepare selected dates")))
        }
        val current = units[activeIndex]
        fun counts(start: Int, end: Int): Map<String, Any> {
            val completedEnd = index.coerceIn(start, end)
            val partial = index in start until end
            return mapOf(
                "symbols" to (completedEnd - start).toString(), "totalSymbols" to (end - start).toString(),
                "messages" to (messagesBefore[completedEnd] - messagesBefore[start] + if (partial) messages else 0).toString(),
                "totalMessages" to (messagesBefore[end] - messagesBefore[start]).toString(),
                "bytes" to (bytesBefore[completedEnd] - bytesBefore[start] + if (partial) bytes else 0).toString(),
                "totalBytes" to (bytesBefore[end] - bytesBefore[start]).toString())
        }
        return mapOf("itch" to (counts(0, units.size) + mapOf(
            "phase" to phase,
            "file" to current.file,
            "date" to current.date,
            "symbol" to current.symbol,
            "fileIndex" to (files.keys.indexOf(current.file) + 1).toString(),
            "totalFiles" to files.size.toString(),
            "files" to entries.map { entry ->
                val range = fileRanges.getValue(entry.id())
                counts(range.first, range.last + 1) + mapOf("file" to entry.id(), "date" to entry.date().toString(),
                    "skipped" to range.isEmpty())
            }
        )))
    }

    private fun publish(force: Boolean = false) {
        val now = System.nanoTime()
        if (!force && lastPublished != 0L && now - lastPublished < publishIntervalNanos) return
        val target = location ?: return
        lastPublished = now
        control?.publishProgress(target, progress(), force)
    }

    override fun close() {
        synchronized(this) {
            if (closed) return
            closed = true
        }
        if (phase != "complete") phase = "stopped"
        try { publish(true) }
        finally {
            try { session?.close() }
            finally {
                try { preparation?.close() }
                finally { session = null; control = null; location = null }
            }
        }
    }

    companion object { private const val publishIntervalNanos = 200_000_000L }
}
