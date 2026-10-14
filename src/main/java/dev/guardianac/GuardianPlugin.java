package dev.guardianac;

import dev.guardianac.ban.BanRecord;
import dev.guardianac.ban.BanStore;
import dev.guardianac.bukkit.TelemetryListener;
import dev.guardianac.command.GuardianCommand;
import dev.guardianac.config.GuardianSettings;
import dev.guardianac.detection.DetectionEngine;
import dev.guardianac.detection.Finding;
import dev.guardianac.violation.ViolationTracker;
import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.command.CommandSender;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.AsyncPlayerPreLoginEvent;
import org.bukkit.event.player.PlayerLoginEvent;
import org.bukkit.plugin.java.JavaPlugin;
import java.io.File;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.logging.Level;

public final class GuardianPlugin extends JavaPlugin implements Listener {
    private static final DateTimeFormatter DATE = DateTimeFormatter.ofPattern("uuuu-MM-dd HH:mm 'UTC'").withZone(ZoneOffset.UTC);
    private volatile GuardianSettings settings;
    private BanStore bans;
    private DetectionEngine engine;
    private ViolationTracker violations;
    private TelemetryListener telemetry;
    private ThreadPoolExecutor storage;
    private final Set<UUID> pending = new HashSet<>();
    private final Set<UUID> mutedAlerts = new HashSet<>();
    private final Map<UUID, Long> alertTicks = new HashMap<>();
    private long tick;
    private long lagUntil;
    private long lastNanos;
    private double lastTickMs = 50;
    private volatile boolean running;
    private volatile boolean storageHealthy;

    @Override public void onEnable() {
        saveDefaultConfig();
        GuardianCommand command = new GuardianCommand(this);
        Objects.requireNonNull(getCommand("guardian")).setExecutor(command);
        getCommand("guardian").setTabCompleter(command);
        // Fail closed if an unsupported/legacy config is present; never overwrite a previous installation.
        try {
            settings = readSettings();
            bans = new BanStore(getDataFolder().toPath().resolve("bans.json"), Clock.systemUTC());
            bans.load();
            storageHealthy = true;
        } catch (Exception failure) {
            getLogger().log(Level.SEVERE, "GuardianAC could not load configuration or ban data. Server logins are blocked until repaired and restarted.", failure);
            Bukkit.getPluginManager().registerEvents(this, this);
            return;
        }
        storage = new ThreadPoolExecutor(1, 1, 0, TimeUnit.MILLISECONDS,
                new ArrayBlockingQueue<>(settings.queueCapacity()), r -> {
                    Thread thread = new Thread(r, "GuardianAC-storage");
                    thread.setDaemon(true);
                    return thread;
                }, new ThreadPoolExecutor.AbortPolicy());
        engine = new DetectionEngine(settings.detection());
        violations = new ViolationTracker(settings.decay(), settings.minFindings(), settings.minEvidenceTicks());
        telemetry = new TelemetryListener(this);
        Bukkit.getPluginManager().registerEvents(this, this);
        Bukkit.getPluginManager().registerEvents(telemetry, this);
        running = true;
        lastNanos = System.nanoTime();
        Bukkit.getScheduler().runTaskTimer(this, () -> {
            long now = System.nanoTime();
            lastTickMs = (now - lastNanos) / 1_000_000.0;
            lastNanos = now;
            tick++;
            if (lastTickMs > settings.safety().maxTickMs()) lagUntil = tick + settings.safety().lagGrace();
            telemetry.tick();
        }, 1, 1);
        getLogger().info("GuardianAC Ultimate enabled. Auto-ban=" + settings.autoBan() + ", IP mode=" + settings.ipBan()
                + ". Event heuristics require live calibration; no standalone kick penalty.");
    }

