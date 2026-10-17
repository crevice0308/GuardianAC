// SPDX-License-Identifier: GPL-3.0-only
package dev.guardianac.core;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonNull;
import com.google.gson.JsonObject;
import com.google.gson.JsonPrimitive;
import com.google.gson.Strictness;
import com.google.gson.stream.JsonReader;
import com.google.gson.stream.JsonToken;
import dev.guardianac.checks.CheckCatalog;
import java.io.IOException;
import java.io.StringReader;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeSet;
import java.util.UUID;

/** Versioned immutable automatic-decision evidence; legacy summaries cannot authorize IP review. */
public final class PersistedBanEvidence {
    private static final int MAX_BYTES = 1024 * 1024, MAX_FINDINGS = 256, MAX_REPLAY = 120;
    private PersistedBanEvidence() { }

    public record Snapshot(UUID playerId, BanEvidence evidence, AutomaticBanGate.Policy capturedPolicy,
                           String replayJson) {
        public Snapshot {
            Objects.requireNonNull(playerId); Objects.requireNonNull(evidence);
            Objects.requireNonNull(capturedPolicy); Objects.requireNonNull(replayJson);
            if (evidence.capturedAtMillis() < 0 || evidence.findings().size() > MAX_FINDINGS)
                throw invalid();
            Set<String> seen = new HashSet<>();
            for (Finding finding : evidence.findings()) {
                if (finding == null || !seen.add(finding.checkId()) || !playerId.equals(finding.sample().playerId())
                    || !Double.isFinite(finding.confidence()) || finding.confidence() < 0 || finding.confidence() > 1
                    || !Double.isFinite(finding.violationLevel()) || finding.violationLevel() < 0 || finding.violationLevel() > 500
                    || finding.occurrences() < 1 || finding.occurrences() > 1_000_000
                    || finding.firstSeenMillis() < 0 || finding.lastSeenMillis() < finding.firstSeenMillis()
                    || finding.lastSeenMillis() > evidence.capturedAtMillis()
                    || finding.sample().timeMillis() != finding.lastSeenMillis()) throw invalid();
                textValue(finding.checkId(), 128); textValue(finding.family(), 128);
            }
            if (capturedPolicy.enabledChecks().size() > MAX_FINDINGS) throw invalid();
            for (String id : capturedPolicy.enabledChecks()) textValue(id, 128);
            textValue(capturedPolicy.sanctionPolicy(), 4096);
        }

        /** Historical decision sufficiency, at capture time, under original AND current safeguards. */
        public DetectionEngine.Evaluation evaluate(List<Check> checks, AutomaticBanGate.Policy currentPolicy) {
            Objects.requireNonNull(checks); Objects.requireNonNull(currentPolicy);
            Map<String, Check> known = new HashMap<>();
            for (Check check : checks) {
                if (known.put(check.id(), check) != null) throw new IllegalArgumentException("Duplicate check ID");
            }
            List<Finding> verified = new ArrayList<>();
            for (Finding finding : evidence.findings()) {
                Check check = known.get(finding.checkId());
                if (check == null || !check.hardInvariant() || !finding.hardInvariant()
                    || check.confidence() < .995 || finding.confidence() < .995
                    || !check.family().equals(finding.family())
                    || !capturedPolicy.enabledChecks().contains(check.id())
                    || !currentPolicy.enabledChecks().contains(check.id())
                    || !check.test(finding.sample())) continue;
                verified.add(finding);
            }
            BanEvidence safe = new BanEvidence(verified, evidence.capturedAtMillis());
            DetectionEngine.Evaluation source = safe.evaluate(checks, capturedPolicy, evidence.capturedAtMillis());
            DetectionEngine.Evaluation current = safe.evaluate(checks, currentPolicy, evidence.capturedAtMillis());
            if (source.banRecommended() && current.banRecommended()) return current;
            return new DetectionEngine.Evaluation(List.of(), false,
                Math.min(source.totalVl(), current.totalVl()), Math.min(source.independentFamilies(), current.independentFamilies()));
        }

        @Override public String toString() {
            return "PersistedBanEvidence[playerId=" + playerId + ", capturedAt=" + evidence.capturedAtMillis()
                + ", findings=" + evidence.findings().size() + "]";
        }
    }

