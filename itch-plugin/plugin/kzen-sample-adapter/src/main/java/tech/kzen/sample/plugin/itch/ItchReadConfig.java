package tech.kzen.sample.plugin.itch;

import tech.kzen.auto.common.data.read.ReaderConfig;
import tech.kzen.lib.common.exec.ExecutionValue;
import tech.kzen.lib.common.exec.ListExecutionValue;
import tech.kzen.lib.common.exec.MapExecutionValue;
import tech.kzen.lib.common.exec.TextExecutionValue;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;


/**
 * The ITCH reader's configuration: which symbols (empty = all), which message types by their ITCH type letter
 * (empty = all), and a time-of-day window in nanoseconds since midnight ({@link #noBound} = open). The
 * canonical form sorts and upper-cases the symbols and sorts the types; it is what the reader encodes.
 */
public record ItchReadConfig(
        Set<String> symbols,
        Set<Character> messageTypes,
        long fromNanos,
        long toNanos
) implements ReaderConfig {
    public static final long noBound = -1L;

    static final String symbolsKey = "symbols";
    static final String messageTypesKey = "messageTypes";
    static final String fromKey = "fromNanos";
    static final String toKey = "toNanos";

    public static final ItchReadConfig all = new ItchReadConfig(Set.of(), Set.of(), noBound, noBound);


    public ItchReadConfig {
        symbols = Collections.unmodifiableSet(new TreeSet<>(symbols));
        messageTypes = Collections.unmodifiableSet(new TreeSet<>(messageTypes));
    }


    public static ItchReadConfig decode(ExecutionValue config) {
        if (!(config instanceof MapExecutionValue map)) {
            throw new IllegalArgumentException("ITCH reader config must be a map");
        }
        Set<String> symbols = new TreeSet<>();
        for (String symbol : texts(map, symbolsKey)) {
            symbols.add(symbol.trim().toUpperCase(Locale.ROOT));
        }
        Set<Character> types = new TreeSet<>();
        for (String type : texts(map, messageTypesKey)) {
            String trimmed = type.trim();
            if (trimmed.length() != 1) {
                throw new IllegalArgumentException("ITCH message type must be one letter, not '" + type + "'");
            }
            types.add(trimmed.charAt(0));
        }
        return new ItchReadConfig(symbols, types, bound(map, fromKey), bound(map, toKey));
    }


    public ExecutionValue encode() {
        Map<String, ExecutionValue> values = new LinkedHashMap<>();
        values.put(symbolsKey, new ListExecutionValue(symbols.stream().map(s -> (ExecutionValue) new TextExecutionValue(s)).toList()));
        values.put(messageTypesKey, new ListExecutionValue(messageTypes.stream()
                .map(t -> (ExecutionValue) new TextExecutionValue(String.valueOf(t))).toList()));
        values.put(fromKey, new TextExecutionValue(fromNanos == noBound ? "" : Long.toString(fromNanos)));
        values.put(toKey, new TextExecutionValue(toNanos == noBound ? "" : Long.toString(toNanos)));
        return new MapExecutionValue(values);
    }


    /** Fails by name on an empty symbol, a non-letter type, a negative bound or an inverted window. */
    public void validate() {
        for (String symbol : symbols) {
            if (symbol.isBlank()) {
                throw new IllegalArgumentException("ITCH symbol filter must not contain a blank symbol");
            }
        }
        for (char type : messageTypes) {
            if (!Character.isLetter(type)) {
                throw new IllegalArgumentException("ITCH message type filter must be letters, not '" + type + "'");
            }
        }
        if (fromNanos != noBound && fromNanos < 0 || toNanos != noBound && toNanos < 0) {
            throw new IllegalArgumentException("ITCH time window bounds must not be negative");
        }
        if (fromNanos != noBound && toNanos != noBound && toNanos < fromNanos) {
            throw new IllegalArgumentException("ITCH time window end " + toNanos + " precedes start " + fromNanos);
        }
    }


    public boolean acceptsType(char type) {
        return messageTypes.isEmpty() || messageTypes.contains(type);
    }


    public boolean acceptsSymbol(String symbolOrNull) {
        return symbols.isEmpty() || symbolOrNull != null && symbols.contains(symbolOrNull);
    }


    public boolean acceptsTime(long timestampNanos) {
        return (fromNanos == noBound || timestampNanos >= fromNanos) &&
                (toNanos == noBound || timestampNanos <= toNanos);
    }


    private static List<String> texts(MapExecutionValue map, String key) {
        ExecutionValue value = map.getValues().get(key);
        if (value == null) {
            return List.of();
        }
        if (!(value instanceof ListExecutionValue list)) {
            throw new IllegalArgumentException("ITCH reader '" + key + "' must be a list");
        }
        List<String> texts = new ArrayList<>();
        for (ExecutionValue item : list.getValues()) {
            if (!(item instanceof TextExecutionValue text)) {
                throw new IllegalArgumentException("ITCH reader '" + key + "' entries must be text");
            }
            texts.add(text.getValue());
        }
        return texts;
    }


    private static long bound(MapExecutionValue map, String key) {
        ExecutionValue value = map.getValues().get(key);
        if (value == null) {
            return noBound;
        }
        if (!(value instanceof TextExecutionValue text)) {
            throw new IllegalArgumentException("ITCH reader '" + key + "' must be text");
        }
        String trimmed = text.getValue().trim();
        if (trimmed.isEmpty()) {
            return noBound;
        }
        try {
            return Long.parseLong(trimmed);
        }
        catch (NumberFormatException e) {
            throw new IllegalArgumentException("ITCH reader '" + key + "' must be nanoseconds since midnight, not '" + trimmed + "'");
        }
    }
}