    private GuardianSettings readSettings() throws Exception {
        YamlConfiguration c = new YamlConfiguration();
        c.load(new File(getDataFolder(), "config.yml"));
        if (c.contains("ban-type") || c.contains("preset") || new File(getDataFolder(), "bans.db").exists())
            throw new IllegalArgumentException("Legacy binary data detected. Back it up and migrate explicitly; automatic database/config migration is unsupported.");
        try (var stream = Objects.requireNonNull(getResource("config.yml"));
             var reader = new InputStreamReader(stream, StandardCharsets.UTF_8)) {
            c.setDefaults(YamlConfiguration.loadConfiguration(reader));
        }
        return GuardianSettings.read(c);
    }

    public void reloadGuardian() {
        if (!running) throw new IllegalArgumentException("Repair configuration/storage and restart the plugin first.");
        try {
            GuardianSettings next = readSettings();
            if (next.queueCapacity() != settings.queueCapacity())
                throw new IllegalArgumentException("punishment.queue-capacity requires a server restart");
            // Outstanding writes use the policy captured at submission; do not switch policy under them.
            if (!pending.isEmpty() || storage.getActiveCount() > 0 || !storage.getQueue().isEmpty())
                throw new IllegalArgumentException("A storage operation is in progress; retry after it completes");
            settings = next;
            engine = new DetectionEngine(next.detection());
            violations = new ViolationTracker(next.decay(), next.minFindings(), next.minEvidenceTicks());
            telemetry.clear();
            alertTicks.clear();
        } catch (IllegalArgumentException e) { throw e; }
        catch (Exception e) { throw new IllegalArgumentException("Cannot reload config: " + e.getMessage(), e); }
    }

    public GuardianSettings settings() { return settings; }
    public DetectionEngine engine() { return engine; }
    public long tick() { return tick; }
    public boolean lagging() { return tick < lagUntil; }
    public double violation(UUID player) { return violations == null ? 0 : violations.maximum(player, tick); }

    public void findings(Player player, List<Finding> findings) {
        if (!running || pending.contains(player.getUniqueId())) return;
        for (Finding finding : findings) {
            var policy = settings.checks().get(finding.check());
            if (!policy.enabled()) continue;
            var verdict = violations.add(player.getUniqueId(), finding.check(), policy.weight() * finding.weight(), policy.threshold(), tick);
            if (tick - alertTicks.getOrDefault(player.getUniqueId(), -100000L) >= settings.alertCooldown()) {
                alertTicks.put(player.getUniqueId(), tick);
                String message = text(settings.messages().get("prefix") + settings.messages().get("alert"),
                        Map.of("player", player.getName(), "check", finding.check().name(), "vl", String.format(Locale.ROOT, "%.1f", verdict.level())));
                for (Player admin : Bukkit.getOnlinePlayers()) if (admin.hasPermission("guardianac.alerts") && !mutedAlerts.contains(admin.getUniqueId())) admin.sendMessage(message);
                getLogger().info(player.getName() + " " + finding.check() + " VL=" + String.format(Locale.ROOT,"%.1f", verdict.level()) + " " + finding.detail());
            }
            if (settings.autoBan() && verdict.punish()) {
                String reason = settings.reason().replace("{check}", finding.check().name());
                enqueueBan(null, player, settings.ipBan(), settings.duration(), reason, finding.check().name(), true);
                break;
            }
        }
    }

    public void submitBan(CommandSender actor, Player target, boolean ip, long durationMillis, String reason) {
        enqueueBan(actor, target, ip, durationMillis, reason, "Manual", false);
    }