    /** Serializes the supplied frozen decision; never reads mutable engine state. */
    public static String encode(UUID playerId, BanEvidence evidence, AutomaticBanGate.Policy policy, String replayJson) {
        JsonArray replay = array(parseJson(replayJson), MAX_REPLAY);
        for (JsonElement frame : replay) readSample(object(frame), playerId);
        Snapshot snapshot = new Snapshot(playerId, evidence, policy, replay.toString());
        JsonObject root = new JsonObject(); root.addProperty("version", 2); root.addProperty("player", playerId.toString());
        root.addProperty("capturedAt", evidence.capturedAtMillis());
        DetectionEngine.Evaluation summary = snapshot.evaluate(CheckCatalog.create(), policy);
        root.addProperty("families", summary.independentFamilies()); root.addProperty("vl", summary.totalVl());
        JsonObject captured = new JsonObject();
        captured.addProperty("enabled", policy.enabled()); captured.addProperty("supportedRuntime", policy.supportedRuntime());
        captured.addProperty("preset", policy.preset().name()); captured.addProperty("sanctionPolicy", policy.sanctionPolicy());
        JsonArray enabled = new JsonArray(); new TreeSet<>(policy.enabledChecks()).forEach(enabled::add);
        captured.add("enabledChecks", enabled); root.add("policy", captured);
        JsonArray findings = new JsonArray();
        for (Finding finding : evidence.findings()) {
            JsonObject value = new JsonObject();
            value.addProperty("check", finding.checkId()); value.addProperty("family", finding.family());
            value.addProperty("hard", finding.hardInvariant()); value.addProperty("confidence", finding.confidence());
            value.addProperty("count", finding.occurrences()); value.addProperty("findingVL", finding.violationLevel());
            value.addProperty("firstSeen", finding.firstSeenMillis()); value.addProperty("lastSeen", finding.lastSeenMillis());
            value.add("observation", sampleJson(finding.sample())); findings.add(value);
        }
        root.add("findings", findings); root.add("replay", replay);
        String json = root.toString(); checkBytes(json); return json;
    }

    public static Snapshot parse(String json, UUID expectedPlayer) {
        Objects.requireNonNull(expectedPlayer);
        try {
            JsonObject root = object(parseJson(json));
            if (integer(root, "version") != 2) throw invalid();
            UUID player = UUID.fromString(text(root, "player", 36));
            if (!player.equals(expectedPlayer)) throw invalid();
            long capturedAt = integer(root, "capturedAt");
            JsonObject captured = object(required(root, "policy"));
            Set<String> checks = new HashSet<>();
            for (JsonElement id : array(required(captured, "enabledChecks"), MAX_FINDINGS)) {
                if (!checks.add(text(id, 128))) throw invalid();
            }
            AutomaticBanGate.Policy policy = new AutomaticBanGate.Policy(bool(captured, "enabled"),
                bool(captured, "supportedRuntime"), Preset.valueOf(text(captured, "preset", 16)),
                checks, text(captured, "sanctionPolicy", 4096));
            List<Finding> findings = new ArrayList<>();
            for (JsonElement entry : array(required(root, "findings"), MAX_FINDINGS)) {
                JsonObject finding = object(entry);
                long count = integer(finding, "count");
                if (count < 1 || count > 1_000_000) throw invalid();
                findings.add(new Finding(text(finding, "check", 128), text(finding, "family", 128),
                    bool(finding, "hard"), decimal(finding, "confidence"), decimal(finding, "findingVL"), (int) count,
                    integer(finding, "firstSeen"), integer(finding, "lastSeen"),
                    readSample(object(required(finding, "observation")), player)));
            }
            JsonArray replay = array(required(root, "replay"), MAX_REPLAY);
            for (JsonElement frame : replay) readSample(object(frame), player);
            return new Snapshot(player, new BanEvidence(findings, capturedAt), policy, replay.toString());
        } catch (IllegalArgumentException | IllegalStateException failure) { throw invalid(); }
    }

    private static JsonObject sampleJson(Sample sample) {
        JsonObject value = new JsonObject(); value.addProperty("time", sample.timeMillis()); value.addProperty("tick", sample.tick());
        value.addProperty("kind", sample.kind().name()); JsonObject measurements = new JsonObject();
        new java.util.TreeMap<>(sample.measurements()).forEach((key, number) -> {
            if (Double.isFinite(number)) measurements.addProperty(key, number);
            else measurements.addProperty(key, number.toString());
        });
        value.add("measurements", measurements); JsonArray flags = new JsonArray();
        new TreeSet<>(sample.flags()).forEach(flags::add); value.add("flags", flags); return value;
    }
    private static Sample readSample(JsonObject value, UUID player) {
        long time = integer(value, "time"), tick = integer(value, "tick");
        if (time < 0 || tick < 0) throw invalid();
        Sample.Kind kind = Sample.Kind.valueOf(text(value, "kind", 16));
        JsonObject raw = object(required(value, "measurements")); if (raw.size() > 96) throw invalid();
        Map<String, Double> measurements = new LinkedHashMap<>();
        for (Map.Entry<String, JsonElement> entry : raw.entrySet()) {
            textValue(entry.getKey(), 128); JsonElement number = entry.getValue();
            if (!number.isJsonPrimitive()) throw invalid();
            JsonPrimitive primitive = number.getAsJsonPrimitive(); double parsed;
            if (primitive.isNumber()) { parsed = primitive.getAsDouble(); if (!Double.isFinite(parsed)) throw invalid(); }
            else if (primitive.isString()) parsed = switch (primitive.getAsString()) {
                case "NaN" -> Double.NaN; case "Infinity" -> Double.POSITIVE_INFINITY;
                case "-Infinity" -> Double.NEGATIVE_INFINITY; default -> throw invalid();
            };
            else throw invalid();
            measurements.put(entry.getKey(), parsed);
        }
        Set<String> flags = new HashSet<>();
        for (JsonElement flag : array(required(value, "flags"), 32)) if (!flags.add(text(flag, 256))) throw invalid();
        return new Sample(player, time, tick, kind, measurements, flags);
    }

