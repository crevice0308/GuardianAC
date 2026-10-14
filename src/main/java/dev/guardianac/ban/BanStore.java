package dev.guardianac.ban;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonPrimitive;
import com.google.gson.Strictness;
import com.google.gson.stream.JsonReader;
import com.google.gson.stream.JsonToken;

import java.io.IOException;
import java.io.StringReader;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.time.Clock;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/**
 * Indexed ban storage with synchronized mutations and lock-free snapshot reads.
 * Call disk-writing methods off the server thread.
 * A failed load disables reads and writes until a successful reload, so corrupt data
 * cannot silently become an empty ban list. Mutations publish only after an atomic,
 * fsynced replacement. Filesystems without atomic replacement are rejected.
 */
public final class BanStore {
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().disableHtmlEscaping().create();
    private static final long MAX_BYTES = 64L * 1024 * 1024;
    private static final Set<String> ROOT_KEYS = Set.of("version", "bans");
    private static final Set<String> RECORD_KEYS = Set.of("id", "playerId", "playerName", "address", "reason", "source", "createdAt", "expiresAt");
    private final Path file;
    private final Clock clock;
    private record Snapshot(Map<UUID, BanRecord> players, Map<String, List<BanRecord>> addresses,
                            Map<String, Set<UUID>> names) {}
    private volatile Snapshot snapshot;

