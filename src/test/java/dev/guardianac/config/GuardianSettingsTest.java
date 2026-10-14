package dev.guardianac.config;

import dev.guardianac.detection.CheckType;
import dev.guardianac.detection.DetectionEngine;
import org.bukkit.configuration.file.YamlConfiguration;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;

import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class GuardianSettingsTest {
    @Test
    void shippedConfigurationLoadsCompleteProtectionAndExpectedEnginePolicies() throws Exception {
        GuardianSettings settings = GuardianSettings.read(defaultConfiguration());

        assertEquals(EnumSet.allOf(CheckType.class), settings.checks().keySet());
        assertTrue(settings.checks().values().stream().allMatch(GuardianSettings.CheckPolicy::enabled));
        assertEquals(new GuardianSettings.CheckPolicy(true, 18.0, 2.0), settings.checks().get(CheckType.SPEED));
        assertEquals(new GuardianSettings.CheckPolicy(true, 18.0, 2.5), settings.checks().get(CheckType.FLY));
        assertEquals(new GuardianSettings.CheckPolicy(true, 16.0, 2.0), settings.checks().get(CheckType.REACH));
        assertEquals(new GuardianSettings.CheckPolicy(true, 18.0, 2.5), settings.checks().get(CheckType.KILLAURA));
        assertEquals(new DetectionEngine.Settings(
                new DetectionEngine.MovementSettings(1.15, 4, 18, 8, .025, .15),
                new DetectionEngine.CombatSettings(.10, 3, 40, 125.0, 6, 3, 4, 4)), settings.detection());
        assertTrue(settings.autoBan());
        assertTrue(settings.ipBan());
        assertEquals(86_400_000L, settings.duration());
        assertEquals(6, settings.minFindings());
        assertEquals(40, settings.minEvidenceTicks());
        assertEquals(.75, settings.decay());
        assertTrue(settings.safety().protectSharedIp());
        assertEquals(6, settings.safety().historyTicks());
        assertTrue(settings.messages().get("ban-screen").contains("{player}"));
        assertTrue(settings.banBroadcast().stream().anyMatch(line -> line.contains("{player}")));
    }

    @Test
    void supportsUuidOnlyPermanentPolicyWithoutImplicitIpBan() throws Exception {
        YamlConfiguration configuration = defaultConfiguration();
        configuration.set("punishment.mode", "UUID");
        configuration.set("punishment.duration", "permanent");
        GuardianSettings settings = GuardianSettings.read(configuration);
        assertFalse(settings.ipBan());
        assertEquals(0L, settings.duration());
    }

    @ParameterizedTest
    @ValueSource(strings = {"KICK", "IP", "uuid", "", "UUID_AND_IP_AND_KICK"})
    void rejectsUnknownPunishmentModes(String mode) throws Exception {
        YamlConfiguration configuration = defaultConfiguration();
        configuration.set("punishment.mode", mode);
        IllegalArgumentException error = assertThrows(IllegalArgumentException.class,
                () -> GuardianSettings.read(configuration));
        assertTrue(error.getMessage().contains("punishment.mode"));
    }

    @ParameterizedTest
    @MethodSource("invalidNumericSettings")
    void rejectsMalformedOutOfRangeAndMissingNumericPolicies(String path, Object value) throws Exception {
        YamlConfiguration configuration = defaultConfiguration();
        configuration.set(path, value);
        IllegalArgumentException error = assertThrows(IllegalArgumentException.class,
                () -> GuardianSettings.read(configuration));
        assertTrue(error.getMessage().contains(path));
    }

    static Stream<Arguments> invalidNumericSettings() {
        return Stream.of(
                Arguments.of("checks.speed.threshold", "18.0"),
                Arguments.of("checks.reach.weight", Double.NaN),
                Arguments.of("checks.fly.threshold", Double.POSITIVE_INFINITY),
                Arguments.of("checks.killaura.weight", 0),
                Arguments.of("detection.speed-buffer", 1.5),
                Arguments.of("detection.killaura-angle-degrees", 89),
                Arguments.of("detection.speed-tolerance", null),
                Arguments.of("safety.target-history-ticks", 11),
                Arguments.of("safety.max-ping-ms", -1),
                Arguments.of("violations.minimum-findings", true),
                Arguments.of("violations.minimum-evidence-ticks", -1),
                Arguments.of("punishment.queue-capacity", 0));
    }

    @ParameterizedTest
    @MethodSource("invalidBooleanSettings")
    void rejectsNonBooleanTogglesRatherThanSilentlyDisablingProtection(String path, Object value) throws Exception {
        YamlConfiguration configuration = defaultConfiguration();
        configuration.set(path, value);
        IllegalArgumentException error = assertThrows(IllegalArgumentException.class,
                () -> GuardianSettings.read(configuration));
        assertTrue(error.getMessage().contains(path));
    }

    static Stream<Arguments> invalidBooleanSettings() {
        return Stream.of(
                Arguments.of("punishment.auto-ban", "true"),
                Arguments.of("punishment.broadcast", 1),
                Arguments.of("checks.speed.enabled", "false"),
                Arguments.of("checks.fly.enabled", null),
                Arguments.of("safety.protect-shared-ip", "yes"));
    }

    @ParameterizedTest
    @MethodSource("invalidDurations")
    void rejectsUnusablePunishmentDurations(Object value) throws Exception {
        YamlConfiguration configuration = defaultConfiguration();
        configuration.set("punishment.duration", value);
        assertThrows(IllegalArgumentException.class, () -> GuardianSettings.read(configuration));
    }

    static Stream<Arguments> invalidDurations() {
        return Stream.of(Arguments.of((Object) null), Arguments.of("0s"), Arguments.of("-24h"),
                Arguments.of("1.5h"), Arguments.of("9223372036854775807d"),
                Arguments.of(86_400_000), Arguments.of(true));
    }

    @ParameterizedTest
    @MethodSource("invalidBroadcasts")
    void rejectsMalformedBroadcastsInsteadOfSilentlySuppressingBanAnnouncements(Object value) throws Exception {
        YamlConfiguration configuration = defaultConfiguration();
        configuration.set("messages.ban-broadcast", value);
        IllegalArgumentException error = assertThrows(IllegalArgumentException.class,
                () -> GuardianSettings.read(configuration));
        assertTrue(error.getMessage().contains("messages.ban-broadcast"));
    }

    static Stream<Arguments> invalidBroadcasts() {
        return Stream.of(Arguments.of("{player} has been banned"),
                Arguments.of(List.of("{player} has been banned", 42)),
                Arguments.of(Map.of("text", "{player} has been banned")));
    }

    private static YamlConfiguration defaultConfiguration() throws Exception {
        YamlConfiguration configuration = new YamlConfiguration();
        try (InputStream input = GuardianSettingsTest.class.getResourceAsStream("/config.yml")) {
            assertNotNull(input, "The production default config must be packaged with the plugin");
            configuration.load(new InputStreamReader(input, StandardCharsets.UTF_8));
        }
        return configuration;
    }
}
