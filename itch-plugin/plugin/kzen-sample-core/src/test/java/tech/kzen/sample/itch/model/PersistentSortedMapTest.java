package tech.kzen.sample.itch.model;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.TreeMap;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;


class PersistentSortedMapTest {
    private static final int operations = 5000;


    @Test
    void matchesTreeMapUnderRandomUpdatesAndKeepsEveryHistoricalVersion() {
        Random random = new Random(3);
        PersistentSortedMap<Long, String> map = PersistentSortedMap.empty();
        TreeMap<Long, String> oracle = new TreeMap<>();
        List<PersistentSortedMap<Long, String>> history = new ArrayList<>();
        List<List<Long>> historyKeys = new ArrayList<>();

        for (int i = 0; i < operations; i++) {
            long key = random.nextInt(200);
            if (random.nextInt(3) == 0) {
                map = map.remove(key);
                oracle.remove(key);
            }
            else {
                map = map.put(key, "v" + i);
                oracle.put(key, "v" + i);
            }
            assertEquals(oracle.size(), map.size());
            assertEquals(List.copyOf(oracle.keySet()), keys(map));
            for (Map.Entry<Long, String> entry : map) {
                assertEquals(oracle.get(entry.getKey()), entry.getValue());
            }
            history.add(map);
            historyKeys.add(keys(map));
        }
        for (int i = 0; i < history.size(); i++) {
            assertEquals(historyKeys.get(i), keys(history.get(i)), "version " + i + " unchanged by later updates");
        }
    }


    @Test
    void comparatorOrderFirstEntriesAndSharing() {
        PersistentSortedMap<Long, String> descending = PersistentSortedMap.empty(Comparator.reverseOrder());
        descending = descending.put(10L, "a").put(30L, "c").put(20L, "b");
        assertEquals(List.of(30L, 20L, 10L), keys(descending));
        assertEquals(30L, descending.firstEntry().getKey());
        assertEquals(List.of(30L, 20L), descending.first(2).stream().map(Map.Entry::getKey).toList());
        assertNull(PersistentSortedMap.<Long, String>empty().firstEntry());

        PersistentSortedMap<Long, String> same = descending.remove(99L);
        assertSame(descending, same, "removing an absent key returns the same version");
        assertTrue(descending.containsKey(20L));
        assertEquals("b", descending.get(20L));
    }


    private static <K, V> List<K> keys(PersistentSortedMap<K, V> map) {
        List<K> keys = new ArrayList<>();
        for (Map.Entry<K, V> entry : map) {
            keys.add(entry.getKey());
        }
        return keys;
    }
}