    private void enqueueBan(CommandSender actor, Player target, boolean ip, long duration, String reason, String check, boolean automatic) {
        if (!running) { reply(actor,"Storage is unavailable; no ban was issued."); return; }
        UUID uuid = target.getUniqueId();
        if (!pending.add(uuid)) { reply(actor,"A ban for this player is already being saved."); return; }
        String address = ip ? address(target) : null;
        if (ip && address == null) {
            pending.remove(uuid); reply(actor,"No trustworthy address is available; no IP ban was issued."); return;
        }
        if (automatic && address != null && settings.safety().protectSharedIp()) {
            for (Player other : Bukkit.getOnlinePlayers()) if (!other.getUniqueId().equals(uuid) && address.equals(address(other))) {
                address = null;
                getLogger().warning("Shared address detected for " + target.getName() + "; automatic penalty restricted to UUID.");
                break;
            }
        }
        String finalAddress = address;
        String name = target.getName();
        String source = automatic ? "automatic:" + check : "manual:" + actor.getName();
        GuardianSettings captured = settings;
        boolean accepted = executeStorage(() -> {
            try {
                BanRecord ban = bans.ban(uuid, name, finalAddress, clean(reason), source, duration);
                storageHealthy = true;
                onMain(() -> {
                    pending.remove(uuid);
                    enforceOnline(ban, check, captured);
                    reply(actor,"Banned " + name + " until " + expires(ban) + ". ID " + ban.id());
                });
            } catch (Exception failure) {
                storageHealthy = false;
                getLogger().log(Level.SEVERE,"Ban was not applied because persistence failed for " + name, failure);
                onMain(() -> { pending.remove(uuid); reply(actor,"Ban could not be saved; no penalty was issued. Check server logs."); });
            }
        });
        if (!accepted) { pending.remove(uuid); reply(actor,"Storage queue is full; no ban was issued. Retry later."); }
        else reply(actor,"Saving ban for " + name + "...");
    }

    private void enforceOnline(BanRecord ban, String check, GuardianSettings captured) {
        // Disconnection here delivers an already persisted BAN; it is never a standalone kick punishment.
        for (Player player : new ArrayList<>(Bukkit.getOnlinePlayers())) {
            String ip = address(player);
            bans.find(player.getUniqueId(), ip).ifPresent(active -> player.kickPlayer(banScreen(active, captured)));
        }
        if (captured.broadcast()) {
            Map<String,String> values = values(ban);
            values.put("check", check);
            for (String line : captured.banBroadcast()) Bukkit.broadcastMessage(text(line, values));
        }
        getLogger().info("BAN " + ban.playerName() + " id=" + ban.id() + " expires=" + expires(ban));
    }

    public void unban(CommandSender actor, String value, boolean address) {
        if (!running) { reply(actor,"Storage is unavailable."); return; }
        if (!executeStorage(() -> {
            try {
                int count = address ? bans.unbanAddress(value) : bans.unbanPlayer(value);
                storageHealthy = true;
                onMain(() -> reply(actor,"Removed " + count + (address ? " IP restriction(s). UUID bans remain." : " ban(s), including linked IP restrictions.")));
            } catch (Exception failure) {
                getLogger().log(Level.SEVERE,"Unban failed", failure);
                onMain(() -> reply(actor,"Unban failed; existing bans were preserved. Check logs."));
            }
        })) reply(actor,"Storage queue is full; retry later.");
    }

    public void listBans(CommandSender actor, int page) {
        if (!running) { reply(actor,"Storage is unavailable."); return; }
        if (!executeStorage(() -> {
            List<BanRecord> active = bans.listActive();
            int pages = Math.max(1, (active.size() + 7) / 8);
            int selected = Math.min(page, pages);
            List<BanRecord> slice = active.subList((selected - 1) * 8, Math.min(selected * 8, active.size()));
            onMain(() -> {
                reply(actor,"Active bans: " + active.size() + " | page " + selected + "/" + pages);
                for (BanRecord ban : slice) reply(actor, ban.playerName() + " | " + expires(ban) + " | " + ban.reason() + (ban.address() == null ? "" : " | IP restricted"));
            });
        })) reply(actor,"Storage queue is full; retry later.");
    }

    private boolean executeStorage(Runnable task) {
        try { storage.execute(task); return true; }
        catch (RejectedExecutionException e) { getLogger().severe("Storage work rejected; no unsaved punishment will be issued."); return false; }
    }
    private void onMain(Runnable task) { if (running && isEnabled()) Bukkit.getScheduler().runTask(this, task); }

