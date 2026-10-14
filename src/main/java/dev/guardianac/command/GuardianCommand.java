package dev.guardianac.command;

import dev.guardianac.GuardianPlugin;
import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.entity.Player;

import java.net.InetAddress;
import java.net.UnknownHostException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Pattern;

/** Administrative commands; offline account lookup and network name resolution are never used. */
public final class GuardianCommand implements CommandExecutor, TabCompleter {
    private static final String VIEW = "guardianac.view";
    private static final String ADMIN = "guardianac.admin";
    private static final String BAN = "guardianac.ban";
    private static final String IPBAN = "guardianac.ipban";
    private static final String UNBAN = "guardianac.unban";
    private static final String ALERTS = "guardianac.alerts";
    private static final String PREFIX = ChatColor.DARK_GRAY + "[" + ChatColor.GOLD + "GuardianAC"
            + ChatColor.DARK_GRAY + "] ";
    private static final Pattern USERNAME = Pattern.compile("[A-Za-z0-9_]{1,16}");
    private static final Pattern UUID = Pattern.compile(
            "[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}");
    private static final Map<String, String> PERMISSIONS = Map.ofEntries(
            Map.entry("status", VIEW), Map.entry("reload", ADMIN), Map.entry("alerts", ALERTS),
            Map.entry("vl", VIEW), Map.entry("ban", BAN), Map.entry("ipban", IPBAN),
            Map.entry("unban", UNBAN), Map.entry("unbanip", UNBAN), Map.entry("bans", VIEW));
    private static final List<String> SUBCOMMANDS = List.of(
            "status", "reload", "alerts", "vl", "ban", "ipban", "unban", "unbanip", "bans");
    private static final List<String> DURATIONS = List.of("30m", "24h", "7d", "permanent");

    private final GuardianPlugin plugin;

    public GuardianCommand(GuardianPlugin plugin) {
        this.plugin = plugin;
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        String action = args.length == 0 ? "status" : args[0].toLowerCase(Locale.ROOT);
        String permission = PERMISSIONS.get(action);
        if (permission == null) {
            help(sender, label);
            return true;
        }
        if (!sender.hasPermission(permission)) {
            error(sender, "You do not have permission to use this command.");
            return true;
        }

        try {
            switch (action) {
                case "status" -> {
                    if (args.length > 1) usage(sender, label, "status");
                    else plugin.status(sender);
                }
                case "reload" -> {
                    if (args.length != 1) usage(sender, label, "reload");
                    else {
                        plugin.reloadGuardian();
                        sender.sendMessage(PREFIX + ChatColor.GREEN + "Configuration reloaded.");
                    }
                }
                case "alerts" -> {
                    if (args.length != 1) usage(sender, label, "alerts");
                    else if (sender instanceof Player player) plugin.toggleAlerts(player);
                    else error(sender, "Only players can toggle in-game alerts.");
                }
                case "vl" -> {
                    if (args.length != 2) usage(sender, label, "vl <online-player>");
                    else {
                        Player target = onlinePlayer(sender, args[1]);
                        if (target != null) {
                            sender.sendMessage(PREFIX + ChatColor.WHITE + target.getName() + ChatColor.GRAY
                                    + " VL: " + ChatColor.GOLD
                                    + String.format(Locale.ROOT, "%.2f", plugin.violation(target.getUniqueId())));
                        }
                    }
                }
                case "ban", "ipban" -> {
                    if (args.length < 3) usage(sender, label, action + " <online-player> <duration> [reason]");
                    else {
                        long duration = DurationParser.parse(args[2]);
                        Player target = onlinePlayer(sender, args[1]);
                        if (target != null) {
                            String reason = args.length > 3
                                    ? String.join(" ", Arrays.copyOfRange(args, 3, args.length))
                                    : "Cheat detection";
                            plugin.submitBan(sender, target, action.equals("ipban"), duration, reason);
                        }
                    }
                }
                case "unban" -> {
                    if (args.length != 2) usage(sender, label, "unban <name|uuid>");
                    else if (!USERNAME.matcher(args[1]).matches() && !UUID.matcher(args[1]).matches()) {
                        error(sender, "Specify a player name or a full UUID.");
                    } else plugin.unban(sender, args[1], false);
                }
                case "unbanip" -> {
                    if (args.length != 2) usage(sender, label, "unbanip <literal-ip>");
                    else if (!isIpLiteral(args[1])) error(sender, "Specify a numeric IPv4 or IPv6 address.");
                    else plugin.unban(sender, args[1], true);
                }
                case "bans" -> {
                    if (args.length > 2) usage(sender, label, "bans [page]");
                    else plugin.listBans(sender, args.length == 2 ? parsePage(args[1]) : 1);
                }
                default -> help(sender, label);
            }
        } catch (IllegalArgumentException exception) {
            error(sender, exception.getMessage() == null ? "Invalid command arguments." : exception.getMessage());
        }
        return true;
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
        if (args.length == 1) {
            return matching(SUBCOMMANDS.stream()
                    .filter(name -> sender.hasPermission(PERMISSIONS.get(name)))
                    .filter(name -> !name.equals("alerts") || sender instanceof Player).toList(), args[0]);
        }
        if (args.length < 2) return List.of();
        String action = args[0].toLowerCase(Locale.ROOT);
        String permission = PERMISSIONS.get(action);
        if (permission == null || !sender.hasPermission(permission)) return List.of();
        if (args.length == 2 && List.of("vl", "ban", "ipban").contains(action)) {
            List<String> names = new ArrayList<>();
            for (Player player : Bukkit.getOnlinePlayers()) {
                if (!(sender instanceof Player viewer) || viewer.canSee(player)) names.add(player.getName());
            }
            names.sort(String.CASE_INSENSITIVE_ORDER);
            return matching(names, args[1]);
        }
        if (args.length == 3 && (action.equals("ban") || action.equals("ipban"))) {
            return matching(DURATIONS, args[2]);
        }
        if (args.length == 2 && action.equals("bans")) return matching(List.of("1"), args[1]);
        return List.of();
    }

