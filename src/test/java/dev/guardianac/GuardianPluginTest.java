package dev.guardianac;

import dev.guardianac.ban.BanStore;
import dev.guardianac.command.GuardianCommand;
import dev.guardianac.config.GuardianSettings;
import org.bukkit.Bukkit;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.command.PluginCommand;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.event.player.AsyncPlayerPreLoginEvent;
import org.bukkit.event.player.PlayerLoginEvent;
import org.bukkit.plugin.PluginManager;
import org.bukkit.scheduler.BukkitScheduler;
import org.bukkit.scheduler.BukkitTask;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.MockedStatic;

import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.lang.reflect.Field;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Queue;
import java.util.UUID;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.logging.Level;
import java.util.logging.Logger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.CALLS_REAL_METHODS;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doNothing;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** Real persistence and command/login behavior with deterministic storage/main-thread dispatch. */
class GuardianPluginTest {
    private static final UUID PLAYER_ID = UUID.fromString("11111111-2222-3333-4444-555555555555");
    private static final UUID OTHER_ID = UUID.fromString("aaaaaaaa-bbbb-cccc-dddd-eeeeeeeeeeee");
    private static final String ADDRESS = "192.0.2.10";
    private static final String OTHER_ADDRESS = "192.0.2.11";

    @TempDir Path directory;

    @Test
    void ipBanCommandPersistsBeforeEnforcementAndUnbanRestoresBothIdentityAndAddress() throws Exception {
        try (Fixture f = new Fixture(directory)) {
            f.beforeMainSchedule = () -> {
                BanStore restarted = f.reopen();
                assertTrue(restarted.find(PLAYER_ID, OTHER_ADDRESS).isPresent(),
                        "The UUID penalty must already survive a restart before main-thread enforcement is queued");
                assertTrue(restarted.find(OTHER_ID, ADDRESS).isPresent(),
                        "The linked IP penalty must be persisted in the same transaction");
            };
            f.command("ipban", "Example", "24h", "Verified", "cheating");

            assertEquals(1, f.storageJobs.size());
            assertTrue(f.bans.find(PLAYER_ID, ADDRESS).isEmpty());
            verify(f.target, never()).kickPlayer(anyString());
            assertTrue(f.broadcasts.isEmpty());

            f.runStorage();
            assertEquals(1, f.mainJobs.size());
            verify(f.target, never()).kickPlayer(anyString());
            assertTrue(f.broadcasts.isEmpty(), "An async persistence job must never broadcast directly");

            f.runMain();
            verify(f.target).kickPlayer(contains("Example"));
            assertTrue(f.broadcasts.stream().anyMatch(message -> message.contains("Example")),
                    "The public ban presentation must identify the punished player");
            assertEquals(AsyncPlayerPreLoginEvent.Result.KICK_BANNED,
                    f.preLogin(PLAYER_ID, OTHER_ADDRESS).getLoginResult());
            assertEquals(AsyncPlayerPreLoginEvent.Result.KICK_BANNED,
                    f.preLogin(OTHER_ID, ADDRESS).getLoginResult());
            assertEquals(PlayerLoginEvent.Result.KICK_BANNED,
                    f.login(PLAYER_ID, OTHER_ADDRESS).getResult());

            f.beforeMainSchedule = () -> {};
            f.command("unban", "Example");
            f.runStorage();
            f.runMain();

            BanStore restarted = f.reopen();
            assertTrue(restarted.find(PLAYER_ID, OTHER_ADDRESS).isEmpty());
            assertTrue(restarted.find(OTHER_ID, ADDRESS).isEmpty());
            assertEquals(AsyncPlayerPreLoginEvent.Result.ALLOWED,
                    f.preLogin(PLAYER_ID, OTHER_ADDRESS).getLoginResult());
            assertEquals(AsyncPlayerPreLoginEvent.Result.ALLOWED,
                    f.preLogin(OTHER_ID, ADDRESS).getLoginResult());
            assertEquals(PlayerLoginEvent.Result.ALLOWED,
                    f.login(PLAYER_ID, ADDRESS).getResult());
        }
    }