    public BanStore(Path file, Clock clock) {
        this.file = Objects.requireNonNull(file, "file").toAbsolutePath().normalize();
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    public synchronized void load() throws IOException {
        snapshot = null;
        if (!Files.exists(file)) {
            // exists() also returns false when access cannot be checked. Only a definite
            // absence is safe to treat as a new store.
            if (!Files.notExists(file)) throw new IOException("Cannot inspect ban storage");
            publish(Map.of());
            return;
        }
        try {
            if (!Files.isRegularFile(file) || Files.size(file) > MAX_BYTES)
                throw new IOException("Ban storage is not a supported regular file");
            String json = Files.readString(file, StandardCharsets.UTF_8);
            if (json.length() > MAX_BYTES) throw new IOException("Ban storage is too large");
            JsonReader reader = new JsonReader(new StringReader(json));
            reader.setStrictness(Strictness.STRICT);
            JsonElement root = readJson(reader, 0);
            if (reader.peek() != JsonToken.END_DOCUMENT || !root.isJsonObject())
                throw new IOException("Invalid ban storage document");
            JsonObject document = root.getAsJsonObject();
            if (!document.keySet().equals(ROOT_KEYS) || number(document, "version") != 1)
                throw new IOException("Unsupported ban storage schema");
            if (!document.get("bans").isJsonArray()) throw new IOException("Invalid ban list");
            Map<UUID, BanRecord> next = new LinkedHashMap<>();
            Set<String> ids = new HashSet<>();
            for (JsonElement element : document.getAsJsonArray("bans")) {
                if (!element.isJsonObject()) throw new IOException("Invalid ban record");
                JsonObject value = element.getAsJsonObject();
                if (!RECORD_KEYS.containsAll(value.keySet()) || !value.keySet().containsAll(Set.of("id", "playerId", "playerName", "reason", "source", "createdAt", "expiresAt")))
                    throw new IOException("Invalid ban record fields");
                String uuid = string(value, "playerId");
                if (!isUuid(uuid)) throw new IOException("Invalid player UUID in ban storage");
                String address = value.has("address") && !value.get("address").isJsonNull() ? string(value, "address") : null;
                BanRecord record = new BanRecord(string(value, "id"), UUID.fromString(uuid), string(value, "playerName"),
                        address, string(value, "reason"), string(value, "source"), number(value, "createdAt"), number(value, "expiresAt"));
                if (!ids.add(record.id()) || next.putIfAbsent(record.playerId(), record) != null)
                    throw new IOException("Duplicate ban record");
            }
            publish(next);
        } catch (IllegalArgumentException | IllegalStateException exception) {
            throw new IOException("Ban storage is corrupt or unsupported", exception);
        }
    }

    public synchronized BanRecord ban(UUID playerId, String name, String optionalAddress, String reason,
                                       String source, long durationMillis) throws IOException {
        Snapshot current = requireLoaded();
        if (durationMillis < 0) throw new IllegalArgumentException("Ban duration cannot be negative");
        long now = clock.millis();
        long expiry = durationMillis == 0 ? 0 : Math.addExact(now, durationMillis);
        BanRecord record = new BanRecord(UUID.randomUUID().toString(), playerId, name, optionalAddress, reason, source, now, expiry);
        Map<UUID, BanRecord> next = new LinkedHashMap<>(current.players());
        next.put(playerId, record);
        persist(next);
        publish(next);
        return record;
    }

    /** Looks up UUID first, then an optional address. Null UUID supports address-only lookup. */
    public Optional<BanRecord> find(UUID playerId, String address) {
        Snapshot current = requireLoaded();
        long now = clock.millis();
        BanRecord player = playerId == null ? null : current.players().get(playerId);
        if (player != null && player.isActive(now)) return Optional.of(player);
        if (address == null) return Optional.empty();
        List<BanRecord> matches = current.addresses().get(IpAddress.normalize(address));
        if (matches == null) return Optional.empty();
        return matches.stream().filter(record -> record.isActive(now)).findFirst();
    }

    /** Removes matching player records, including their linked IP bans, active or expired. */
    public synchronized int unbanPlayer(String nameOrUuid) throws IOException {
        Snapshot current = requireLoaded();
        Objects.requireNonNull(nameOrUuid, "nameOrUuid");
        Set<UUID> matches;
        if (isUuid(nameOrUuid)) matches = Set.of(UUID.fromString(nameOrUuid));
        else {
            BanRecord.validateName(nameOrUuid);
            matches = current.names().getOrDefault(nameOrUuid.toLowerCase(Locale.ROOT), Set.of());
        }
        Map<UUID, BanRecord> next = new LinkedHashMap<>(current.players());
        int removed = 0;
        for (UUID id : matches) if (next.remove(id) != null) removed++;
        if (removed != 0) { persist(next); publish(next); }
        return removed;
    }

    /** Clears only linked IP bans. UUID bans are retained, including for shared addresses. */
    public synchronized int unbanAddress(String address) throws IOException {
        Snapshot current = requireLoaded();
        String canonical = IpAddress.normalize(address);
        List<BanRecord> matches = current.addresses().getOrDefault(canonical, List.of());
        if (matches.isEmpty()) return 0;
        Map<UUID, BanRecord> next = new LinkedHashMap<>(current.players());
        for (BanRecord record : matches) next.put(record.playerId(), new BanRecord(record.id(), record.playerId(), record.playerName(), null,
                record.reason(), record.source(), record.createdAt(), record.expiresAt()));
        persist(next);
        publish(next);
        return matches.size();
    }

    public List<BanRecord> listActive() {
        Snapshot current = requireLoaded();
        long now = clock.millis();
        return current.players().values().stream().filter(record -> record.isActive(now)).sorted(order()).toList();
    }

    private Snapshot requireLoaded() {
        Snapshot current = snapshot;
        if (current == null) throw new IllegalStateException("Ban storage has not been loaded successfully");
        return current;
    }

    private void publish(Map<UUID, BanRecord> next) {
        Map<String, List<BanRecord>> nextAddresses = new HashMap<>();
        Map<String, Set<UUID>> nextNames = new HashMap<>();
        for (BanRecord record : next.values()) {
            if (record.address() != null) nextAddresses.computeIfAbsent(record.address(), key -> new ArrayList<>()).add(record);
            nextNames.computeIfAbsent(record.playerName().toLowerCase(Locale.ROOT), key -> new HashSet<>()).add(record.playerId());
        }
        nextAddresses.replaceAll((address, records) -> records.stream().sorted(order()).toList());
        nextNames.replaceAll((name, ids) -> Set.copyOf(ids));
        snapshot = new Snapshot(Map.copyOf(next), Map.copyOf(nextAddresses), Map.copyOf(nextNames));
    }

    private void persist(Map<UUID, BanRecord> next) throws IOException {
        JsonObject document = new JsonObject();
        document.addProperty("version", 1);
        JsonArray bans = new JsonArray();
        for (BanRecord record : next.values().stream().sorted(order()).toList()) {
            JsonObject value = new JsonObject();
            value.addProperty("id", record.id());
            value.addProperty("playerId", record.playerId().toString());
            value.addProperty("playerName", record.playerName());
            if (record.address() != null) value.addProperty("address", record.address());
            value.addProperty("reason", record.reason());
            value.addProperty("source", record.source());
            value.addProperty("createdAt", record.createdAt());
            value.addProperty("expiresAt", record.expiresAt());
            bans.add(value);
        }
        document.add("bans", bans);
        byte[] bytes = (GSON.toJson(document) + "\n").getBytes(StandardCharsets.UTF_8);
        if (bytes.length > MAX_BYTES) throw new IOException("Ban storage exceeds size limit");
        Path parent = file.getParent();
        Files.createDirectories(parent);
        Path temporary = Files.createTempFile(parent, ".guardian-bans-", ".tmp");
        try {
            try (FileChannel channel = FileChannel.open(temporary, StandardOpenOption.WRITE, StandardOpenOption.TRUNCATE_EXISTING)) {
                ByteBuffer buffer = ByteBuffer.wrap(bytes);
                while (buffer.hasRemaining()) channel.write(buffer);
                channel.force(true);
            }
            try {
                Files.move(temporary, file, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
            } catch (AtomicMoveNotSupportedException exception) {
                throw new IOException("Ban storage filesystem must support atomic replacement", exception);
            }
            // Atomic rename plus durable file contents: directory fsync is best effort,
            // since Windows and some Java filesystem providers cannot open directories.
            try (FileChannel directory = FileChannel.open(parent, StandardOpenOption.READ)) {
                directory.force(true);
            } catch (IOException | UnsupportedOperationException ignored) {
                // The committed file is already atomically visible; do not report failure
                // after commit and leave memory inconsistent with the committed file.
            }
        } finally {
            Files.deleteIfExists(temporary);
        }
    }

    private static Comparator<BanRecord> order() {
        return Comparator.comparingLong(BanRecord::createdAt).reversed().thenComparing(BanRecord::id);
    }

    private static JsonElement readJson(JsonReader reader, int depth) throws IOException {
        if (depth > 16) throw new IOException("Ban storage nesting is too deep");
        return switch (reader.peek()) {
            case BEGIN_OBJECT -> {
                reader.beginObject();
                JsonObject result = new JsonObject();
                while (reader.hasNext()) {
                    String key = reader.nextName();
                    if (result.has(key)) throw new IOException("Duplicate ban storage field");
                    result.add(key, readJson(reader, depth + 1));
                }
                reader.endObject();
                yield result;
            }
            case BEGIN_ARRAY -> {
                reader.beginArray();
                JsonArray result = new JsonArray();
                while (reader.hasNext()) result.add(readJson(reader, depth + 1));
                reader.endArray();
                yield result;
            }
            case STRING -> new JsonPrimitive(reader.nextString());
            case NUMBER -> new JsonPrimitive(new java.math.BigDecimal(reader.nextString()));
            case BOOLEAN -> new JsonPrimitive(reader.nextBoolean());
            case NULL -> { reader.nextNull(); yield com.google.gson.JsonNull.INSTANCE; }
            default -> throw new IOException("Invalid ban storage JSON");
        };
    }

    private static boolean isUuid(String value) {
        return value != null && value.matches("[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}");
    }

    private static String string(JsonObject object, String key) throws IOException {
        JsonElement value = object.get(key);
        if (value == null || !value.isJsonPrimitive() || !value.getAsJsonPrimitive().isString())
            throw new IOException("Invalid ban storage text field");
        return value.getAsString();
    }

    private static long number(JsonObject object, String key) throws IOException {
        JsonElement value = object.get(key);
        if (value == null || !value.isJsonPrimitive() || !value.getAsJsonPrimitive().isNumber())
            throw new IOException("Invalid ban storage numeric field");
        try { return value.getAsBigDecimal().longValueExact(); }
        catch (ArithmeticException exception) { throw new IOException("Invalid ban storage integer", exception); }
    }
}
