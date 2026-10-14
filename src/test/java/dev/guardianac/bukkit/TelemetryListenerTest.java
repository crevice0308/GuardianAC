package dev.guardianac.bukkit;

import dev.guardianac.GuardianPlugin;
import dev.guardianac.config.GuardianSettings;
import dev.guardianac.detection.CombatSample;
import dev.guardianac.detection.DetectionEngine;
import dev.guardianac.detection.MovementSample;
import org.bukkit.Bukkit;
import org.bukkit.Color;
import org.bukkit.GameMode;
import org.bukkit.Keyed;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.Registry;
import org.bukkit.World;
import org.bukkit.attribute.Attribute;
import org.bukkit.attribute.AttributeInstance;
import org.bukkit.block.Block;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.event.player.PlayerVelocityEvent;
import org.bukkit.potion.PotionEffectType;
import org.bukkit.potion.PotionEffect;
import org.bukkit.util.BoundingBox;
import org.bukkit.util.Vector;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.junit.jupiter.params.provider.CsvSource;
import org.mockito.Answers;
import org.mockito.ArgumentCaptor;
import org.mockito.MockedStatic;

import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicLong;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class TelemetryListenerTest {
    private static final UUID ATTACKER_ID = UUID.fromString("11111111-2222-3333-4444-555555555555");
    private static final UUID TARGET_ID = UUID.fromString("aaaaaaaa-bbbb-cccc-dddd-eeeeeeeeeeee");

    @BeforeAll
    static void initializeServerProvidedPotionTypes() {
        // Spigot 1.20.4 resolves potion constants from server registries during class initialization.
        // Supply this small bootstrap explicitly; the adapter fixtures below still mock Bukkit normally.
        try (MockedStatic<Bukkit> ignored = mockStatic(Bukkit.class, invocation -> {
            if (invocation.getMethod().getName().equals("getRegistry")) {
                Class<? extends Keyed> type = invocation.getArgument(0);
                return new TestRegistry<>(type);
            }
            return Answers.RETURNS_DEFAULTS.answer(invocation);
        })) {
            assertNotNull(PotionEffectType.JUMP);
        }
    }

    @ParameterizedTest
    @ValueSource(booleans = {true, false})
    void damageOrVelocityBeforeAttackPreservesReachChecksAndOnlyForgivesMovement(boolean damage) throws Exception {
        try (Fixture f = new Fixture()) {
            f.samplePastJoinGrace();
            EntityDamageByEntityEvent hit = f.hit();
            if (damage) {
                // Bukkit invokes the general damage handler for this same melee hit as well.
                f.listener.damage(hit);
            } else {
                PlayerVelocityEvent velocity = mock(PlayerVelocityEvent.class);
                when(velocity.getPlayer()).thenReturn(f.target);
                f.listener.velocity(velocity);
            }
            f.listener.attack(hit);

            // The eye is within the target's vertical span: the nearest hitbox face is exactly 3m away.
            // Looking along +Z at a target on +X gives a 90 degree attack angle.
            CombatSample combat = onlyCombat(f);
            assertFalse(combat.exempt(), "Damage and velocity grace must leave combat detection active");
            assertEquals(3.0, combat.distance(), 1e-9);
            assertEquals(90.0, combat.angleDegrees(), 1e-5);

            f.clock.set(62);
            f.listener.tick();
            f.clock.set(63);
            f.listener.tick();
            MovementSample movement = lastMovement(f, TARGET_ID);
            assertTrue(movement.exempt(), "The knockback/damage grace must still protect movement checks");
            assertEquals(.40, movement.horizontalLimit(), 1e-6);
        }
    }

    @Test
    void exactRearAttackAngleStaysInsideTheDetectionEnginesValidRange() throws Exception {
        try (Fixture f = new Fixture()) {
            when(f.attacker.getEyeLocation()).thenAnswer(invocation -> new Location(f.world, 0, .9, 0));
            when(f.target.getLocation()).thenAnswer(invocation -> new Location(f.world, 0, 0, -3.3));
            when(f.target.getBoundingBox()).thenAnswer(invocation -> new BoundingBox(-.3, 0, -3.6, .3, 1.8, -3));
            f.samplePastJoinGrace();
            f.listener.attack(f.hit());
            // Bukkit returns a float angle, whose PI value converts to slightly more than 180 degrees.
            // Such an otherwise valid attack must not be dropped as a malformed sample by the engine.
            assertEquals(180.0, onlyCombat(f).angleDegrees());
        }
    }

    @Test
    void reachUsesHitboxFaceRatherThanTargetCenterDistance() throws Exception {
        try (Fixture f = new Fixture()) {
            f.samplePastJoinGrace();
            f.listener.attack(f.hit());

            Vector eye = f.attacker.getEyeLocation().toVector();
            assertTrue(eye.distance(f.target.getBoundingBox().getCenter()) > 3.05,
                    "The test target center must lie outside the configured reach envelope");
            assertEquals(3.0, TelemetryListener.distance(eye, f.target.getBoundingBox()), 1e-9);
            CombatSample combat = onlyCombat(f);
            assertFalse(combat.exempt());
            assertEquals(3.0, combat.distance(), 1e-9);
            assertEquals(90.0, combat.angleDegrees(), 1e-5);
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"jump-potion", "bypass-permission", "allow-flight"})
    void victimMovementPrivilegesCannotExemptTheAttackersCombatChecks(String privilege) throws Exception {
        try (Fixture f = new Fixture()) {
            switch (privilege) {
                case "jump-potion" -> when(f.target.hasPotionEffect(PotionEffectType.JUMP)).thenReturn(true);
                case "bypass-permission" -> when(f.target.hasPermission("guardianac.bypass")).thenReturn(true);
                case "allow-flight" -> when(f.target.getAllowFlight()).thenReturn(true);
                default -> throw new IllegalArgumentException(privilege);
            }
            f.samplePastJoinGrace();
            f.listener.attack(f.hit());
            assertFalse(onlyCombat(f).exempt(), "A victim's movement exemption must not shield their attacker");
        }
    }

    @Test
    void damageBeforeAttackRetainsRecentTargetHistoryForReachForgiveness() throws Exception {
        try (Fixture f = new Fixture()) {
            f.samplePastJoinGrace();
            // One ordinary sprint tick moves the target's current box beyond reach, while its previous
            // box is still inside the client's view. Damage handling must preserve that recent frame.
            when(f.target.getLocation()).thenAnswer(invocation -> new Location(f.world, 3.6, 0, 0));
            when(f.target.getBoundingBox()).thenAnswer(invocation -> new BoundingBox(3.3, 0, -.3, 3.9, 1.8, .3));
            f.clock.set(62);
            f.listener.tick();
            assertTrue(TelemetryListener.distance(f.attacker.getEyeLocation().toVector(), f.target.getBoundingBox()) > 3.15);

            EntityDamageByEntityEvent hit = f.hit();
            f.listener.damage(hit);
            f.listener.attack(hit);
            CombatSample combat = onlyCombat(f);
            assertFalse(combat.exempt());
            assertEquals(3.0, combat.distance(), 1e-9,
                    "The preceding tick's closer target box must survive the damage event");
        }
    }

    @Test
    void recentForwardTargetFrameForgivesAngleWhenTargetCrossesBehindHighPingAttacker() throws Exception {
        try (Fixture f = new Fixture()) {
            when(f.attacker.getPing()).thenReturn(200);
            when(f.target.getLocation()).thenAnswer(invocation -> new Location(f.world, 0, 0, 1));
            when(f.target.getBoundingBox()).thenAnswer(invocation -> new BoundingBox(-.3, 0, .7, .3, 1.8, 1.3));
            f.samplePastJoinGrace();

            when(f.target.getLocation()).thenAnswer(invocation -> new Location(f.world, 0, 0, -1));
            when(f.target.getBoundingBox()).thenAnswer(invocation -> new BoundingBox(-.3, 0, -1.3, .3, 1.8, -.7));
            f.clock.set(65);
            f.listener.tick();
            Location eye = f.attacker.getEyeLocation();
            double currentAngle = Math.toDegrees(eye.getDirection().angle(f.target.getBoundingBox().getCenter().subtract(eye.toVector())));
            assertTrue(currentAngle > 125, "The current target box must otherwise look like an implausible rear attack");

            f.listener.attack(f.hit());
            CombatSample combat = onlyCombat(f);
            assertFalse(combat.exempt());
            assertTrue(combat.angleDegrees() < 90,
                    "The legitimate recent forward frame must forgive the current rear angle under latency");
        }
    }

    @Test
    void anUnloadedChunkForgivesMovementWithoutReadingOrLoadingItsBlocks() throws Exception {
        try (Fixture f = new Fixture()) {
            when(f.world.isChunkLoaded(anyInt(), anyInt())).thenReturn(false);
            f.samplePastJoinGrace();

            assertTrue(lastMovement(f, ATTACKER_ID).exempt());
            verify(f.world, never()).getBlockAt(anyInt(), anyInt(), anyInt());
            verify(f.world, never()).getChunkAt(anyInt(), anyInt());
        }
    }

    @Test
    void nearbyIceSurfaceForgivesMovementAfterJoinGraceExpires() throws Exception {
        try (Fixture f = new Fixture()) {
            when(f.air.getType()).thenReturn(Material.PACKED_ICE);
            f.samplePastJoinGrace();
            assertTrue(lastMovement(f, ATTACKER_ID).exempt());
        }
    }

    @Test
    void jumpBoostForgivesFlyWithoutDisablingSpeedDetection() throws Exception {
        try (Fixture f = new Fixture()) {
            when(f.attacker.hasPotionEffect(PotionEffectType.JUMP)).thenReturn(true);
            f.samplePastJoinGrace();

            MovementSample sample = lastMovement(f, ATTACKER_ID);
            assertFalse(sample.exempt(), "A vertical potion must leave horizontal speed checks active");
            assertTrue(sample.nearSupport(), "Jump Boost must still forgive the vertical flight heuristic");
            assertFalse(sample.onGround(), "Potion forgiveness must not invent physical ground support");
        }
    }

    @ParameterizedTest
    @CsvSource({"0.13, 0.40", "0.156, 0.48"})
    void sprintAttributeCountsOnlyAdditionalSpeedBeyondTheBaseSprintEnvelope(double attributeValue, double expectedLimit) throws Exception {
        try (Fixture f = new Fixture()) {
            AttributeInstance movementSpeed = mock(AttributeInstance.class);
            when(movementSpeed.getValue()).thenReturn(attributeValue);
            when(f.attacker.getAttribute(Attribute.GENERIC_MOVEMENT_SPEED)).thenReturn(movementSpeed);
            when(f.attacker.isSprinting()).thenReturn(true);
            f.samplePastJoinGrace();

            MovementSample sample = lastMovement(f, ATTACKER_ID);
            assertFalse(sample.exempt());
            assertEquals(expectedLimit, sample.horizontalLimit(), 1e-6,
                    "The ordinary sprint modifier is already included in the configured base envelope");
        }
    }

    private static MovementSample lastMovement(Fixture f, UUID player) {
        ArgumentCaptor<MovementSample> sample = ArgumentCaptor.forClass(MovementSample.class);
        verify(f.engine, atLeastOnce()).movement(eq(player), sample.capture());
        return sample.getValue();
    }

    private static CombatSample onlyCombat(Fixture f) {
        ArgumentCaptor<CombatSample> sample = ArgumentCaptor.forClass(CombatSample.class);
        verify(f.engine).combat(eq(ATTACKER_ID), sample.capture());
        return sample.getValue();
    }

    private static final class TestRegistry<T extends Keyed> implements Registry<T> {
        private final Class<T> type;
        private final Map<NamespacedKey, T> entries = new HashMap<>();

        TestRegistry(Class<T> type) {
            this.type = type;
        }

        @Override
        public T get(NamespacedKey key) {
            if (type != PotionEffectType.class) return null;
            return entries.computeIfAbsent(key, value -> type.cast(new TestPotionType(value)));
        }

        @Override public Stream<T> stream() { return entries.values().stream(); }
        @Override public Iterator<T> iterator() { return entries.values().iterator(); }
    }

    private static final class TestPotionType extends PotionEffectType {
        private final NamespacedKey key;

        TestPotionType(NamespacedKey key) { this.key = key; }
        @Override public NamespacedKey getKey() { return key; }
        @Override public String getTranslationKey() { return "effect.minecraft." + key.getKey(); }
        @Override public PotionEffect createEffect(int duration, int amplifier) { return new PotionEffect(this, duration, amplifier); }
        @Override public boolean isInstant() { return false; }
        @Override public Color getColor() { return Color.WHITE; }
        @Override public double getDurationModifier() { return 1; }
        @Override public int getId() { return key.hashCode(); }
        @Override public String getName() { return key.getKey().toUpperCase(Locale.ROOT); }
    }

    private static final class Fixture implements AutoCloseable {
        final GuardianPlugin plugin = mock(GuardianPlugin.class);
        final DetectionEngine engine = mock(DetectionEngine.class);
        final World world = mock(World.class);
        final Block air = mock(Block.class);
        final AtomicLong clock = new AtomicLong();
        final Player attacker;
        final Player target;
        final MockedStatic<Bukkit> bukkit;
        final TelemetryListener listener;

        Fixture() throws Exception {
            when(plugin.settings()).thenReturn(settings());
            when(plugin.engine()).thenReturn(engine);
            when(plugin.tick()).thenAnswer(invocation -> clock.get());
            when(world.getUID()).thenReturn(UUID.fromString("99999999-8888-7777-6666-555555555555"));
            when(world.getMinHeight()).thenReturn(-64);
            when(world.getMaxHeight()).thenReturn(320);
            when(world.isChunkLoaded(anyInt(), anyInt())).thenReturn(true);
            when(world.getBlockAt(anyInt(), anyInt(), anyInt())).thenReturn(air);
            when(air.getType()).thenReturn(Material.AIR);
            when(air.isPassable()).thenReturn(true);
            attacker = player(ATTACKER_ID, "Attacker", 0);
            target = player(TARGET_ID, "Target", 3.3);
            bukkit = mockStatic(Bukkit.class);
            bukkit.when(Bukkit::getOnlinePlayers).thenReturn(List.of(attacker, target));
            listener = new TelemetryListener(plugin);
        }

        private Player player(UUID id, String name, double x) {
            Player player = mock(Player.class);
            when(player.getUniqueId()).thenReturn(id);
            when(player.getName()).thenReturn(name);
            when(player.getWorld()).thenReturn(world);
            when(player.getGameMode()).thenReturn(GameMode.SURVIVAL);
            when(player.getWalkSpeed()).thenReturn(.2f);
            when(player.getLocation()).thenAnswer(invocation -> new Location(world, x, 0, 0));
            when(player.getEyeLocation()).thenAnswer(invocation -> new Location(world, x, 1.62, 0, 0, 0));
            // Normal standing player dimensions, with all world reads staying in already loaded chunks.
            when(player.getBoundingBox()).thenAnswer(invocation -> new BoundingBox(x - .3, 0, -.3, x + .3, 1.8, .3));
            return player;
        }

        void samplePastJoinGrace() {
            listener.tick();
            clock.set(61);
            listener.tick();
        }

        EntityDamageByEntityEvent hit() {
            EntityDamageByEntityEvent event = mock(EntityDamageByEntityEvent.class);
            when(event.getDamager()).thenReturn(attacker);
            when(event.getEntity()).thenReturn(target);
            when(event.getCause()).thenReturn(EntityDamageEvent.DamageCause.ENTITY_ATTACK);
            return event;
        }

        @Override
        public void close() {
            bukkit.close();
        }
    }

    private static GuardianSettings settings() throws Exception {
        YamlConfiguration configuration = new YamlConfiguration();
        try (InputStream input = TelemetryListenerTest.class.getResourceAsStream("/config.yml")) {
            assertNotNull(input);
            configuration.load(new InputStreamReader(input, StandardCharsets.UTF_8));
        }
        return GuardianSettings.read(configuration);
    }
}
