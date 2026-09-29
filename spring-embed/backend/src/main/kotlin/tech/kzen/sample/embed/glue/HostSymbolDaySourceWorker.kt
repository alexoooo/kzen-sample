package tech.kzen.sample.embed.glue

import tech.kzen.auto.common.paradigm.job.api.ChannelOutput
import tech.kzen.auto.common.paradigm.job.control.JobControl
import tech.kzen.auto.server.objects.job.worker.CursorSourceWorker
import tech.kzen.lib.common.exec.data.value.DataValue
import tech.kzen.lib.common.model.location.ObjectLocation
import tech.kzen.lib.common.reflect.Reflect
import tech.kzen.lib.common.reflect.Service
import tech.kzen.sample.embed.host.SymbolDayLoader
import tech.kzen.sample.itch.day.SymbolDay


/**
 * The Kotlin glue between the host's live object and a Job: a cursor-driven source (HS11) that opens the host's
 * governed [SymbolDayLoader] — a `@Service` the host registered under that Java interface — and hands the
 * framework a closeable cursor of fresh symbol-days. The framework owns every pull (each one a blocking
 * budget acquire, run through `runBlockingIo` so a cancel interrupts it), adopts each day as it arrives (E9:
 * the run closes it, returning the lease), and closes the cursor on completion, failure or cancellation.
 * No hand-written `produce`, no Emitter, no coroutine in this file.
 */
@Reflect
class HostSymbolDaySourceWorker(
    output: ChannelOutput<DataValue>,
    selfLocation: ObjectLocation,
    @Service private val loader: SymbolDayLoader
): CursorSourceWorker(output, selfLocation) {
    override fun open(control: JobControl): Iterator<*> {
        check(loader.available()) { "The host has no ITCH day loaded (kzen.host.day-file)" }
        return loader.open()
    }

    override fun elementClass(): Class<*> = SymbolDay::class.java
}