    @EventHandler(priority = EventPriority.HIGHEST)
    public void preLogin(AsyncPlayerPreLoginEvent event) {
        if (!running) { event.disallow(AsyncPlayerPreLoginEvent.Result.KICK_OTHER, "GuardianAC ban storage unavailable. Contact an administrator."); return; }
        try {
            bans.find(event.getUniqueId(), event.getAddress().getHostAddress()).ifPresent(ban ->
                    event.disallow(AsyncPlayerPreLoginEvent.Result.KICK_BANNED, banScreen(ban, settings)));
        } catch (RuntimeException failure) {
            event.disallow(AsyncPlayerPreLoginEvent.Result.KICK_OTHER,"GuardianAC ban storage unavailable. Contact an administrator.");
        }
    }
    @EventHandler(priority = EventPriority.HIGHEST)
    public void login(PlayerLoginEvent event) {
        // Recheck on main thread to close an unban/ban race between async prelogin and join.
        if (!running) { event.disallow(PlayerLoginEvent.Result.KICK_OTHER,"GuardianAC ban storage unavailable. Contact an administrator."); return; }
        bans.find(event.getPlayer().getUniqueId(), event.getAddress().getHostAddress()).ifPresent(ban ->
                event.disallow(PlayerLoginEvent.Result.KICK_BANNED, banScreen(ban, settings)));
    }

    public void status(CommandSender sender) {
        reply(sender,"GuardianAC Ultimate | " + (running ? "active" : "failed closed") + " | storage=" + (storageHealthy ? "healthy" : "error"));
        if (running) reply(sender,"Checks=4 | auto-ban=" + settings.autoBan() + " | mode=" + (settings.ipBan() ? "UUID_AND_IP" : "UUID")
                + " | last tick=" + String.format(Locale.ROOT,"%.1f",lastTickMs) + "ms | lag grace=" + lagging() + " | pending=" + pending.size());
    }
    public void toggleAlerts(Player player) {
        if (!mutedAlerts.add(player.getUniqueId())) { mutedAlerts.remove(player.getUniqueId()); reply(player,"Alerts enabled."); }
        else reply(player,"Alerts muted for this session.");
    }
    public void forget(UUID uuid) {
        engine.reset(uuid); violations.remove(uuid); alertTicks.remove(uuid); mutedAlerts.remove(uuid);
        // A persisted/queued penalty belongs to its UUID, even if the offending session quits.
    }
    private void reply(CommandSender actor, String message) {
        if (actor != null) actor.sendMessage(ChatColor.translateAlternateColorCodes('&',"&8[&bGuardian&fAC&8] &7") + message);
    }
    private static String address(Player player) {
        var socket = player.getAddress();
        return socket == null || socket.getAddress() == null ? null : socket.getAddress().getHostAddress();
    }
    private static String clean(String value) {
        String result = value.replaceAll("[\\p{Cntrl}§]", " ").trim();
        return result.length() > 256 ? result.substring(0,256) : result;
    }
    private static Map<String,String> values(BanRecord ban) {
        Map<String,String> values = new HashMap<>();
        values.put("player",ban.playerName()); values.put("reason",ban.reason()); values.put("expires",expires(ban)); values.put("id",ban.id());
        return values;
    }
    public static String banScreen(BanRecord ban, GuardianSettings config) { return text(config.messages().get("ban-screen"),values(ban)); }
    private static String expires(BanRecord ban) { return ban.expiresAt() == 0 ? "Permanent" : DATE.format(Instant.ofEpochMilli(ban.expiresAt())); }
    private static String text(String template, Map<String,String> values) {
        // Translate trusted templates before inserting data so reasons cannot inject chat formatting.
        String result = ChatColor.translateAlternateColorCodes('&',template);
        for (var entry : values.entrySet()) result = result.replace("{" + entry.getKey() + "}",entry.getValue());
        return result;
    }
    @Override public void onDisable() {
        running = false;
        if (storage != null) {
            storage.shutdown();
            try {
                if (!storage.awaitTermination(10,TimeUnit.SECONDS)) getLogger().severe("Storage shutdown timed out; inspect bans.json before restarting.");
            } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
        }
        if (telemetry != null) telemetry.clear();
        if (engine != null) engine.clear();
        if (violations != null) violations.clear();
    }
}
