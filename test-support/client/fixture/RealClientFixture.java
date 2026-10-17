package test.guardianreal;
import org.bukkit.*;
import org.bukkit.command.*;
import org.bukkit.entity.Player;
import org.bukkit.event.*;
import org.bukkit.event.player.*;
import org.bukkit.plugin.java.JavaPlugin;
import dev.guardianac.bukkit.GuardianPlugin;
import dev.guardianac.packet.PacketAdapter;
import dev.guardianac.libs.pe.api.packetevents.PacketEvents;
import dev.guardianac.libs.pe.api.packetevents.event.*;
import dev.guardianac.libs.pe.api.packetevents.protocol.world.states.type.StateTypes;
import java.lang.reflect.Field;
import java.nio.file.Files;
import java.util.*;

public final class RealClientFixture extends JavaPlugin implements Listener {
 final java.util.concurrent.ConcurrentHashMap<UUID,java.util.concurrent.atomic.AtomicInteger> observedDisconnects=new java.util.concurrent.ConcurrentHashMap<>();
 final IdentityHashMap<Object,Integer> stateTokens=new IdentityHashMap<>();
 final PacketListenerAbstract disconnectObserver=new PacketListenerAbstract(PacketListenerPriority.MONITOR){
  @Override public void onUserDisconnect(UserDisconnectEvent event){UUID id=event.getUser().getUUID();if(id!=null)observedDisconnects.computeIfAbsent(id,k->new java.util.concurrent.atomic.AtomicInteger()).incrementAndGet();}
 };
 GuardianPlugin guardian; PacketAdapter packets; final java.util.ArrayDeque<String> ingressTrace=new java.util.ArrayDeque<>();
 @Override public void onEnable(){try{
  guardian=(GuardianPlugin)Bukkit.getPluginManager().getPlugin("GuardianAC");
  Field f=GuardianPlugin.class.getDeclaredField("packets");f.setAccessible(true);packets=(PacketAdapter)f.get(guardian);
  if(System.getenv("GUARDIAN_TRACE")!=null){Field sink=PacketAdapter.class.getDeclaredField("sink");sink.setAccessible(true);
  @SuppressWarnings("unchecked") java.util.function.Consumer<dev.guardianac.core.Sample> original=(java.util.function.Consumer<dev.guardianac.core.Sample>)sink.get(packets);
  sink.set(packets,(java.util.function.Consumer<dev.guardianac.core.Sample>)sample->{
   try{Field players=dev.guardianac.core.DetectionEngine.class.getDeclaredField("players");players.setAccessible(true);Object state=((java.util.Map<?,?>)players.get(guardian.engine())).get(sample.playerId());long last=-1;
    if(state!=null){Field lastTime=state.getClass().getDeclaredField("lastTime");lastTime.setAccessible(true);last=lastTime.getLong(state);}
    ingressTrace.add("{\"engineLastBefore\":"+last+",\"sample\":"+dev.guardianac.security.ReplayBuffer.sampleJson(sample)+"}");while(ingressTrace.size()>1000)ingressTrace.removeFirst();
   }catch(Exception e){throw new RuntimeException(e);}original.accept(sample);
  });
  }
  PacketEvents.getAPI().getEventManager().registerListener(disconnectObserver);
  getCommand("gacreal").setExecutor(this);Bukkit.getPluginManager().registerEvents(this,this);
 }catch(Exception e){throw new RuntimeException(e);}}
 @Override public void onDisable(){try{PacketEvents.getAPI().getEventManager().unregisterListener(disconnectObserver);}catch(Exception ignored){}}
 int packetStateToken(UUID id)throws Exception{Field f=PacketAdapter.class.getDeclaredField("states");f.setAccessible(true);Object state=((Map<?,?>)f.get(packets)).get(id);return state==null?0:stateTokens.computeIfAbsent(state,k->stateTokens.size()+1);}
 @EventHandler public void onJoin(PlayerJoinEvent e){if(e.getPlayer().getName().startsWith("GacReal"))Bukkit.getScheduler().runTaskLater(this,()->{
  Player p=e.getPlayer();p.teleport(new Location(Bukkit.getWorlds().getFirst(),8.5,65,8.5));p.setGameMode(GameMode.SURVIVAL);p.setInvulnerable(true);p.setFoodLevel(20);
 },2);}
 void setup(){World w=Bukkit.getWorlds().getFirst();
  for(int x=-8;x<=56;x++)for(int z=-8;z<=24;z++){
   w.getBlockAt(x,64,z).setType(Material.STONE,false);
   for(int y=65;y<=70;y++)w.getBlockAt(x,y,z).setType(Material.AIR,false);
  }
  for(int x=37;x<=43;x++)for(int y=17;y<=23;y++)for(int z=5;z<=11;z++)w.getBlockAt(x,y,z).setType(Material.STONE,false);
  w.getBlockAt(40,20,8).setType(Material.DIAMOND_ORE,false);w.setSpawnLocation(8,65,8);
 }
 void caveSetup(){World w=Bukkit.getWorlds().getFirst();
  for(int x=32;x<48;x++)for(int y=-64;y<0;y++)for(int z=0;z<16;z++)w.getBlockAt(x,y,z).setType(Material.STONE,false);
  for(int x=39;x<=41;x++)for(int y=-25;y<=-23;y++)for(int z=7;z<=9;z++)w.getBlockAt(x,y,z).setType(Material.AIR,false);
 }
 String q(Object x){return "\""+String.valueOf(x).replace("\\","\\\\").replace("\"","\\\"").replace("\n","\\n")+"\"";}
 long metric(Object o,String name){try{return ((Number)o.getClass().getMethod(name).invoke(o)).longValue();}catch(Exception unavailable){return -1;}}
 void report(String label)throws Exception{getDataFolder().mkdirs();
  World w=Bukkit.getWorlds().getFirst();var cc=guardian.xray().concealment();var cv=PacketEvents.getAPI().getServerManager().getVersion().toClientVersion();
  StringBuilder b=new StringBuilder("{\"label\":").append(q(label)).append(",\"cancelled\":").append(packets.cancelledPackets()).append(",\"malformed\":").append(packets.malformedPackets()).append(",\"dropped\":").append(packets.droppedSamples()).append(",\"queue\":").append(packets.queuedSamples()).append(",\"contextCaptureFailures\":").append(metric(packets,"contextCaptureFailures")).append(",\"concealedChunks\":").append(cc.concealedChunks()).append(",\"concealedBlocks\":").append(cc.concealedBlocks()).append(",\"failedChunks\":").append(cc.failedChunks()).append(",\"skippedChunks\":").append(cc.skippedChunks()).append(",\"noTargetChunks\":").append(metric(cc,"noTargetChunks")).append(",\"budgetLimitedChunks\":").append(metric(cc,"budgetLimitedChunks")).append(",\"hiddenLedgerSize\":").append(cc.cache().size()).append(",\"hiddenLedgerPlayers\":").append(cc.cache().playerCount()).append(",\"worldCave\":").append(q(w.getBlockAt(40,-24,8).getType())).append(",\"worldCaveWall\":").append(q(w.getBlockAt(38,-24,8).getType())).append(",\"worldOre\":").append(q(w.getBlockAt(40,20,8).getType())).append(",\"stoneId\":").append(StateTypes.STONE.createBlockState(cv).getGlobalId()).append(",\"deepslateId\":").append(StateTypes.DEEPSLATE.createBlockState(cv).getGlobalId()).append(",\"oreId\":").append(StateTypes.DIAMOND_ORE.createBlockState(cv).getGlobalId()).append(",\"players\":[");
  boolean first=true;for(Player p:Bukkit.getOnlinePlayers()){
   if(!first)b.append(',');first=false;b.append("{\"name\":").append(q(p.getName())).append(",\"uuid\":").append(q(p.getUniqueId())).append(",\"exactProtocol\":").append(packets.exactProtocol(p.getUniqueId())).append(",\"packetStateToken\":").append(packetStateToken(p.getUniqueId())).append(",\"disconnectEventsForUuid\":").append(observedDisconnects.getOrDefault(p.getUniqueId(),new java.util.concurrent.atomic.AtomicInteger()).get()).append(",\"slot\":").append(p.getInventory().getHeldItemSlot()).append(",\"pitch\":").append(p.getLocation().getPitch()).append(",\"x\":").append(p.getLocation().getX()).append(",\"y\":").append(p.getLocation().getY()).append(",\"z\":").append(p.getLocation().getZ()).append(",\"findings\":[");
   Files.writeString(getDataFolder().toPath().resolve(label+"-"+p.getName()+"-replay.json"),guardian.replay().json(p.getUniqueId()));
   Files.writeString(getDataFolder().toPath().resolve(label+"-"+p.getName()+"-context.txt"),"mode="+p.getGameMode()+" context="+packets.context(p.getUniqueId()));
   boolean ff=true;for(var finding:guardian.engine().current(p.getUniqueId())){if(!ff)b.append(',');ff=false;b.append("{\"check\":").append(q(finding.checkId())).append(",\"hard\":").append(finding.hardInvariant()).append(",\"count\":").append(finding.occurrences()).append(",\"vl\":").append(finding.violationLevel()).append('}');}b.append("]}");
  }
  b.append("]}");getDataFolder().mkdirs();Files.writeString(getDataFolder().toPath().resolve(label+"-ingress.json"),"["+String.join(",",ingressTrace)+"]");Files.writeString(getDataFolder().toPath().resolve(label+".json"),b.toString());getLogger().info("GACREAL "+b);
 }
 @Override public boolean onCommand(CommandSender s,Command c,String l,String[] a){try{
  if(a.length==0)return true;
  switch(a[0]){case "setup"->setup();case "cavesetup"->caveSetup();case "cavenear"->{Player p=Bukkit.getPlayerExact(a[1]);p.teleport(new Location(p.getWorld(),40.5,-25,8.5));}case "report"->report(a[1]);case "nearall"->{for(Player p:Bukkit.getOnlinePlayers())if(p.getName().startsWith("GacReal"))p.teleport(new Location(p.getWorld(),40.5,24,8.5));}case "near"->{Player p=Bukkit.getPlayerExact(a[1]);p.teleport(new Location(p.getWorld(),40.5,24,8.5));}case "far"->{Player p=Bukkit.getPlayerExact(a[1]);p.teleport(new Location(p.getWorld(),8.5,65,8.5));}case "refresh"->{World w=Bukkit.getWorlds().getFirst();w.refreshChunk(2,0);}}
  getLogger().info("GACREAL COMMAND "+a[0]+" DONE");
 }catch(Exception e){getLogger().log(java.util.logging.Level.SEVERE,"GACREAL ERROR",e);}return true;}
}
