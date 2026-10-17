/* SPDX-License-Identifier: GPL-3.0-only */
package dev.guardianac.bukkit.telemetry;

import java.util.Map;
import java.util.LinkedHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class SessionStateStoreTest {
    private final SessionStateStore<String, Object, Object> store = new SessionStateStore<>(2);
    @Test void observationsCannotOpenUnknownSession() {
        assertNull(store.state("alice", new Object(), () -> { fail("must not allocate"); return null; }));
        assertEquals(0, store.size());
    }
    @Test void quitThenLateCloseAndOtherObservationsCannotResurrect() {
        Object session = new Object(); store.admit("alice", session);
        assertNotNull(store.state("alice", session, Object::new)); store.forget("alice", session);
        assertNull(store.peek("alice", session));
        for (int i = 0; i < 100; i++) assertNull(store.state("alice", session, Object::new));
        store.reset("alice"); assertEquals(0, store.size());
    }
    @Test void sameUuidReconnectRejectsStaleEventsAndStaleQuit() {
        Object old = new Object(), current = new Object(); store.admit("alice", old);
        Object before = store.state("alice", old, Object::new); store.admit("alice", current);
        Object after = store.state("alice", current, Object::new); assertNotSame(before, after);
        store.reset("alice", old); store.forget("alice", old);
        assertNull(store.state("alice", old, Object::new)); assertSame(after, store.peek("alice", current));
    }
    @Test void equalButDifferentSessionTokensDoNotMatch() {
        Object first = new String("same"), second = new String("same"); store.admit("alice", first);
        assertNotNull(store.state("alice", first, Object::new));
        assertNull(store.state("alice", second, Object::new)); store.forget("alice", second); assertEquals(1, store.size());
    }
    @Test void resetRetainsOnlyAdmittedSessionAndReplacesHistory() {
        Object session = new Object(); store.admit("alice", session); Object old = store.state("alice", session, Object::new);
        store.reset("alice"); assertNull(store.peek("alice", session)); assertEquals(1, store.size());
        assertNotSame(old, store.state("alice", session, Object::new));
    }
    @Test void repeatedAdmissionKeepsHistoryAndAllocatesOnce() {
        Object session = new Object(); AtomicInteger allocations = new AtomicInteger(); store.admit("alice", session);
        Object state = store.state("alice", session, () -> { allocations.incrementAndGet(); return new Object(); });
        store.admit("alice", session); assertSame(state, store.state("alice", session, Object::new)); assertEquals(1, allocations.get());
    }
    @Test void boundedAdmissionAndReconciliationRecoverCapacityAfterQuit() {
        Object a = new Object(), b = new Object(), c = new Object();
        assertTrue(store.admit("a", a)); assertTrue(store.admit("b", b)); assertFalse(store.admit("c", c));
        store.forget("a", a); store.reconcile(Map.of("b", b, "c", c));
        assertEquals(2, store.size()); assertNotNull(store.state("c", c, Object::new)); assertNull(store.state("a", a, Object::new));
    }
    @Test void authoritativeSnapshotPrunesMissedQuitsAndKeepsExistingHistory() {
        Object a = new Object(), b = new Object(); store.reconcile(Map.of("a", a, "b", b));
        Object state = store.state("b", b, Object::new); store.reconcile(Map.of("b", b));
        assertEquals(1, store.size()); assertSame(state, store.peek("b", b)); assertNull(store.state("a", a, Object::new));
    }
    @Test void authoritativeSnapshotReplacesChangedSessionEvenAtCapacity() {
        Object a = new Object(), replacement = new Object(), b = new Object(); store.reconcile(Map.of("a", a, "b", b));
        store.reconcile(Map.of("a", replacement, "b", b));
        assertEquals(2, store.size()); assertNull(store.state("a", a, Object::new)); assertNotNull(store.state("a", replacement, Object::new));
    }
    @Test void excessAuthoritativeSessionsNeverExpandCapacity() {
        Map<String, Object> online = new LinkedHashMap<>();
        for (int i = 0; i < 100; i++) online.put("p" + i, new Object());
        store.reconcile(online); assertEquals(2, store.size()); assertNull(store.state("p99", online.get("p99"), Object::new));
    }
    @Test void clearRemovesSessionsAndLateCallbacksCannotRestoreThem() {
        Object session = new Object(); store.admit("alice", session); store.state("alice", session, Object::new); store.clear();
        assertNull(store.state("alice", session, Object::new)); assertEquals(0, store.size());
    }
    @Test void repeatedChurnNeverRetainsDepartedAccounts() {
        for (int i = 0; i < 10000; i++) {
            String id = "p" + i; Object session = new Object(); assertTrue(store.admit(id, session));
            store.state(id, session, Object::new); store.forget(id, session); assertNull(store.state(id, session, Object::new));
        }
        assertEquals(0, store.size());
    }
    @Test void invalidInputsFailBeforeAdmission() {
        assertThrows(IllegalArgumentException.class, () -> new SessionStateStore<>(0));
        assertThrows(IllegalArgumentException.class, () -> new SessionStateStore<>(10001));
        assertThrows(NullPointerException.class, () -> store.admit(null, new Object()));
        assertThrows(NullPointerException.class, () -> store.admit("alice", null)); assertEquals(0, store.size());
    }
}
