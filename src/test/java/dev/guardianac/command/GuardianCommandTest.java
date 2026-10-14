package dev.guardianac.command;

import dev.guardianac.GuardianPlugin;
import org.bukkit.Bukkit;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.MockedStatic;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class GuardianCommandTest {
    private GuardianPlugin plugin;
    private CommandSender sender;
    private Command command;
    private GuardianCommand executor;

    @BeforeEach
    void prepare() {
        plugin = mock(GuardianPlugin.class);
        sender = mock(CommandSender.class);
        command = mock(Command.class);
        executor = new GuardianCommand(plugin);
        when(sender.hasPermission(anyString())).thenReturn(true);
    }

    @Test
    void deniedBanCannotLookUpOrPunishTarget() {
        when(sender.hasPermission("guardianac.ipban")).thenReturn(false);
        try (MockedStatic<Bukkit> bukkit = mockStatic(Bukkit.class)) {
            executor.onCommand(sender, command, "gac", new String[]{"ipban", "Example", "permanent"});
            bukkit.verifyNoInteractions();
        }
        verifyNoInteractions(plugin);
        verify(sender).sendMessage(contains("permission"));
    }

    @Test
    void deniedCompletionCannotExposeOnlinePlayers() {
        when(sender.hasPermission(anyString())).thenReturn(false);
        try (MockedStatic<Bukkit> bukkit = mockStatic(Bukkit.class)) {
            assertEquals(List.of(), executor.onTabComplete(sender, command, "gac", new String[]{"ban", ""}));
            assertEquals(List.of(), executor.onTabComplete(sender, command, "gac", new String[]{""}));
            bukkit.verifyNoInteractions();
        }
    }

    @Test
    void sendsOnlineIpBanThroughSingleServiceWithExactDurationAndDefaultReason() {
        Player target = mock(Player.class);
        try (MockedStatic<Bukkit> bukkit = mockStatic(Bukkit.class)) {
            bukkit.when(() -> Bukkit.getPlayerExact("Example")).thenReturn(target);
            executor.onCommand(sender, command, "gac", new String[]{"ipban", "Example", "24h"});
            bukkit.verify(() -> Bukkit.getPlayerExact("Example"));
        }
        verify(plugin).submitBan(sender, target, true, 86_400_000L, "Cheat detection");
    }

    @Test
    void joinsMultiwordReasonAndUsesPermanentSentinel() {
        Player target = mock(Player.class);
        try (MockedStatic<Bukkit> bukkit = mockStatic(Bukkit.class)) {
            bukkit.when(() -> Bukkit.getPlayerExact("Example")).thenReturn(target);
            executor.onCommand(sender, command, "gac", new String[]{"ban", "Example", "permanent", "Verified", "cheating"});
        }
        verify(plugin).submitBan(sender, target, false, 0L, "Verified cheating");
    }

    @Test
    void offlineBanIsExplicitlyRejectedWithoutOfflineLookup() {
        try (MockedStatic<Bukkit> bukkit = mockStatic(Bukkit.class)) {
            executor.onCommand(sender, command, "gac", new String[]{"ban", "Offline", "7d"});
            bukkit.verify(() -> Bukkit.getPlayerExact("Offline"));
            bukkit.verifyNoMoreInteractions();
        }
        verifyNoInteractions(plugin);
        verify(sender).sendMessage(contains("requires an online player"));
    }

    @ParameterizedTest
    @ValueSource(strings = {"example.org", "localhost", "256.1.1.1", "127.1", "01.2.3.4", "1.2.3.4:25565", "::gg", "::1%eth0"})
    void rejectsHostnamesAndInvalidIpLiterals(String value) {
        executor.onCommand(sender, command, "gac", new String[]{"unbanip", value});
        verifyNoInteractions(plugin);
        verify(sender).sendMessage(contains("numeric IPv4 or IPv6"));
    }

    @ParameterizedTest
    @ValueSource(strings = {"127.0.0.1", "0.0.0.0", "255.255.255.255", "::1", "2001:db8::1234", "::ffff:192.0.2.1"})
    void acceptsLiteralAddressesWithoutNameLookup(String value) {
        executor.onCommand(sender, command, "gac", new String[]{"unbanip", value});
        verify(plugin).unban(sender, value, true);
    }

    @Test
    void invalidDurationCannotSubmitPunishmentOrLookupPlayer() {
        try (MockedStatic<Bukkit> bukkit = mockStatic(Bukkit.class)) {
            executor.onCommand(sender, command, "gac", new String[]{"ban", "Example", "0s"});
            bukkit.verifyNoInteractions();
        }
        verifyNoInteractions(plugin);
    }

    @Test
    void consoleCannotToggleAlerts() {
        executor.onCommand(sender, command, "guardian", new String[]{"alerts"});
        verifyNoInteractions(plugin);
        verify(sender).sendMessage(contains("Only players"));
    }

    @ParameterizedTest
    @ValueSource(strings = {"0", "-1", "999999999999999999999", "1.5", "01"})
    void rejectsInvalidBanListPages(String value) {
        executor.onCommand(sender, command, "gac", new String[]{"bans", value});
        verifyNoInteractions(plugin);
    }
}
