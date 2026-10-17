package test.guardianip;

import dev.guardianac.bukkit.GuardianPlugin;
import dev.guardianac.packet.PacketAdapter;
import org.bukkit.*;
import test.guardianfixture.safety.FixturePorts;
import org.bukkit.command.*;
import org.bukkit.entity.Player;
import org.bukkit.event.*;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.plugin.java.JavaPlugin;
import java.lang.reflect.Field;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.time.Instant;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.TimeUnit;

/** Isolated console-only loopback fixture. Never creates sanctions or feeds engine samples. */
public final class RealClientFixture extends JavaPlugin implements Listener {
    static final String ADDRESS = "127.0.0.31";
    static final Set<String> NAMES = Set.of("GacIpBad", "GacIpSame", "GacIpOther", "GacIpShared");
    GuardianPlugin guardian;
    PacketAdapter packets;
    static UUID id(String name) { return UUID.nameUUIDFromBytes(("OfflinePlayer:" + name).getBytes(StandardCharsets.UTF_8)); }
    @Override public void onEnable() {
        FixturePorts.requireEndpoint("paper-26.3", Bukkit.getIp(), Bukkit.getPort(), Bukkit.getOnlineMode());
        guardian = (GuardianPlugin) Bukkit.getPluginManager().getPlugin("GuardianAC");
        try {
            Field field = GuardianPlugin.class.getDeclaredField("packets"); field.setAccessible(true);
            packets = (PacketAdapter) field.get(guardian);
        } catch (ReflectiveOperationException failure) { throw new IllegalStateException(failure); }
        Objects.requireNonNull(getCommand("gacip")).setExecutor(this);
        Bukkit.getPluginManager().registerEvents(this, this);
    }
    @EventHandler public void onJoin(PlayerJoinEvent event) {
        Player player = event.getPlayer();
        if (!NAMES.contains(player.getName())) return;
        Bukkit.getScheduler().runTaskLater(this, () -> {
            if (!player.isOnline()) return;
            player.teleport(new Location(Bukkit.getWorlds().getFirst(), 8.5, 65, 8.5));
            player.setGameMode(GameMode.SURVIVAL); player.setInvulnerable(true); player.setFoodLevel(20);
        }, 2);
    }
    void setup() {
        World world = Bukkit.getWorlds().getFirst();
        for (int x = 4; x <= 12; x++) for (int z = 4; z <= 12; z++) {
            world.getBlockAt(x, 64, z).setType(Material.STONE, false);
            for (int y = 65; y <= 69; y++) world.getBlockAt(x, y, z).setType(Material.AIR, false);
        }
        world.setSpawnLocation(8, 65, 8);
    }
    String q(Object value) { return "\"" + String.valueOf(value).replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", "\\n") + "\""; }
    void report(String label) throws Exception {
        if (!label.matches("[a-z0-9-]{1,48}")) throw new IllegalArgumentException("Invalid report label");
        Instant since = Instant.now().minus(Duration.ofDays(30));
        int trusted = guardian.storage().trustedAccountCount(ADDRESS, since).get(5, TimeUnit.SECONDS);
        int observed = guardian.storage().observedAccountCount(ADDRESS, since).get(5, TimeUnit.SECONDS);
        var association = guardian.storage().ipAssociation(id("GacIpBad"), ADDRESS, since).get(5, TimeUnit.SECONDS);
        StringBuilder out = new StringBuilder("{\"label\":").append(q(label))
            .append(",\"trustedAssociations\":").append(trusted)
            .append(",\"observedAssociations\":").append(observed)
            .append(",\"targetTrustedAssociation\":").append(association.targetTrusted())
            .append(",\"nativeOnlineMode\":").append(Bukkit.getOnlineMode())
            .append(",\"cancelled\":").append(packets.cancelledPackets())
            .append(",\"malformed\":").append(packets.malformedPackets())
            .append(",\"queued\":").append(packets.queuedSamples())
            .append(",\"dropped\":").append(packets.droppedSamples()).append(",\"players\":[");
        boolean first = true;
        for (Player player : Bukkit.getOnlinePlayers()) {
            if (!first) out.append(','); first = false;
            out.append("{\"name\":").append(q(player.getName()))
                .append(",\"uuid\":").append(q(player.getUniqueId()))
                .append(",\"address\":").append(q(player.getAddress().getAddress().getHostAddress()))
                .append(",\"exactProtocol\":").append(packets.exactProtocol(player.getUniqueId()))
                .append(",\"slot\":").append(player.getInventory().getHeldItemSlot())
                .append(",\"pitch\":").append(player.getLocation().getPitch())
                .append(",\"findings\":").append(guardian.engine().current(player.getUniqueId()).size()).append('}');
        }
        out.append("]}"); getDataFolder().mkdirs(); Files.writeString(getDataFolder().toPath().resolve(label + ".json"), out);
        getLogger().info("GACIP " + out);
    }
    @Override public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (!(sender instanceof ConsoleCommandSender)) throw new IllegalArgumentException("Console only");
        try {
            if (args.length == 1 && args[0].equals("setup")) setup();
            else if (args.length == 2 && args[0].equals("report")) report(args[1]);
            else throw new IllegalArgumentException("Unknown bounded fixture command");
            getLogger().info("GACIP COMMAND " + args[0] + " DONE");
        } catch (Exception failure) { getLogger().log(java.util.logging.Level.SEVERE, "GACIP ERROR", failure); }
        return true;
    }
}
