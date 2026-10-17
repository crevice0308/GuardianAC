// SPDX-License-Identifier: GPL-3.0-only
package dev.guardianac.core;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import dev.guardianac.checks.CheckCatalog;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.function.Consumer;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class PersistedBanEvidenceTest {
    private static final UUID PLAYER = UUID.fromString("01234567-89ab-4def-8123-456789abcdef");
    private static final long CAPTURED = 1_800_000_000_000L;
    private static final Set<String> IDS = Set.of("packet.pitch", "packet.held-slot");
    private static AutomaticBanGate.Policy policy(Preset preset) {
        return new AutomaticBanGate.Policy(true, true, preset, IDS, "DUAL:24:0:true");
    }
    private static Finding finding(String id, String family, String key, double measurement, String flag) {
        Sample sample = Sample.builder(PLAYER, Sample.Kind.PACKET, CAPTURED, 200)
            .value(key, measurement).flag(flag).build();
        return new Finding(id, family, true, .995, 80, 12, CAPTURED - 6_000, CAPTURED, sample);
    }
    private static BanEvidence evidence() {
        return new BanEvidence(List.of(
            finding("packet.pitch", "PROTOCOL_MOVEMENT", "pitch", 100, "rotation-present"),
            finding("packet.held-slot", "PROTOCOL_INVENTORY", "held-slot", 15, "held-slot-present")), CAPTURED);
    }
    private static String json() { return PersistedBanEvidence.encode(PLAYER, evidence(), policy(Preset.STRICT), "[]"); }
    private static JsonObject first(JsonObject root) { return root.getAsJsonArray("findings").get(0).getAsJsonObject(); }
    private static String changed(Consumer<JsonObject> change) {
        JsonObject root = JsonParser.parseString(json()).getAsJsonObject(); change.accept(root); return root.toString();
    }
    private static boolean eligible(String json) {
        return PersistedBanEvidence.parse(json, PLAYER).evaluate(CheckCatalog.create(), policy(Preset.STRICT)).banRecommended();
    }

    @Test void twoRealCatalogFamiliesRoundTripAndRetainReaderFields() {
        String json = json(); var snapshot = PersistedBanEvidence.parse(json, PLAYER);
        assertEquals(evidence(), snapshot.evidence()); assertEquals(policy(Preset.STRICT), snapshot.capturedPolicy());
        var evaluation = snapshot.evaluate(CheckCatalog.create(), policy(Preset.STRICT));
        assertTrue(evaluation.banRecommended()); assertEquals(2, evaluation.independentFamilies()); assertEquals(160, evaluation.totalVl());
        JsonObject root = JsonParser.parseString(json).getAsJsonObject();
        assertEquals(2, root.get("families").getAsInt()); assertEquals(160, root.get("vl").getAsDouble());
        assertTrue(root.has("player") && root.has("findings") && root.has("replay"));
        for (String field : List.of("check", "family", "hard", "confidence", "count", "observation", "firstSeen", "lastSeen", "findingVL"))
            assertTrue(first(root).has(field), field);
    }
    @Test void reviewUsesHistoricalCaptureTimeAndDoesNotDecayAfterTwoMinutes() {
        var snapshot = PersistedBanEvidence.parse(json(), PLAYER);
        assertFalse(snapshot.evidence().evaluate(CheckCatalog.create(), policy(Preset.STRICT), CAPTURED + 3_600_000).banRecommended());
        assertTrue(snapshot.evaluate(CheckCatalog.create(), policy(Preset.STRICT)).banRecommended());
    }
    @Test void capturedAndCurrentPolicyBothHaveToQualify() {
        var snapshot = PersistedBanEvidence.parse(json(), PLAYER);
        assertFalse(snapshot.evaluate(CheckCatalog.create(), policy(Preset.RELAXED)).banRecommended());
        assertFalse(snapshot.evaluate(CheckCatalog.create(), new AutomaticBanGate.Policy(true, true, Preset.STRICT,
            Set.of("packet.pitch"), "DUAL:24:0:true")).banRecommended());
        String originalDisabled = changed(root -> root.getAsJsonObject("policy").addProperty("enabled", false));
        assertFalse(eligible(originalDisabled));
    }
    @Test void sourceFindingsAreFrozenIndependentOfCallerList() {
        var mutable = new java.util.ArrayList<>(evidence().findings()); BanEvidence frozen = new BanEvidence(mutable, CAPTURED);
        mutable.clear(); assertEquals(2, PersistedBanEvidence.parse(PersistedBanEvidence.encode(PLAYER, frozen,
            policy(Preset.STRICT), "[]"), PLAYER).evidence().findings().size());
    }
    @Test void forgedSummaryCountsDoNotQualifyAnInvalidPredicate() {
        assertFalse(eligible(changed(root -> {
            root.addProperty("families", 999); root.addProperty("vl", 999_999);
            first(root).getAsJsonObject("observation").getAsJsonObject("measurements").addProperty("pitch", 0);
        })));
    }
    @Test void forgedFamilyHardConfidenceAndInsufficientCountsFail() {
        assertFalse(eligible(changed(root -> first(root).addProperty("family", "FORGED"))));
        assertFalse(eligible(changed(root -> first(root).addProperty("hard", false))));
        assertFalse(eligible(changed(root -> first(root).addProperty("confidence", .99))));
        assertFalse(eligible(changed(root -> first(root).addProperty("count", 5))));
        assertFalse(eligible(changed(root -> first(root).getAsJsonObject("observation").getAsJsonArray("flags").add("unsupported-protocol"))));
    }
    @Test void sameFamilyDoesNotBecomeTwoIndependentFamilies() {
        Finding pitch = evidence().findings().getFirst();
        Sample bad = Sample.builder(PLAYER, Sample.Kind.PACKET, CAPTURED, 200)
            .flag("rotation-present").value("yaw", Double.NaN).value("pitch", 100).build();
        Finding rotation = new Finding("packet.nonfinite-rotation", "PROTOCOL_MOVEMENT", true, .995, 80, 12,
            CAPTURED - 6_000, CAPTURED, bad);
        var current = new AutomaticBanGate.Policy(true, true, Preset.STRICT,
            Set.of("packet.pitch", "packet.nonfinite-rotation"), "DUAL:24:0:true");
        String encoded = PersistedBanEvidence.encode(PLAYER, new BanEvidence(List.of(pitch, rotation), CAPTURED), current, "[]");
        var snapshot = PersistedBanEvidence.parse(encoded, PLAYER);
        assertFalse(snapshot.evaluate(CheckCatalog.create(), current).banRecommended());
        assertTrue(Double.isNaN(snapshot.evidence().findings().get(1).sample().v("yaw")));
    }
    @Test void wrongPlayerLegacyMissingFieldsAndDuplicateChecksAreRejected() {
        assertThrows(IllegalArgumentException.class, () -> PersistedBanEvidence.parse(json(), UUID.randomUUID()));
        for (String field : List.of("capturedAt", "policy", "findings", "replay"))
            assertThrows(IllegalArgumentException.class, () -> eligible(changed(root -> root.remove(field))));
        assertThrows(IllegalArgumentException.class, () -> eligible(changed(root -> root.addProperty("version", 1))));
        assertThrows(IllegalArgumentException.class, () -> eligible(changed(root -> root.getAsJsonArray("findings").add(first(root).deepCopy()))));
        assertThrows(IllegalArgumentException.class, () -> eligible(changed(root -> first(root).remove("firstSeen"))));
    }
    @Test void malformedNumbersTimestampsAndDuplicatesAreRejected() {
        assertThrows(IllegalArgumentException.class, () -> eligible(changed(root -> first(root).addProperty("count", 6.1))));
        assertThrows(IllegalArgumentException.class, () -> eligible(changed(root -> first(root).addProperty("lastSeen", CAPTURED + 1))));
        assertThrows(IllegalArgumentException.class, () -> eligible(changed(root -> first(root).addProperty("firstSeen", CAPTURED + 1))));
        assertThrows(IllegalArgumentException.class, () -> eligible(changed(root -> first(root).addProperty("findingVL", -1))));
        assertThrows(IllegalArgumentException.class, () -> eligible(json().replace("\"version\":2", "\"version\":1,\"version\":2")));
        assertThrows(IllegalArgumentException.class, () -> eligible(json() + "{}"));
        assertThrows(IllegalArgumentException.class, () -> eligible(" ".repeat(1024 * 1024 + 1)));
    }
    @Test void failureMessagesNeverEchoPayload() {
        IllegalArgumentException error = assertThrows(IllegalArgumentException.class,
            () -> PersistedBanEvidence.parse("{\"ip\":\"192.0.2.70\"}", PLAYER));
        assertFalse(error.toString().contains("192.0.2.70"));
    }
}
