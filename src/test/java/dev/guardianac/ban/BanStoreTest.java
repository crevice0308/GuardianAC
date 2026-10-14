package dev.guardianac.ban;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

class BanStoreTest {
    @TempDir Path directory;
    private final MutableClock clock = new MutableClock(1_000_000);

    private Path file() { return directory.resolve("bans.json"); }

    private BanStore open() throws IOException {
        BanStore store = new BanStore(file(), clock);
        store.load();
        return store;
    }

    @Test void requiresSuccessfulLoadBeforeReadingOrWriting() {
        BanStore store = new BanStore(file(), clock);
        assertThrows(IllegalStateException.class, store::listActive);
        assertThrows(IllegalStateException.class, () -> store.find(UUID.randomUUID(), null));
        assertThrows(IllegalStateException.class, () -> store.ban(UUID.randomUUID(), "Alice", null, "Reach", "test", 0));
    }

    @Test void persistsPermanentPlayerAndLinkedIpBanAcrossRestart() throws Exception {
        BanStore store = open();
        UUID player = UUID.randomUUID();
        BanRecord record = store.ban(player, "Alice", "2001:db8::1", "Reach \"test\" 日本語", "GuardianAC", 0);
        BanStore restarted = open();
        assertEquals(record, restarted.find(player, null).orElseThrow());
        assertEquals(record, restarted.find(null, "2001:0db8:0:0:0:0:0:1").orElseThrow());
        assertEquals(record, restarted.find(UUID.randomUUID(), "2001:db8::1").orElseThrow());
        assertEquals(0, record.expiresAt());
        assertEquals(List.of(record), restarted.listActive());
        try (var paths = Files.list(directory)) {
            assertEquals(List.of("bans.json"), paths.map(path -> path.getFileName().toString()).toList());
        }
    }

    @Test void temporaryBansExpireAtBoundaryForBothPlayerAndAddress() throws Exception {
        BanStore store = open();
        UUID player = UUID.randomUUID();
        BanRecord record = store.ban(player, "Alice", "192.0.2.1", "Speed", "test", 500);
        assertEquals(clock.millis() + 500, record.expiresAt());
        clock.now += 499;
        assertTrue(store.find(player, null).isPresent());
        clock.now++;
        assertTrue(store.find(player, null).isEmpty());
        assertTrue(store.find(null, "192.0.2.1").isEmpty());
        assertTrue(store.listActive().isEmpty());
        assertTrue(open().listActive().isEmpty());
    }

    @Test void permanentBansDoNotExpire() throws Exception {
        BanStore store = open();
        UUID player = UUID.randomUUID();
        store.ban(player, "Alice", null, "Fly", "test", 0);
        clock.now = Long.MAX_VALUE;
        assertTrue(store.find(player, null).isPresent());
    }

    @Test void replacingPlayerBanRemovesOldNameAndAddressIndexes() throws Exception {
        BanStore store = open();
        UUID player = UUID.randomUUID();
        store.ban(player, "OldName", "192.0.2.1", "Reach", "test", 0);
        BanRecord replacement = store.ban(player, "NewName", "192.0.2.2", "Speed", "test", 0);
        assertEquals(1, store.listActive().size());
        assertTrue(store.find(null, "192.0.2.1").isEmpty());
        assertEquals(replacement, store.find(null, "192.0.2.2").orElseThrow());
        assertEquals(0, store.unbanPlayer("oldname"));
        assertEquals(1, store.unbanPlayer("nEwNaMe"));
    }

    @Test void unbanByUuidOrCaseInsensitiveNameRemovesLinkedAddress() throws Exception {
        BanStore store = open();
        UUID first = UUID.randomUUID();
        UUID second = UUID.randomUUID();
        store.ban(first, "Alice", "192.0.2.1", "Fly", "test", 0);
        store.ban(second, "Bob", "192.0.2.2", "Fly", "test", 0);
        assertEquals(1, store.unbanPlayer("aLiCe"));
        assertEquals(1, store.unbanPlayer(second.toString().toUpperCase()));
        assertTrue(open().listActive().isEmpty());
        assertTrue(store.find(null, "192.0.2.1").isEmpty());
        assertTrue(store.find(null, "192.0.2.2").isEmpty());
        assertEquals(0, store.unbanPlayer("Alice"));
    }

