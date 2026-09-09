package tech.kzen.sample.embed.glue

import tech.kzen.auto.common.paradigm.job.control.JobControl
import tech.kzen.lib.common.model.location.ObjectLocation
import tech.kzen.sample.embed.host.catalog.ItchCatalog
import tech.kzen.sample.itch.day.DatedSymbolDay
import tech.kzen.sample.itch.message.ItchHeader

/** The selection and progress travel with the cursor across a live edit. No loaded batch is retained here. */
internal class ItchSourceCursor(
    private val catalog: ItchCatalog,
    selection: List<String>,
    symbols: List<String>
): Iterator<DatedSymbolDay>, AutoCloseable {
    private data class UnitOfWork(val file: String, val date: String, val symbol: String, val messages: Long, val bytes: Long)

    private val entries = catalog.selected(selection)
    private val units = entries.flatMap { entry ->
        val store = catalog.readyStore(entry.id())
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
    private val files = entries.associate { entry -> entry.id() to units.filter { it.file == entry.id() } }
    private val messagesBefore = units.runningFold(0L) { count, unit -> count + unit.messages }
    private val bytesBefore = units.runningFold(0L) { count, unit -> count + unit.bytes }
    private val fileRanges = run {
        var offset = 0
        files.mapValues { (_, selected) -> (offset until offset + selected.size).also { offset += selected.size } }
    }
    private var activeIndex = 0
    private var index = 0
    private var messages = 0L
    private var bytes = 0L
    private var phase = "waiting"
    private var control: JobControl? = null
    private var location: ObjectLocation? = null
    private var lastPublished = 0L

    init { check(units.isNotEmpty()) { "No selected symbols occur on the selected dates" } }

    fun bind(control: JobControl, location: ObjectLocation) {
        this.control = control
        this.location = location
        publish(true)
    }

    override fun hasNext(): Boolean {
        if (index < units.size) return true
        phase = "complete"
        return false
    }

    override fun next(): DatedSymbolDay {
        if (!hasNext()) throw NoSuchElementException()
        activeIndex = index
        val unit = units[index]
        messages = 0; bytes = 0; phase = "waiting"
        // A new admission may block before the next periodic update, even after a very short preceding batch.
        publish(true)
        try {
            val day = catalog.materialize(unit.file, unit.symbol) { count, size ->
                messages = count; bytes = size; phase = "reading"
                publish()
            }
            index++
            messages = 0; bytes = 0
            phase = if (index == units.size) "complete" else "downstream"
            try {
                // Publish before send can park: all bytes are read even while downstream still owns work.
                publish(true)
            }
            catch (failure: Throwable) {
                // The framework has not adopted the returned batch yet.
                index--
                try { day.close() } catch (closeFailure: Throwable) { failure.addSuppressed(closeFailure) }
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
        if (phase != "complete") phase = "stopped"
        try { publish(true) }
        finally { control = null; location = null }
    }

    companion object { private const val publishIntervalNanos = 200_000_000L }
}
