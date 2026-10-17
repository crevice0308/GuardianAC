/* SPDX-License-Identifier: GPL-3.0-only */
package dev.guardianac.bukkit.telemetry;

/** Fixed-size timestamp/value history. Owned by the server thread, never by packet threads. */
public final class BoundedHistory {
    private final double[] values;
    private final long[] times;
    private int next, size;
    public BoundedHistory(int capacity) {
        if (capacity < 2 || capacity > 4096) throw new IllegalArgumentException("capacity");
        values = new double[capacity]; times = new long[capacity];
    }
    public void add(long time, double value) {
        if (!Double.isFinite(value)) return;
        if (size > 0 && time < newestTime()) clear();
        values[next] = value; times[next] = time; next = (next + 1) % values.length;
        size = Math.min(size + 1, values.length);
    }
    public int size() { return size; }
    public void clear() { next = 0; size = 0; }
    public long newestTime() { return size == 0 ? 0 : times[(next - 1 + values.length) % values.length]; }
    public long spanMillis() { return size < 2 ? 0 : newestTime() - times[(next - size + values.length) % values.length]; }
    public int countSince(long cutoff) {
        int count = 0;
        for (int i = 0; i < size; i++) if (times[(next - i - 1 + values.length) % values.length] > cutoff) count++;
        return count;
    }
    public Summary summarySince(long cutoff) {
        int count = 0; double mean = 0, m2 = 0;
        for (int i = 0; i < size; i++) {
            int index = (next - i - 1 + values.length) % values.length;
            if (times[index] <= cutoff) continue;
            count++; double d = values[index] - mean; mean += d / count; m2 += d * (values[index] - mean);
        }
        return new Summary(count, count == 0 ? Double.NaN : mean, count < 2 ? Double.NaN : Math.sqrt(m2 / count));
    }
    public record Summary(int count, double mean, double stddev) {}
}
