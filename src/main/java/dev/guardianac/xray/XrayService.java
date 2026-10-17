/* SPDX-License-Identifier: GPL-3.0-only */
package dev.guardianac.xray;

import dev.guardianac.core.Sample;
import dev.guardianac.packet.PacketAdapter;
import dev.guardianac.packet.PacketContext;
import org.bukkit.*;
import org.bukkit.block.Block;
import org.bukkit.entity.Player;
import org.bukkit.event.*;
import org.bukkit.event.block.*;
import org.bukkit.event.entity.EntityExplodeEvent;
import org.bukkit.event.player.*;
import org.bukkit.event.world.WorldLoadEvent;
import org.bukkit.plugin.java.JavaPlugin;

import java.util.*;
import java.util.function.Consumer;

/** Main-thread world access, reveal, and mining observation. No server config is mutated. */
public final class XrayService implements Listener,AutoCloseable {
    private static final int[][] FACES={{1,0,0},{-1,0,0},{0,1,0},{0,-1,0},{0,0,1},{0,0,-1}};
    private final JavaPlugin plugin;
    private final PacketAdapter packets;
    private final Consumer<Sample> sink;
    private final ChunkConcealment concealment=new ChunkConcealment();
    private final MiningStatistics mining=new MiningStatistics();
    private final Map<UUID,NativeAntiXray.Mode> nativeModes=new HashMap<>();
    private final Map<UUID,LinkedHashMap<BlockPosition,Long>> hiddenCandidates=new HashMap<>();
    private final LinkedHashSet<WorldPosition> updates=new LinkedHashSet<>();
    private long currentTick;
    private double currentTps=20;
    private boolean started;
    public XrayService(JavaPlugin plugin,PacketAdapter packets,Consumer<Sample> sink){this.plugin=plugin;this.packets=packets;this.sink=sink;}
    public void start(){requireMain();if(started)return;started=true;Bukkit.getPluginManager().registerEvents(this,plugin);Bukkit.getWorlds().forEach(this::inspect);concealment.prepare(com.github.retrooper.packetevents.PacketEvents.getAPI().getServerManager().getVersion().toClientVersion());packets.concealment(concealment);}
    // Reconfiguration invalidates uncommitted rewrites. Already committed pending sends keep their
    // ledger until activation; disabled concealment drains them below (1,024/player, 8,192/global/tick).
    // The work/memory bound is strict, but network completion and low TPS have no wall-clock bound.
    public void configure(XraySettings settings){requireMain();concealment.configure(settings);
        if(!settings.observesMining()){mining.clear();hiddenCandidates.clear();}}
    public XraySettings settings(){return concealment.settings();}
    public ChunkConcealment concealment(){return concealment;}
    public Map<UUID,NativeAntiXray.Mode> nativeModes(){return Map.copyOf(nativeModes);}
    private void inspect(World world){nativeModes.put(world.getUID(),NativeAntiXray.inspect(world));}
    public void tick(long tick,double tps){
        requireMain();currentTick=tick;currentTps=tps;XraySettings config=settings();
        if(tick%400==0)Bukkit.getWorlds().forEach(this::inspect);
        int globalRevealBudget=8192;
        for(Player player:Bukkit.getOnlinePlayers()){
            Location at=player.getLocation();World world=player.getWorld();
            NativeAntiXray.Mode mode=nativeModes.getOrDefault(world.getUID(),NativeAntiXray.Mode.UNKNOWN);
            boolean bypass=player.getGameMode()==GameMode.SPECTATOR||player.hasPermission("guardianac.xray.bypass");
            concealment.publish(player.getUniqueId(),new ChunkConcealment.Viewer(world.getUID(),world.getMinHeight(),world.getMaxHeight(),
                at.getX(),at.getY(),at.getZ(),System.currentTimeMillis(),world.getEnvironment().name(),mode,bypass));
            if(globalRevealBudget<=0)continue;
            int limit=Math.min(1024,globalRevealBudget);
            List<BlockPosition> reveal=(!config.enabled()||(!config.concealOres()&&!config.concealCaves())||bypass)
                ?concealment.cache().takeAll(player.getUniqueId(),world.getUID(),limit)
                :concealment.cache().takeNearby(player.getUniqueId(),world.getUID(),at.getX(),at.getY(),at.getZ(),config.concealCaves()?Math.max(24,config.revealRadius()):config.revealRadius(),limit);
            reveal(player,world,reveal);globalRevealBudget-=reveal.size();
        }
        // A bounded queue coalesces explosion/piston updates and never force-loads a chunk.
        Iterator<WorldPosition> iterator=updates.iterator();int processed=0;
        while(iterator.hasNext()&&processed++<128&&globalRevealBudget>0){
            WorldPosition changed=iterator.next();iterator.remove();World world=Bukkit.getWorld(changed.world);if(world==null)continue;
            for(Player player:world.getPlayers()){
                if(globalRevealBudget<=0)break;
                List<BlockPosition> reveal=concealment.cache().takeNearby(player.getUniqueId(),world.getUID(),changed.position.x()+.5,changed.position.y()+.5,changed.position.z()+.5,2,Math.min(64,globalRevealBudget));
                reveal(player,world,reveal);globalRevealBudget-=reveal.size();
            }
        }
    }
    private void reveal(Player player,World world,List<BlockPosition> positions){
        for(BlockPosition p:positions)if(world.isChunkLoaded(p.x()>>4,p.z()>>4)&&p.y()>=world.getMinHeight()&&p.y()<world.getMaxHeight())
            player.sendBlockChange(new Location(world,p.x(),p.y(),p.z()),world.getBlockAt(p.x(),p.y(),p.z()).getBlockData());
    }
    private void queue(Block block){if(updates.size()<4096)updates.add(new WorldPosition(block.getWorld().getUID(),pos(block)));}
    @EventHandler(priority=EventPriority.MONITOR,ignoreCancelled=true) public void onBreak(BlockBreakEvent event){
        Block block=event.getBlock();queue(block);if(!settings().observesMining())return;
        Player player=event.getPlayer();UUID id=player.getUniqueId();long now=System.currentTimeMillis();
        if(player.getGameMode()!=GameMode.SURVIVAL||player.hasPermission("guardianac.bypass"))return;
        LinkedHashMap<BlockPosition,Long> candidates=hiddenCandidates.computeIfAbsent(id,key->new LinkedHashMap<>());
        candidates.entrySet().removeIf(e->now-e.getValue()>120_000);
        boolean wasHidden=candidates.remove(pos(block))!=null;
        for(int[] face:FACES){int y=block.getY()+face[1];if(y<block.getWorld().getMinHeight()||y>=block.getWorld().getMaxHeight())continue;
            int x=block.getX()+face[0],z=block.getZ()+face[2];if(!block.getWorld().isChunkLoaded(x>>4,z>>4))continue;
            Block neighbor=block.getWorld().getBlockAt(x,y,z);
            if(rare(neighbor.getType())&&enclosed(neighbor)){if(candidates.size()>=512)candidates.remove(candidates.keySet().iterator().next());candidates.put(pos(neighbor),now);}
        }
        PacketContext context=packets.context(id).orElse(null);
        Sample sample=mining.record(id,block.getWorld().getUID(),now,currentTick,pos(block),rare(block.getType()),wasHidden,
            currentTps,player.getPing(),context==null?Set.of("exempt"):context.flags());
        if(sample!=null)sink.accept(sample);
    }
    private static boolean enclosed(Block block){for(int[] f:FACES){int x=block.getX()+f[0],y=block.getY()+f[1],z=block.getZ()+f[2];World w=block.getWorld();
        if(y<w.getMinHeight()||y>=w.getMaxHeight()||!w.isChunkLoaded(x>>4,z>>4)||!w.getBlockAt(x,y,z).getType().isOccluding())return false;}return true;}
    private static boolean rare(Material material){return switch(material){case DIAMOND_ORE,DEEPSLATE_DIAMOND_ORE,EMERALD_ORE,DEEPSLATE_EMERALD_ORE,ANCIENT_DEBRIS->true;default->false;};}
    @EventHandler(priority=EventPriority.MONITOR,ignoreCancelled=true) public void onPlace(BlockPlaceEvent e){queue(e.getBlock());}
    @EventHandler(priority=EventPriority.MONITOR,ignoreCancelled=true) public void onPhysics(BlockPhysicsEvent e){queue(e.getBlock());}
    @EventHandler(priority=EventPriority.MONITOR,ignoreCancelled=true) public void onEntityExplode(EntityExplodeEvent e){e.blockList().stream().limit(512).forEach(this::queue);}
    @EventHandler(priority=EventPriority.MONITOR,ignoreCancelled=true) public void onBlockExplode(BlockExplodeEvent e){e.blockList().stream().limit(512).forEach(this::queue);}
    @EventHandler(priority=EventPriority.MONITOR,ignoreCancelled=true) public void onPiston(BlockPistonExtendEvent e){e.getBlocks().forEach(this::queue);}
    @EventHandler(priority=EventPriority.MONITOR,ignoreCancelled=true) public void onRetract(BlockPistonRetractEvent e){e.getBlocks().forEach(this::queue);}
    @EventHandler public void onLoad(WorldLoadEvent e){inspect(e.getWorld());}
    @EventHandler public void onQuit(PlayerQuitEvent e){forget(e.getPlayer().getUniqueId());}
    @EventHandler public void onWorld(PlayerChangedWorldEvent e){forget(e.getPlayer().getUniqueId());}
    private void forget(UUID id){concealment.forget(id);mining.forget(id);hiddenCandidates.remove(id);}
    @Override public void close(){requireMain();HandlerList.unregisterAll(this);concealment.stop();packets.concealment(null);for(Player p:Bukkit.getOnlinePlayers()){
        List<BlockPosition> restore=concealment.cache().takeAllIncludingPending(p.getUniqueId(),p.getWorld().getUID(),131072);reveal(p,p.getWorld(),restore);forget(p.getUniqueId());}
        hiddenCandidates.clear();updates.clear();nativeModes.clear();started=false;}
    private static BlockPosition pos(Block block){return new BlockPosition(block.getX(),block.getY(),block.getZ());}
    private static void requireMain(){if(!Bukkit.isPrimaryThread())throw new IllegalStateException("Xray world access on server thread");}
    private record WorldPosition(UUID world,BlockPosition position){ }
}