    /** Strict bounded JSON reader also rejects duplicate object keys, unlike a permissive tree parse. */
    private static JsonElement parseJson(String json) {
        checkBytes(json);
        try (JsonReader reader = new JsonReader(new StringReader(json))) {
            reader.setStrictness(Strictness.STRICT);
            JsonElement value = readJson(reader, 0, new int[] {0});
            if (reader.peek() != JsonToken.END_DOCUMENT) throw invalid();
            return value;
        } catch (IOException | IllegalStateException | NumberFormatException failure) { throw invalid(); }
    }
    private static JsonElement readJson(JsonReader reader, int depth, int[] nodes) throws IOException {
        if (depth > 24 || ++nodes[0] > 50_000) throw invalid();
        return switch (reader.peek()) {
            case BEGIN_OBJECT -> {
                reader.beginObject(); JsonObject object = new JsonObject(); Set<String> seen = new HashSet<>();
                while (reader.hasNext()) {
                    String key = reader.nextName(); if (key.length() > 256 || !seen.add(key)) throw invalid();
                    object.add(key, readJson(reader, depth + 1, nodes));
                }
                reader.endObject(); yield object;
            }
            case BEGIN_ARRAY -> {
                reader.beginArray(); JsonArray array = new JsonArray();
                while (reader.hasNext()) { if (array.size() >= 10_000) throw invalid(); array.add(readJson(reader, depth + 1, nodes)); }
                reader.endArray(); yield array;
            }
            case STRING -> new JsonPrimitive(reader.nextString());
            case NUMBER -> {
                String number = reader.nextString(); if (number.length() > 128) throw invalid();
                yield new JsonPrimitive(new BigDecimal(number));
            }
            case BOOLEAN -> new JsonPrimitive(reader.nextBoolean());
            case NULL -> { reader.nextNull(); yield JsonNull.INSTANCE; }
            default -> throw invalid();
        };
    }
    private static void checkBytes(String json) {
        if (json == null || json.length() > MAX_BYTES || json.getBytes(StandardCharsets.UTF_8).length > MAX_BYTES) throw invalid();
    }
    private static JsonElement required(JsonObject value, String key) {
        JsonElement result = value.get(key); if (result == null || result.isJsonNull()) throw invalid(); return result;
    }
    private static JsonObject object(JsonElement value) { if (!value.isJsonObject()) throw invalid(); return value.getAsJsonObject(); }
    private static JsonArray array(JsonElement value, int maximum) {
        if (!value.isJsonArray() || value.getAsJsonArray().size() > maximum) throw invalid(); return value.getAsJsonArray();
    }
    private static String text(JsonObject value, String key, int max) { return text(required(value, key), max); }
    private static String text(JsonElement value, int max) {
        if (!value.isJsonPrimitive() || !value.getAsJsonPrimitive().isString()) throw invalid();
        return textValue(value.getAsString(), max);
    }
    private static String textValue(String value, int max) {
        if (value == null || value.isBlank() || value.length() > max || value.chars().anyMatch(c -> c < 32)) throw invalid(); return value;
    }
    private static boolean bool(JsonObject value, String key) {
        JsonElement raw = required(value, key);
        if (!raw.isJsonPrimitive() || !raw.getAsJsonPrimitive().isBoolean()) throw invalid(); return raw.getAsBoolean();
    }
    private static long integer(JsonObject value, String key) {
        JsonElement raw = required(value, key);
        if (!raw.isJsonPrimitive() || !raw.getAsJsonPrimitive().isNumber()) throw invalid();
        try { return raw.getAsBigDecimal().longValueExact(); } catch (ArithmeticException failure) { throw invalid(); }
    }
    private static double decimal(JsonObject value, String key) {
        JsonElement raw = required(value, key);
        if (!raw.isJsonPrimitive() || !raw.getAsJsonPrimitive().isNumber()) throw invalid();
        double result = raw.getAsDouble(); if (!Double.isFinite(result)) throw invalid(); return result;
    }
    private static IllegalArgumentException invalid() { return new IllegalArgumentException("Invalid immutable automatic-ban evidence"); }
}