    @Test void sharedIpRemainsBannedUntilEveryOwningPlayerBanIsRemoved() throws Exception {
        BanStore store = open();
        store.ban(UUID.randomUUID(), "Alice", "192.0.2.1", "Fly", "test", 0);
        BanRecord bob = store.ban(UUID.randomUUID(), "Bob", "192.0.2.1", "Speed", "test", 0);
        store.unbanPlayer("Alice");
        assertEquals(bob, store.find(null, "192.0.2.1").orElseThrow());
        store.unbanPlayer("Bob");
        assertTrue(store.find(null, "192.0.2.1").isEmpty());
    }

    @Test void unbanAddressOnlyClearsLinkedIpAndPersistsUuidBans() throws Exception {
        BanStore store = open();
        UUID alice = UUID.randomUUID();
        UUID bob = UUID.randomUUID();
        store.ban(alice, "Alice", "2001:db8::1", "Fly", "test", 0);
        store.ban(bob, "Bob", "2001:db8::1", "Speed", "test", 0);
        assertEquals(2, store.unbanAddress("2001:db8:0:0:0:0:0:1"));
        assertTrue(store.find(null, "2001:db8::1").isEmpty());
        BanStore restarted = open();
        assertNull(restarted.find(alice, null).orElseThrow().address());
        assertTrue(restarted.find(bob, null).isPresent());
        assertEquals(0, restarted.unbanAddress("2001:db8::1"));
    }

    @Test void expiredSharedIpOwnerDoesNotHideActiveOwner() throws Exception {
        BanStore store = open();
        BanRecord active = store.ban(UUID.randomUUID(), "Alice", "192.0.2.1", "Fly", "test", 0);
        clock.now++;
        store.ban(UUID.randomUUID(), "Bob", "192.0.2.1", "Speed", "test", 1);
        clock.now++;
        assertEquals(active, store.find(null, "192.0.2.1").orElseThrow());
    }

    @Test void malformedStorageFailsClosedAndIsNeverOverwritten() throws Exception {
        BanStore store = open();
        store.ban(UUID.randomUUID(), "Alice", null, "Fly", "test", 0);
        String corrupt = "{ \"version\": 1, \"bans\": [ garbage ] }";
        Files.writeString(file(), corrupt);
        assertThrows(IOException.class, store::load);
        assertThrows(IllegalStateException.class, store::listActive);
        assertThrows(IllegalStateException.class, () -> store.ban(UUID.randomUUID(), "Bob", null, "Fly", "test", 0));
        assertEquals(corrupt, Files.readString(file()));
    }

    @Test void invalidSchemasAndDuplicateJsonFieldsAreRejected() throws Exception {
        for (String invalid : List.of(
                "", "null", "[]", "{\"version\":2,\"bans\":[]}",
                "{\"version\":1,\"bans\":[],\"extra\":true}",
                "{\"version\":1,\"bans\":[],\"version\":1}",
                "{\"version\":1.5,\"bans\":[]}",
                "{\"version\":\"1\",\"bans\":[]}",
                "{\"version\":1,\"bans\":{}}", "{\"version\":1,\"bans\":[]} true",
                "{\"version\":1,/* comment */\"bans\":[]}")) {
            Files.writeString(file(), invalid);
            BanStore store = new BanStore(file(), clock);
            assertThrows(IOException.class, store::load, invalid);
            assertThrows(IllegalStateException.class, store::listActive);
            assertEquals(invalid, Files.readString(file()));
        }
    }

