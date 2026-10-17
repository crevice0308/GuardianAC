/* SPDX-License-Identifier: GPL-3.0-only */
package dev.guardianac.core;

import dev.guardianac.checks.CheckCatalog;
import java.util.*;
import java.util.stream.Stream;
import org.junit.jupiter.api.*;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import static org.junit.jupiter.api.Assertions.*;

/** Every catalog rule has an intentional violating fixture and an exact boundary/legitimate counterexample. */
class CheckCatalogTest {
    private static final UUID PLAYER = new UUID(10, 20);
    private record Fixture(String id, Map<String, Double> values, Set<String> flags, String negativeKey, double negativeValue, String removedFlag) {
        Sample sample(Check check, boolean negative, String extraFlag) {
            Map<String, Double> data = new HashMap<>(values); data.put("tps", 20d); data.put("ping", 40d);
            Set<String> f = new HashSet<>(flags);
            if (negativeKey != null && negative) data.put(negativeKey, negativeValue);
            if (removedFlag != null && negative) f.remove(removedFlag);
            if (extraFlag != null) f.add(extraFlag);
            return new Sample(PLAYER, 1000, 20, check.kind(), data, f);
        }
    }
    private static Map<String, Double> v(Object... pairs) {
        Map<String, Double> result = new HashMap<>();
        for (int i = 0; i < pairs.length; i += 2) result.put((String)pairs[i], ((Number)pairs[i + 1]).doubleValue());
        return result;
    }
    private static Fixture f(String id, String key, double boundary, Map<String, Double> values, String... flags) { return new Fixture(id, values, Set.of(flags), key, boundary, null); }
    private static Fixture flag(String id, String removed, Map<String, Double> values, String... flags) { return new Fixture(id, values, Set.of(flags), null, 0, removed); }
    private static List<Fixture> fixtures() { return List.of(
        f("combat.reach", "reach", 4, v("reach", 5, "reach-limit", 3)),
        f("combat.hitbox", "ray-miss", .8, v("ray-miss", 1, "reach", 3)),
        flag("combat.wall", "occluded", v("reach", 3), "occluded"),
        f("combat.multitarget", "targets-tick", 3, v("targets-tick", 4)),
        f("combat.click-rate", "attacks-second", 30, v("attacks-second", 31, "attack-window-ms", 1000)),
        f("combat.click-regularity", "interval-stddev", .5, v("interval-samples", 60, "interval-mean", 40, "interval-stddev", .1)),
        f("combat.target-switch-rate", "target-switches-second", 11, v("target-switches-second", 12, "attack-window-ms", 1000)),
        f("combat.aim-snap", "rotation-delta", 150, v("rotation-delta", 160, "aim-error", .01, "reach", 3)),
        f("combat.aim-lock", "aim-samples", 79, v("aim-samples", 80, "target-speed", .2, "aim-error-stddev", .00001)),
        flag("combat.ground-claim", "packet-ground", v("air-ticks", 11, "support-distance", 3), "packet-ground"),
        f("combat.autototem", "totem-refill-ms", 30, v("totem-refill-ms", 15, "totem-count", 8)),
        flag("combat.inventory-attack", "container-open", v("attacks-second", 9), "container-open"),
        f("movement.fly", "air-ticks", 40, v("air-ticks", 41, "dy", 0, "support-distance", 4)),
        f("movement.speed", "horizontal", .576, v("horizontal", .7, "speed-limit", .36), "ground"),
        f("movement.airjump", "previous-dy", -.1, v("air-ticks", 9, "previous-dy", -.2, "dy", .4)),
        f("movement.vertical", "dy", .85, v("dy", .9, "air-ticks", 5)),
        f("movement.gravity", "gravity-residual", .2, v("air-ticks", 11, "support-distance", 5, "gravity-residual", .21)),
        f("movement.acceleration", "horizontal-acceleration", .72, v("horizontal-acceleration", .8, "speed-limit", .36), "ground"),
        f("movement.bunnyhop", "jump-chain", 7, v("jump-chain", 8, "horizontal", .8, "speed-limit", .36)),
        f("movement.jesus", "water-surface-ticks", 30, v("water-surface-ticks", 31, "dy", 0), "water-below"),
        flag("movement.spider", "wall-near", v("air-ticks", 13, "dy", .3), "wall-near"),
        f("movement.step", "dy", 1.2, v("dy", 1.3, "step-limit", .6), "ground", "previous-ground"),
        f("movement.phase", "solid-penetration", .4, v("solid-penetration", .5, "horizontal", .3)),
        f("movement.landing-claim", "tracked-fall", 6, v("tracked-fall", 7, "support-distance", 3, "dy", -.2), "packet-ground"),
        f("movement.ground-spoof", "support-distance", 2, v("support-distance", 3, "air-ticks", 11), "packet-ground"),
        f("movement.noslow", "using-ticks", 8, v("using-ticks", 9, "horizontal", .5, "speed-limit", .36), "ground", "using-item"),
        flag("movement.sneak-speed", "sneaking", v("horizontal", .5, "speed-limit", .36), "ground", "sneaking"),
        f("movement.elytra-rise", "dy", 1.8, v("dy", 1.9, "glide-ticks", 41), "gliding"),
        f("movement.elytra-speed", "horizontal", 4.5, v("horizontal", 4.6, "glide-ticks", 41), "gliding"),
        flag("movement.velocity", "clear-knockback", v("expected-displacement", 1.2, "observed-displacement", .01), "clear-knockback"),
        f("block.fastbreak", "break-progress", .5, v("break-progress", .2)),
        f("block.nuker", "breaks-tick", 4, v("breaks-tick", 5)),
        f("block.break-reach", "block-reach", 6, v("block-reach", 7, "block-reach-limit", 4.5)),
        flag("block.bed-obstruction", "occluded", v("block-reach", 3), "bed", "occluded"),
        f("block.autotool", "tool-switch-count", 29, v("tool-switch-ms", 10, "tool-switch-count", 30)),
        f("block.fastplace", "places-second", 22, v("places-second", 23)),
        f("block.place-burst", "places-tick", 5, v("places-tick", 6)),
        f("block.place-reach", "block-reach", 6, v("block-reach", 7, "block-reach-limit", 4.5)),
        f("block.scaffold", "pitch", 10, v("pitch", 0, "places-second", 9), "underfoot"),
        f("block.tower", "tower-chain", 8, v("tower-chain", 9, "tower-rise-per-tick", .9), "underfoot"),
        f("inventory.move", "horizontal", .35, v("horizontal", .4), "ground", "container-open"),
        f("inventory.click-rate", "inventory-clicks-second", 45, v("inventory-clicks-second", 46)),
        f("inventory.chest-stealer", "transfer-count", 29, v("transfer-count", 30, "transfer-mean-ms", 20), "container-open"),
        f("inventory.cleaner", "drops-second", 25, v("drops-second", 26)),
        flag("inventory.autoarmor", "armor-slot", v("armor-interval-ms", 20, "armor-equip-count", 20), "armor-slot"),
        f("inventory.closed-window", "since-close-ms", 1000, v("window-id", 1, "since-close-ms", 1001)),
        f("network.timer", "movement-count", 130, v("movement-window-ms", 5000, "movement-count", 131)),
        f("network.blink", "previous-gap-ms", 2000, v("previous-gap-ms", 2001, "movement-burst", 41)),
        f("network.flood", "packets-second", 1000, v("packets-second", 1001)),
        f("network.swing-flood", "swings-second", 150, v("swings-second", 151)),
        f("network.use-flood", "interactions-second", 200, v("interactions-second", 201)),
        f("packet.nonfinite-position", "x", 0, v("x", Double.NaN, "y", 64, "z", 0), "position-present"),
        f("packet.nonfinite-rotation", "yaw", 0, v("yaw", Double.POSITIVE_INFINITY, "pitch", 0), "rotation-present"),
        f("packet.pitch", "pitch", 90.01, v("yaw", 0, "pitch", 90.02), "rotation-present"),
        f("packet.held-slot", "held-slot", 8, v("held-slot", 9), "held-slot-present"),
        f("packet.dig-face", "dig-face", 5, v("dig-face", 6), "dig-block"),
        f("packet.world-boundary", "x", 30000001, v("x", 30000002, "y", 64, "z", 0), "position-present"),
        f("packet.payload-size", "payload-bytes", 32768, v("payload-bytes", 32769)),
        f("xray.rare-rate", "mined-blocks", 499, v("mined-blocks", 500, "rare-ratio", .13)),
        f("xray.hidden-path", "hidden-veins", 11, v("hidden-veins", 12, "targeted-turn-ratio", .86)),
        f("xray.vein-efficiency", "blocks-per-vein", 5, v("distinct-rare-veins", 20, "blocks-per-vein", 4))
    ); }
    static Stream<org.junit.jupiter.params.provider.Arguments> cases() {
        Map<String, Fixture> fixtures = new HashMap<>(); fixtures().forEach(f -> fixtures.put(f.id(), f));
        return CheckCatalog.create().stream().map(c -> org.junit.jupiter.params.provider.Arguments.of(c, Objects.requireNonNull(fixtures.get(c.id()), c.id())));
    }
    @Test void everyRuleHasExactlyOneMeaningfulFixture() {
        Set<String> ids = new HashSet<>(); for (Fixture f : fixtures()) assertTrue(ids.add(f.id()), "duplicate fixture " + f.id());
        assertEquals(CheckCatalog.create().stream().map(Check::id).collect(java.util.stream.Collectors.toSet()), ids);
        assertTrue(ids.size() >= 50);
    }
    @ParameterizedTest(name = "{0} positive") @MethodSource("cases")
    void actualRuleDetectsPositive(Check check, Fixture fixture) { assertTrue(check.test(fixture.sample(check, false, null)), check.id()); }
    @ParameterizedTest(name = "{0} legitimate boundary") @MethodSource("cases")
    void actualRuleRejectsLegitimateBoundary(Check check, Fixture fixture) { assertFalse(check.test(fixture.sample(check, true, null)), check.id()); }
    @ParameterizedTest(name = "{0} missing") @MethodSource("cases")
    void missingMeasurementsNeverDetect(Check check, Fixture fixture) {
        assertFalse(check.test(new Sample(PLAYER, 1000, 20, check.kind(), v("tps", 20, "ping", 40), fixture.flags())), check.id());
    }
    @ParameterizedTest(name = "{0} exemptions") @MethodSource("cases")
    void allRulesRespectBypassAndExplicitExemption(Check check, Fixture fixture) {
        assertFalse(check.test(fixture.sample(check, false, "bypass")), check.id());
        assertFalse(check.test(fixture.sample(check, false, "exempt")), check.id());
    }
    @ParameterizedTest(name = "{0} conservative context") @MethodSource("cases")
    void heuristicsRespectAllPhysicsExemptions(Check check, Fixture fixture) {
        if (check.hardInvariant()) return;
        boolean timing = check.family().equals("INVENTORY_TIMING") && (check.kind() == Sample.Kind.INVENTORY || check.kind() == Sample.Kind.USE);
        for (String flag : List.of("vehicle", "flight", "effects", "velocity-grace", "teleport-grace", "special-terrain", "protocol-translated")) {
            if (timing && Set.of("effects", "velocity-grace", "special-terrain").contains(flag))
                assertTrue(check.test(fixture.sample(check, false, flag)), check.id() + ": timing remains observable during " + flag);
            else assertFalse(check.test(fixture.sample(check, false, flag)), check.id() + ": " + flag);
        }
        Sample s = fixture.sample(check, false, null);
        Map<String, Double> poorTps = new HashMap<>(s.measurements()); poorTps.put("tps", 17.99);
        Map<String, Double> highPing = new HashMap<>(s.measurements()); highPing.put("ping", 201d);
        Map<String, Double> unknownPing = new HashMap<>(s.measurements()); unknownPing.remove("ping");
        for (Map<String, Double> values : List.of(poorTps, highPing, unknownPing)) assertFalse(check.test(new Sample(PLAYER, 1000, 20, check.kind(), values, s.flags())));
    }
    @ParameterizedTest(name = "{0} event kind") @MethodSource("cases")
    void wrongEventKindCannotTrigger(Check check, Fixture fixture) {
        Sample s = fixture.sample(check, false, null);
        Sample.Kind other = check.kind() == Sample.Kind.MOVE ? Sample.Kind.ATTACK : Sample.Kind.MOVE;
        assertFalse(check.test(new Sample(PLAYER, 1000, 20, other, s.measurements(), s.flags())));
    }
    @Test void partialNonfiniteFieldsAreUnknownNotInvalidPackets() {
        Check position = CheckCatalog.create().stream().filter(c -> c.id().equals("packet.nonfinite-position")).findFirst().orElseThrow();
        Check rotation = CheckCatalog.create().stream().filter(c -> c.id().equals("packet.nonfinite-rotation")).findFirst().orElseThrow();
        assertFalse(position.test(new Sample(PLAYER, 1000, 1, Sample.Kind.PACKET, v("x", Double.NaN, "z", 0), Set.of("position-present"))));
        assertFalse(rotation.test(new Sample(PLAYER, 1000, 1, Sample.Kind.PACKET, v("yaw", Double.NaN), Set.of("rotation-present"))));
    }
    @Test void totemEvidenceSurvivesItsOwnEffectsButCannotAutomaticallyBan() {
        Check check = CheckCatalog.create().stream().filter(c -> c.id().equals("combat.autototem")).findFirst().orElseThrow();
        DetectionEngine engine = new DetectionEngine(List.of(check), 1);
        for (int i = 0; i < 60; i++) {
            Sample measured = new Sample(PLAYER, 1000 + i * 600, i * 12, Sample.Kind.USE,
                v("totem-refill-ms", 12, "totem-count", 8, "tps", 20, "ping", 40), Set.of("effects", "velocity-grace"));
            assertTrue(check.test(measured)); assertFalse(engine.accept(measured).banRecommended());
        }
        assertFalse(engine.current(PLAYER).isEmpty());
        Sample teleporting = new Sample(PLAYER, 50000, 1000, Sample.Kind.USE,
            v("totem-refill-ms", 12, "totem-count", 8, "tps", 20, "ping", 40), Set.of("effects", "teleport-grace"));
        assertFalse(check.test(teleporting));
        Sample lagged = new Sample(PLAYER, 51000, 1020, Sample.Kind.USE,
            v("totem-refill-ms", 12, "totem-count", 8, "tps", 16, "ping", 40), Set.of("effects"));
        assertFalse(check.test(lagged));
    }
    @Test void boostAndInstantBreakAreRespected() {
        for (String id : List.of("movement.elytra-rise", "movement.elytra-speed", "block.fastbreak")) {
            Check check = CheckCatalog.create().stream().filter(c -> c.id().equals(id)).findFirst().orElseThrow();
            Fixture fixture = fixtures().stream().filter(f -> f.id().equals(id)).findFirst().orElseThrow();
            assertFalse(check.test(fixture.sample(check, false, id.startsWith("block") ? "instant-break" : "firework-grace")));
        }
    }
}
