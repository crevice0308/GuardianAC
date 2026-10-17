/* SPDX-License-Identifier: GPL-3.0-only */
package dev.guardianac.packet;

import java.util.Arrays;

/** Fixed-memory monotonic-time counter. Boundaries conservatively expire partial buckets. */
public final class RateWindow {
    private final long width;
    private final long[] epochs;
    private final int[] counts;
    public RateWindow(int buckets, long widthMillis) {
        if (buckets < 2 || buckets > 1000 || widthMillis < 1) throw new IllegalArgumentException();
        width = widthMillis; epochs = new long[buckets]; counts = new int[buckets];
        Arrays.fill(epochs, Long.MIN_VALUE);
    }
    public void add(long now) {
        long epoch = Math.floorDiv(now, width);
        int slot = Math.floorMod(epoch, epochs.length);
        if (epochs[slot] != epoch) { epochs[slot] = epoch; counts[slot] = 0; }
        if (counts[slot] < 1_000_000) counts[slot]++;
    }
    public int count(long now) {
        long epoch = Math.floorDiv(now, width); int total = 0;
        for (int i = 0; i < epochs.length; i++)
            if (epochs[i] <= epoch && epochs[i] > epoch - epochs.length) total += counts[i];
        return total;
    }
    public long durationMillis() { return width * epochs.length; }
}