    @Test void duplicatePlayerRecordsAndInvalidRecordDataAreRejected() throws Exception {
        BanStore store = open();
        store.ban(UUID.randomUUID(), "Alice", null, "Fly", "test", 0);
        String valid = Files.readString(file());
        // Build duplicate records structurally, avoiding assumptions about formatting.
        com.google.gson.JsonObject root = com.google.gson.JsonParser.parseString(valid).getAsJsonObject();
        root.getAsJsonArray("bans").add(root.getAsJsonArray("bans").get(0).deepCopy());
        Files.writeString(file(), root.toString());
        assertThrows(IOException.class, store::load);
        Files.writeString(file(), valid.replace("Alice", "invalid player!"));
        assertThrows(IOException.class, store::load);
    }

    @Test void failedAtomicReplacementDoesNotPublishAnyMutation() throws Exception {
        BanStore store = open();
        UUID alice = UUID.randomUUID();
        BanRecord original = store.ban(alice, "Alice", "192.0.2.1", "Fly", "test", 0);
        Files.move(file(), directory.resolve("backup.json"));
        Files.createDirectory(file());
        Files.writeString(file().resolve("marker"), "do not replace");
        UUID bob = UUID.randomUUID();
        assertThrows(IOException.class, () -> store.ban(bob, "Bob", null, "Speed", "test", 0));
        assertThrows(IOException.class, () -> store.unbanPlayer("Alice"));
        assertThrows(IOException.class, () -> store.unbanAddress("192.0.2.1"));
        assertEquals(original, store.find(alice, null).orElseThrow());
        assertTrue(store.find(bob, null).isEmpty());
        assertEquals(original, store.find(null, "192.0.2.1").orElseThrow());
        assertEquals("do not replace", Files.readString(file().resolve("marker")));
        try (var paths = Files.list(directory)) {
            assertFalse(paths.anyMatch(path -> path.getFileName().toString().endsWith(".tmp")));
        }
    }

    @Test void invalidInputsAndOverflowDoNotChangeStorage() throws Exception {
        BanStore store = open();
        UUID player = UUID.randomUUID();
        assertThrows(IllegalArgumentException.class, () -> store.ban(player, "invalid name", null, "Fly", "test", 0));
        assertThrows(IllegalArgumentException.class, () -> store.ban(player, "Alice", "example.com", "Fly", "test", 0));
        assertThrows(IllegalArgumentException.class, () -> store.ban(player, "Alice", null, "Fly", "test", -1));
        assertThrows(IllegalArgumentException.class, () -> store.ban(player, "Alice", null, "", "test", 0));
        assertThrows(ArithmeticException.class, () -> store.ban(player, "Alice", null, "Fly", "test", Long.MAX_VALUE));
        assertTrue(store.listActive().isEmpty());
        assertFalse(Files.exists(file()));
    }

    @Test void concurrentMutationsRetainEveryRecordAndRestartCleanly() throws Exception {
        BanStore store = open();
        var executor = Executors.newFixedThreadPool(4);
        try {
            List<Callable<BanRecord>> operations = new ArrayList<>();
            for (int index = 0; index < 20; index++) {
                int id = index;
                operations.add(() -> store.ban(UUID.randomUUID(), "Player" + id, "192.0.2.1", "Fly", "test", 0));
            }
            for (var future : executor.invokeAll(operations)) assertNotNull(future.get());
            assertEquals(20, store.listActive().size());
            assertEquals(20, open().listActive().size());
        } finally { executor.shutdownNow(); }
    }

    @Test void loginReadsDoNotWaitForDiskMutationMutex() throws Exception {
        BanStore store = open();
        UUID player = UUID.randomUUID();
        BanRecord record = store.ban(player, "Alice", null, "Fly", "test", 0);
        var executor = Executors.newSingleThreadExecutor();
        try {
            synchronized (store) {
                assertEquals(record, executor.submit(() -> store.find(player, null).orElseThrow()).get(2, TimeUnit.SECONDS));
            }
        } finally { executor.shutdownNow(); }
    }

    private static final class MutableClock extends Clock {
        private volatile long now;
        MutableClock(long now) { this.now = now; }
        @Override public ZoneId getZone() { return ZoneOffset.UTC; }
        @Override public Clock withZone(ZoneId zone) { return this; }
        @Override public long millis() { return now; }
        @Override public Instant instant() { return Instant.ofEpochMilli(now); }
    }
}
