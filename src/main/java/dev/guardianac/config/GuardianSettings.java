package dev.guardianac.config;

import dev.guardianac.command.DurationParser;
import dev.guardianac.detection.CheckType;
import dev.guardianac.detection.DetectionEngine;
import org.bukkit.configuration.file.YamlConfiguration;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;

public record GuardianSettings(
        Map<CheckType, CheckPolicy> checks, DetectionEngine.Settings detection,
        double decay, int minFindings, int minEvidenceTicks, int alertCooldown,
        boolean autoBan, boolean ipBan, long duration, String reason, boolean broadcast, int queueCapacity,
        Safety safety, Map<String, String> messages, List<String> banBroadcast) {
    public record CheckPolicy(boolean enabled, double threshold, double weight) {}
    public record Safety(int maxPing, double maxTickMs, int lagGrace, int joinGrace, int teleportGrace,
                         int velocityGrace, int damageGrace, double horizontalLimit, double reachLimit,
                         double reachLatencyAllowance, int historyTicks, boolean protectSharedIp) {}

    public static GuardianSettings read(YamlConfiguration c) {
        EnumMap<CheckType, CheckPolicy> policies = new EnumMap<>(CheckType.class);
        for (CheckType check : CheckType.values()) {
            String p = "checks." + check.name().toLowerCase(java.util.Locale.ROOT) + ".";
            policies.put(check, new CheckPolicy(bool(c, p + "enabled"), number(c, p + "threshold", .1, 10000),
                    number(c, p + "weight", .01, 100)));
        }
        var movement = new DetectionEngine.MovementSettings(
                number(c,"detection.speed-tolerance",1,3), integer(c,"detection.speed-buffer",1,100),
                integer(c,"detection.air-grace-ticks",10,200), integer(c,"detection.fly-buffer",1,100),
                number(c,"detection.hover-vertical-tolerance",.001,.1), number(c,"detection.ascent-min-vertical",.05,1));
        var combat = new DetectionEngine.CombatSettings(
                number(c,"detection.reach-margin",0,1), integer(c,"detection.reach-buffer",1,100),
                integer(c,"detection.max-attack-gap-ticks",1,200), number(c,"detection.killaura-angle-degrees",90,180),
                integer(c,"detection.killaura-buffer",1,100), integer(c,"detection.max-attacks-per-tick",1,20),
                integer(c,"detection.max-distinct-targets-per-window",1,20), integer(c,"detection.target-window-ticks",1,20));
        String mode = c.getString("punishment.mode", "");
        if (!mode.equals("UUID") && !mode.equals("UUID_AND_IP"))
            throw new IllegalArgumentException("punishment.mode must be UUID or UUID_AND_IP");
        Safety safety = new Safety(integer(c,"safety.max-ping-ms",0,2000), number(c,"safety.max-tick-ms",50,1000),
                integer(c,"safety.lag-grace-ticks",0,1200), integer(c,"safety.join-grace-ticks",1,1200),
                integer(c,"safety.teleport-grace-ticks",1,1200), integer(c,"safety.velocity-grace-ticks",1,1200),
                integer(c,"safety.damage-grace-ticks",1,1200), number(c,"safety.horizontal-base-limit",.3,5),
                number(c,"safety.reach-base-limit",3,6), number(c,"safety.max-reach-latency-allowance",0,2),
                integer(c,"safety.target-history-ticks",0,10), bool(c,"safety.protect-shared-ip"));
        Map<String, String> messages = new java.util.HashMap<>();
        for (String key : List.of("prefix", "alert", "ban-screen")) {
            if (!(c.get("messages." + key) instanceof String value) || value.length() > 4000)
                throw new IllegalArgumentException("Invalid messages." + key);
            messages.put(key, value);
        }
        if (!(c.get("messages.ban-broadcast") instanceof List<?> rawBroadcast)
                || rawBroadcast.stream().anyMatch(value -> !(value instanceof String)))
            throw new IllegalArgumentException("messages.ban-broadcast must be a list of strings");
        List<String> broadcast = rawBroadcast.stream().map(value -> (String)value).toList();
        if (broadcast.size() > 20 || broadcast.stream().anyMatch(s -> s.length() > 1000))
            throw new IllegalArgumentException("Ban broadcast is too long");
        if (!(c.get("punishment.reason") instanceof String reason) || reason.isBlank() || reason.length() > 256)
            throw new IllegalArgumentException("Invalid punishment.reason");
        return new GuardianSettings(Map.copyOf(policies), new DetectionEngine.Settings(movement, combat),
                number(c,"violations.decay-per-second",0,100), integer(c,"violations.minimum-findings",1,1000),
                integer(c,"violations.minimum-evidence-ticks",0,12000), integer(c,"violations.alert-cooldown-ticks",1,12000),
                bool(c,"punishment.auto-ban"), mode.equals("UUID_AND_IP"), DurationParser.parse(c.getString("punishment.duration")),
                reason, bool(c,"punishment.broadcast"), integer(c,"punishment.queue-capacity",1,10000), safety,
                Map.copyOf(messages), List.copyOf(broadcast));
    }

    private static double number(YamlConfiguration c, String path, double min, double max) {
        Object value = c.get(path);
        if (!(value instanceof Number n) || !Double.isFinite(n.doubleValue()) || n.doubleValue() < min || n.doubleValue() > max)
            throw new IllegalArgumentException(path + " must be between " + min + " and " + max);
        return n.doubleValue();
    }
    private static int integer(YamlConfiguration c, String path, int min, int max) {
        double n = number(c, path, min, max);
        if (n != Math.rint(n)) throw new IllegalArgumentException(path + " must be an integer");
        return (int)n;
    }
    private static boolean bool(YamlConfiguration c, String path) {
        if (!(c.get(path) instanceof Boolean b)) throw new IllegalArgumentException(path + " must be true or false");
        return b;
    }
}