    private static Player onlinePlayer(CommandSender sender, String name) {
        Player target = Bukkit.getPlayerExact(name);
        if (target == null) error(sender, "That player is not online. This command requires an online player.");
        return target;
    }

    private static int parsePage(String input) {
        if (!input.matches("[1-9][0-9]*")) throw new IllegalArgumentException("Page must be a positive integer.");
        try {
            return Integer.parseInt(input);
        } catch (NumberFormatException exception) {
            throw new IllegalArgumentException("Page is too large.", exception);
        }
    }

    private static boolean isIpLiteral(String input) {
        if (input.indexOf(':') >= 0) {
            // The whitelist and required colon rule out DNS names before using Java's IPv6 parser.
            if (!input.matches("[0-9a-fA-F:.]+")) return false;
            try {
                InetAddress.getByName(input);
                return true;
            } catch (UnknownHostException exception) {
                return false;
            }
        }
        String[] octets = input.split("\\.", -1);
        if (octets.length != 4) return false;
        for (String octet : octets) {
            if (!octet.matches("0|[1-9][0-9]{0,2}") || Integer.parseInt(octet) > 255) return false;
        }
        return true;
    }

    private static List<String> matching(List<String> candidates, String prefix) {
        String lowerPrefix = prefix.toLowerCase(Locale.ROOT);
        return candidates.stream().filter(value -> value.toLowerCase(Locale.ROOT).startsWith(lowerPrefix)).toList();
    }

    private static void help(CommandSender sender, String label) {
        List<String> available = SUBCOMMANDS.stream()
                .filter(name -> sender.hasPermission(PERMISSIONS.get(name)))
                .filter(name -> !name.equals("alerts") || sender instanceof Player).toList();
        if (available.isEmpty()) error(sender, "You do not have permission to use GuardianAC commands.");
        else usage(sender, label, "<" + String.join("|", available) + ">");
    }

    private static void usage(CommandSender sender, String label, String syntax) {
        error(sender, "Usage: /" + label + " " + syntax);
    }

    private static void error(CommandSender sender, String message) {
        sender.sendMessage(PREFIX + ChatColor.RED + message);
    }
}
