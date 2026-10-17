/* SPDX-License-Identifier: GPL-3.0-only */
package dev.guardianac.bukkit.telemetry;

import java.util.HashMap;
import java.util.Map;
import java.util.Objects;
import java.util.function.Supplier;

/**
 * Bounded, server-thread-owned state. Only explicit admission may open a session;
 * observations cannot resurrect a closed session. Session tokens use object identity,
 * so a delayed event from a previous connection cannot mutate a same-key reconnect.
 */
public final class SessionStateStore<K, S, V> {
    private final int capacity;
    private final Map<K, Entry<S, V>> sessions = new HashMap<>();
    public SessionStateStore(int capacity) {
        if (capacity < 1 || capacity > 10000) throw new IllegalArgumentException("capacity");
        this.capacity = capacity;
    }
    public boolean admit(K key, S session) {
        Objects.requireNonNull(key); Objects.requireNonNull(session);
        Entry<S, V> current = sessions.get(key);
        if (current != null && current.session == session) return true;
        if (current == null && sessions.size() >= capacity) return false;
        sessions.put(key, new Entry<>(session)); return true;
    }
    /** Reconcile only against an authoritative, same-thread online-session snapshot. */
    public void reconcile(Map<K, S> online) {
        Objects.requireNonNull(online);
        sessions.entrySet().removeIf(e -> online.get(e.getKey()) != e.getValue().session);
        online.forEach(this::admit);
    }
    public V state(K key, S session, Supplier<V> factory) {
        Entry<S, V> entry = matching(key, session);
        if (entry == null) return null;
        if (entry.value == null) entry.value = Objects.requireNonNull(factory.get());
        return entry.value;
    }
    public V peek(K key, S session) {
        Entry<S, V> entry = matching(key, session); return entry == null ? null : entry.value;
    }
    /** Reset observation history while retaining the currently admitted connection. */
    public void reset(K key) { Entry<S, V> entry = sessions.get(key); if (entry != null) entry.value = null; }
    public void reset(K key, S session) { Entry<S, V> entry = matching(key, session); if (entry != null) entry.value = null; }
    public void forget(K key, S session) { if (matching(key, session) != null) sessions.remove(key); }
    public int size() { return sessions.size(); }
    public void clear() { sessions.clear(); }
    private Entry<S, V> matching(K key, S session) {
        Entry<S, V> entry = sessions.get(key); return entry != null && entry.session == session ? entry : null;
    }
    private static final class Entry<S, V> {
        final S session; V value;
        Entry(S session) { this.session = session; }
    }
}