    @Test
    void temporaryBanAutomaticallyReleasesUuidAndIpAtItsExactExpiry() throws Exception {
        try (Fixture f = new Fixture(directory)) {
            f.command("ipban", "Example", "1s");
            f.runStorage();
            f.runMain();

            f.clock.advance(999);
            assertEquals(AsyncPlayerPreLoginEvent.Result.KICK_BANNED,
                    f.preLogin(PLAYER_ID, OTHER_ADDRESS).getLoginResult());
            assertEquals(AsyncPlayerPreLoginEvent.Result.KICK_BANNED,
                    f.preLogin(OTHER_ID, ADDRESS).getLoginResult());

            f.clock.advance(1);
            assertEquals(AsyncPlayerPreLoginEvent.Result.ALLOWED,
                    f.preLogin(PLAYER_ID, OTHER_ADDRESS).getLoginResult());
            assertEquals(AsyncPlayerPreLoginEvent.Result.ALLOWED,
                    f.preLogin(OTHER_ID, ADDRESS).getLoginResult());
            assertTrue(f.reopen().find(PLAYER_ID, ADDRESS).isEmpty(),
                    "Restarting must not reactivate an expired penalty");
        }
    }

    @Test
    void permanentBanRemainsEnforcedAfterLongElapsedTimeAndReloadingItsData() throws Exception {
        try (Fixture f = new Fixture(directory)) {
            f.command("ban", "Example", "permanent");
            f.runStorage();
            f.runMain();

            f.clock.advance(10L * 365 * 24 * 60 * 60 * 1000);
            set(f.plugin, "bans", f.reopen());
            assertEquals(AsyncPlayerPreLoginEvent.Result.KICK_BANNED,
                    f.preLogin(PLAYER_ID, OTHER_ADDRESS).getLoginResult());
            assertEquals(AsyncPlayerPreLoginEvent.Result.ALLOWED,
                    f.preLogin(OTHER_ID, ADDRESS).getLoginResult(),
                    "A UUID-only permanent penalty must not restrict an unrelated account at that address");
        }
    }

    @Test
    void failedPersistenceCannotDisconnectBroadcastOrCreateAnInMemoryBan() throws Exception {
        try (Fixture f = new Fixture(directory)) {
            // Turning the final data-file destination into a directory forces the real atomic write to fail.
            Files.deleteIfExists(f.path);
            Files.createDirectory(f.path);
            f.command("ipban", "Example", "24h");
            f.runStorage();
            f.runMain();

            assertTrue(f.bans.find(PLAYER_ID, ADDRESS).isEmpty(),
                    "A failed durable write must not publish a penalty in memory");
            verify(f.target, never()).kickPlayer(anyString());
            assertTrue(f.broadcasts.isEmpty());
            verify(f.actor).sendMessage(contains("no penalty was issued"));
            assertEquals(AsyncPlayerPreLoginEvent.Result.ALLOWED,
                    f.preLogin(PLAYER_ID, ADDRESS).getLoginResult());
        }
    }

    @Test
    void invalidStartupConfigurationFailsClosedForBothLoginStages() throws Exception {
        try (Fixture f = new Fixture(directory)) {
            set(f.plugin, "running", false);
            Files.writeString(directory.resolve("config.yml"), "preset: legacy\n");
            doNothing().when(f.plugin).saveDefaultConfig();
            doReturn(directory.toFile()).when(f.plugin).getDataFolder();
            doReturn(mock(PluginCommand.class)).when(f.plugin).getCommand("guardian");

            f.plugin.onEnable();

            assertEquals(AsyncPlayerPreLoginEvent.Result.KICK_OTHER,
                    f.preLogin(PLAYER_ID, ADDRESS).getLoginResult());
            assertEquals(PlayerLoginEvent.Result.KICK_OTHER,
                    f.login(PLAYER_ID, ADDRESS).getResult());
            f.command("ban", "Example", "permanent");
            assertTrue(f.storageJobs.isEmpty());
            assertTrue(f.mainJobs.isEmpty());
            verify(f.target, never()).kickPlayer(anyString());
            assertTrue(f.broadcasts.isEmpty());
        }
    }

    private static final class Fixture implements AutoCloseable {
        final GuardianPlugin plugin = mock(GuardianPlugin.class, CALLS_REAL_METHODS);
        final CommandSender actor = mock(CommandSender.class);
        final Command command = mock(Command.class);
        final Player target = mock(Player.class);
        final BukkitScheduler scheduler = mock(BukkitScheduler.class);
        final ThreadPoolExecutor storage = mock(ThreadPoolExecutor.class);
        final Queue<Runnable> storageJobs = new ArrayDeque<>();
        final Queue<Runnable> mainJobs = new ArrayDeque<>();
        final List<String> broadcasts = new ArrayList<>();
        final MutableClock clock = new MutableClock();
        final Path path;
        final BanStore bans;
        final GuardianCommand commands;
        final MockedStatic<Bukkit> bukkit;
        Runnable beforeMainSchedule = () -> {};

