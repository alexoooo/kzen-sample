package tech.kzen.sample.plugin.itch;

import tech.kzen.auto.common.data.api.DataCursor;
import tech.kzen.lib.common.exec.data.shape.DataShape;
import tech.kzen.auto.plugin.api.data.ReaderByteInput;
import tech.kzen.lib.common.exec.data.value.DataValue;
import tech.kzen.sample.itch.message.ItchMessage;
import tech.kzen.sample.itch.wire.ItchDecoder;
import tech.kzen.sample.itch.wire.ItchFormatException;
import tech.kzen.sample.itch.wire.ItchFrameInput;
import tech.kzen.sample.plugin.io.ReaderByteInputStream;
import tech.kzen.sample.plugin.value.LiteralRecords;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.util.HashMap;
import java.util.Map;
import java.util.NoSuchElementException;


/**
 * The reader's single-pass cursor: decodes frames from the host-supplied bytes (already inflated when the
 * content coding was gzip), applies the config's type / symbol / time filters, learns the day's locate →
 * symbol catalog from the stock directory as it streams, and projects each admitted message to an
 * {@link ItchRow} literal record. A framing or decode failure propagates by name ({@link ItchFormatException});
 * closing releases the input once. Pulls block on the input, which is what the blocking reader base is for.
 */
final class ItchDataCursor implements DataCursor {
    private final ItchFrameInput frames;
    private final ItchReadConfig config;
    private final DataShape shape;
    private final long recordLimit;
    private final Map<Integer, String> symbolByLocate = new HashMap<>();

    private ItchMessage pending;
    private long delivered;
    private boolean exhausted;
    private boolean closed;


    ItchDataCursor(ReaderByteInput bytes, ItchReadConfig config, DataShape shape, long recordLimit) {
        this.config = config;
        this.shape = shape;
        this.recordLimit = recordLimit;
        try {
            this.frames = ItchFrameInput.open(new ReaderByteInputStream(bytes));
        }
        catch (IOException e) {
            throw new UncheckedIOException("Unable to open ITCH input", e);
        }
    }


    @Override
    public DataShape getShape() {
        return shape;
    }


    @Override
    public boolean hasNext() {
        if (closed || exhausted || delivered >= recordLimit) {
            return false;
        }
        while (pending == null) {
            if (!frames.next()) {
                exhausted = true;
                return false;
            }
            ItchMessage message = ItchDecoder.decode(frames.frame(), 0, frames.frameLength(), frames.ordinal());
            if (message instanceof ItchMessage.StockDirectory directory) {
                symbolByLocate.put(directory.header().stockLocate(), directory.stock());
            }
            if (admits(message)) {
                pending = message;
            }
        }
        return true;
    }


    @Override
    public DataValue next() {
        if (!hasNext()) {
            throw new NoSuchElementException();
        }
        ItchMessage message = pending;
        pending = null;
        delivered++;
        Map<String, Object> row = ItchRow.project(message, symbolByLocate::get);
        return LiteralRecords.row(shape.getItemType(), row);
    }


    @Override
    public void close() {
        if (closed) {
            return;
        }
        closed = true;
        try {
            frames.close();
        }
        catch (IOException e) {
            throw new UncheckedIOException("Unable to close ITCH input", e);
        }
    }


    private boolean admits(ItchMessage message) {
        if (!config.acceptsType(message.type())) {
            return false;
        }
        if (!config.acceptsTime(message.header().timestampNanos())) {
            return false;
        }
        if (config.symbols().isEmpty()) {
            return true;
        }
        String symbol = (String) ItchRow.project(message, symbolByLocate::get).get(ItchRow.stock);
        return config.acceptsSymbol(symbol);
    }
}
