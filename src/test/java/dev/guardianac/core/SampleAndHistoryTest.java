/* SPDX-License-Identifier: GPL-3.0-only */
package dev.guardianac.core;

import dev.guardianac.bukkit.telemetry.BoundedHistory;
import java.util.*;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class SampleAndHistoryTest {
    @Test void sampleCopiesMutableInputsAndIsImmutable() {
        Map<String, Double> values = new HashMap<>(); values.put("tps", 20d);
        Set<String> flags = new HashSet<>(); flags.add("ground");
        Sample sample = new Sample(new UUID(0, 1), 1000, 1, Sample.Kind.MOVE, values, flags);
        values.clear(); flags.clear(); assertEquals(20, sample.v("tps")); assertTrue(sample.has("ground"));
        assertTrue(Double.isNaN(sample.v("unknown")));
        assertThrows(UnsupportedOperationException.class, () -> sample.measurements().put("x", 1d));
        assertThrows(UnsupportedOperationException.class, () -> sample.flags().add("x"));
    }
    @Test void oversizedSamplesAreRejected() {
        Map<String, Double> values = new HashMap<>(); for (int i = 0; i < 97; i++) values.put("key" + i, 1d);
        assertThrows(IllegalArgumentException.class, () -> new Sample(new UUID(0, 1), 1, 1, Sample.Kind.MOVE, values, Set.of()));
        Set<String> flags = new HashSet<>(); for (int i = 0; i < 33; i++) flags.add("flag" + i);
        assertThrows(IllegalArgumentException.class, () -> new Sample(new UUID(0, 1), 1, 1, Sample.Kind.MOVE, Map.of(), flags));
    }
    @Test void unknownServerHealthIsUnsafeForHeuristics() {
        Sample unknown = Sample.builder(new UUID(0, 1), Sample.Kind.MOVE, 1, 1).build(); assertFalse(unknown.safePhysics());
        Sample safe = Sample.builder(new UUID(0, 1), Sample.Kind.MOVE, 1, 1).value("tps", 20).value("ping", 25).build(); assertTrue(safe.safePhysics());
    }
    @Test void historyIsBoundedAndUsesARealSlidingWindow() {
        BoundedHistory history = new BoundedHistory(3);
        history.add(1000, 1); history.add(1200, 2); history.add(1400, 3); history.add(1600, 4);
        assertEquals(3, history.size()); assertEquals(2, history.countSince(1200)); assertEquals(400, history.spanMillis());
        var stats = history.summarySince(0); assertEquals(3, stats.count()); assertEquals(3, stats.mean()); assertEquals(Math.sqrt(2d / 3), stats.stddev(), 1e-9);
        assertEquals(0, history.countSince(1600)); assertTrue(Double.isNaN(history.summarySince(1600).mean()));
    }
    @Test void historyRejectsNonfiniteValuesAndClearsOnClockReversal() {
        BoundedHistory history = new BoundedHistory(4); history.add(1000, 5); history.add(1100, Double.NaN);
        assertEquals(1, history.size()); history.add(500, 6); assertEquals(1, history.size()); assertEquals(6, history.summarySince(0).mean());
        history.clear(); assertEquals(0, history.size()); assertEquals(0, history.newestTime());
    }
}