        Fixture(Path directory) throws Exception {
            path = directory.resolve("bans.json");
            bans = new BanStore(path, clock);
            bans.load();
            set(plugin, "settings", settings());
            set(plugin, "bans", bans);
            set(plugin, "storage", storage);
            // Mockito bypasses JavaPlugin's loader-dependent constructor and field initializers.
            set(plugin, "pending", new HashSet<UUID>());
            set(plugin, "mutedAlerts", new HashSet<UUID>());
            set(plugin, "alertTicks", new HashMap<UUID, Long>());
            set(plugin, "running", true);
            set(plugin, "storageHealthy", true);
            Logger logger = Logger.getAnonymousLogger();
            logger.setLevel(Level.OFF);
            doReturn(logger).when(plugin).getLogger();
            doReturn(true).when(plugin).isEnabled();
            when(actor.getName()).thenReturn("Administrator");
            when(actor.hasPermission(anyString())).thenReturn(true);
            when(target.getUniqueId()).thenReturn(PLAYER_ID);
            when(target.getName()).thenReturn("Example");
            when(target.getAddress()).thenReturn(new InetSocketAddress(InetAddress.getByName(ADDRESS), 25565));
            doAnswer(invocation -> {
                storageJobs.add(invocation.getArgument(0, Runnable.class));
                return null;
            }).when(storage).execute(any(Runnable.class));
            doAnswer(invocation -> {
                beforeMainSchedule.run();
                mainJobs.add(invocation.getArgument(1, Runnable.class));
                return mock(BukkitTask.class);
            }).when(scheduler).runTask(eq(plugin), any(Runnable.class));
            bukkit = mockStatic(Bukkit.class);
            bukkit.when(Bukkit::getScheduler).thenReturn(scheduler);
            bukkit.when(Bukkit::getPluginManager).thenReturn(mock(PluginManager.class));
            bukkit.when(Bukkit::getOnlinePlayers).thenReturn(List.of(target));
            bukkit.when(() -> Bukkit.getPlayerExact("Example")).thenReturn(target);
            bukkit.when(() -> Bukkit.broadcastMessage(anyString())).thenAnswer(invocation -> {
                broadcasts.add(invocation.getArgument(0, String.class));
                return 1;
            });
            commands = new GuardianCommand(plugin);
        }

        void command(String... arguments) {
            assertTrue(commands.onCommand(actor, command, "guardian", arguments));
        }

        void runStorage() {
            assertFalse(storageJobs.isEmpty(), "Expected a queued persistence operation");
            storageJobs.remove().run();
        }

        void runMain() {
            assertFalse(mainJobs.isEmpty(), "Expected a queued main-thread result");
            while (!mainJobs.isEmpty()) mainJobs.remove().run();
        }

        BanStore reopen() {
            try {
                BanStore restarted = new BanStore(path, clock);
                restarted.load();
                return restarted;
            } catch (IOException failure) {
                throw new AssertionError("Ban data must remain readable", failure);
            }
        }

        AsyncPlayerPreLoginEvent preLogin(UUID id, String address) throws Exception {
            AsyncPlayerPreLoginEvent event = new AsyncPlayerPreLoginEvent("Joining", InetAddress.getByName(address), id);
            plugin.preLogin(event);
            return event;
        }

        PlayerLoginEvent login(UUID id, String address) throws Exception {
            Player joining = mock(Player.class);
            when(joining.getUniqueId()).thenReturn(id);
            PlayerLoginEvent event = new PlayerLoginEvent(joining, "example.test", InetAddress.getByName(address));
            plugin.login(event);
            return event;
        }

        @Override public void close() {
            bukkit.close();
        }
    }

    private static GuardianSettings settings() throws Exception {
        YamlConfiguration configuration = new YamlConfiguration();
        try (InputStream input = GuardianPluginTest.class.getResourceAsStream("/config.yml")) {
            assertNotNull(input);
            configuration.load(new InputStreamReader(input, StandardCharsets.UTF_8));
        }
        return GuardianSettings.read(configuration);
    }

    private static void set(Object target, String name, Object value) throws Exception {
        Field field = GuardianPlugin.class.getDeclaredField(name);
        field.setAccessible(true);
        field.set(target, value);
    }

    private static final class MutableClock extends Clock {
        private Instant now = Instant.parse("2026-01-01T00:00:00Z");
        void advance(long millis) { now = now.plusMillis(millis); }
        @Override public ZoneId getZone() { return ZoneOffset.UTC; }
        @Override public Clock withZone(ZoneId zone) { return this; }
        @Override public Instant instant() { return now; }
    }
}
