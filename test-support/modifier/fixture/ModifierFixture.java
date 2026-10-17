package test.guardianmod;
import org.bukkit.*;
import test.guardianfixture.safety.FixturePorts;
import org.bukkit.block.data.type.Ladder;
import org.bukkit.block.BlockFace;
import org.bukkit.command.*;
import org.bukkit.entity.*;
import org.bukkit.event.*;
import org.bukkit.event.player.*;
import org.bukkit.event.inventory.InventoryCloseEvent;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.potion.*;
import org.bukkit.util.Vector;
import org.bukkit.scheduler.BukkitRunnable;
import dev.guardianac.bukkit.GuardianPlugin;
import dev.guardianac.bukkit.EventTelemetry;
import dev.guardianac.packet.PacketAdapter;
import dev.guardianac.security.ReplayBuffer;
import java.lang.reflect.Field;
import java.nio.file.*;
import java.util.*;
import java.lang.management.ManagementFactory;
public final class ModifierFixture extends JavaPlugin implements Listener {
 GuardianPlugin g; PacketAdapter packets; List<Entity> spawned=new ArrayList<>(); double minLagTps=20; boolean lagActive;
 public void onEnable(){try{FixturePorts.requireEndpoint(getServer().getIp(),getServer().getPort(),getServer().getOnlineMode());g=(GuardianPlugin)Bukkit.getPluginManager().getPlugin("GuardianAC");packets=(PacketAdapter)field(g,"packets");getCommand("gacmod").setExecutor(this);Bukkit.getPluginManager().registerEvents(this,this);getDataFolder().mkdirs();}catch(Exception e){throw new RuntimeException(e);}}
 static Object field(Object o,String n)throws Exception{Field f=o.getClass().getDeclaredField(n);f.setAccessible(true);return f.get(o);}
 static int size(Object o,String n)throws Exception{return ((Map<?,?>)field(o,n)).size();}
 static String q(Object x){return ReplayBuffer.quote(String.valueOf(x));}
 World w(){return Bukkit.getWorlds().getFirst();}
 void flat(World w,int x0,int x1,int z0,int z1){for(int x=x0;x<=x1;x++)for(int z=z0;z<=z1;z++){w.getBlockAt(x,64,z).setType(Material.STONE,false);for(int y=65;y<=82;y++)w.getBlockAt(x,y,z).setType(Material.AIR,false);}}
 void setup(){flat(w(),0,70,0,36);for(int x=0;x<=60;x++)for(int z=8;z<=12;z++)w().getBlockAt(x,65,z).setType(Material.WATER,false);
  for(int y=65;y<=74;y++){w().getBlockAt(8,y,17).setType(Material.STONE,false);Ladder ladder=(Ladder)Material.LADDER.createBlockData();ladder.setFacing(BlockFace.NORTH);w().getBlockAt(8,y,16).setBlockData(ladder,false);}
  Material[] materials={Material.STONE_SLAB,Material.ICE,Material.SLIME_BLOCK,Material.HONEY_BLOCK};for(int i=0;i<materials.length;i++)for(int x=0;x<=60;x++)for(int z=20+i*4;z<=22+i*4;z++)w().getBlockAt(x,64,z).setType(materials[i],false);
  if(Bukkit.getWorlds().size()>1)flat(Bukkit.getWorlds().get(1),0,10,0,6);w().setSpawnLocation(8,65,2);
 }
 @EventHandler public void join(PlayerJoinEvent e){if(e.getPlayer().getName().startsWith("GacMod"))Bukkit.getScheduler().runTaskLater(this,()->condition(e.getPlayer(),"stone"),2);}
 void lifecycle(String event,Player p){if(!p.getName().startsWith("GacMod"))return;try{EventTelemetry telemetry=(EventTelemetry)field(g,"telemetry");String json="{\"event\":"+q(event)+",\"timeMillis\":"+System.currentTimeMillis()+",\"name\":"+q(p.getName())+",\"online\":"+p.isOnline()+",\"trackedPlayers\":"+telemetry.trackedPlayers()+"}";Files.writeString(getDataFolder().toPath().resolve("lifecycle.jsonl"),json+"\n",StandardOpenOption.CREATE,StandardOpenOption.APPEND);}catch(Exception e){throw new RuntimeException(e);}}
 @EventHandler(priority=EventPriority.MONITOR) public void recordQuit(PlayerQuitEvent e){lifecycle("QUIT",e.getPlayer());}
 @EventHandler(priority=EventPriority.MONITOR) public void recordClose(InventoryCloseEvent e){if(e.getPlayer() instanceof Player p)lifecycle("INVENTORY_CLOSE",p);}
 void condition(Player p,String mode){if(!p.isOnline())return;p.leaveVehicle();for(Entity e:spawned)e.remove();spawned.clear();p.setGameMode(GameMode.SURVIVAL);p.setInvulnerable(true);p.setFoodLevel(20);p.setAllowFlight(false);p.setGliding(false);p.setSneaking(false);p.setSprinting(false);p.setGravity(true);p.setVelocity(new Vector());for(PotionEffect e:p.getActivePotionEffects())p.removePotionEffect(e.getType());
  World world=w();double x=8.5,y=65,z=2.5;
  switch(mode){case "water"->z=10.5;case "ladder"->{x=8.5;z=16.5;}case "slab"->{z=21.5;y=64.5;}case "ice"->z=25.5;case "slime"->z=29.5;case "honey"->{z=33.5;y=64.9375;}case "slowfall"->y=76;case "world"->{if(Bukkit.getWorlds().size()<2)throw new IllegalStateException("second world unavailable");world=Bukkit.getWorlds().get(1);}}
  p.teleport(new Location(world,x,y,z,-90,0));
  PotionEffectType effect=switch(mode){case "speed"->PotionEffectType.SPEED;case "jump"->PotionEffectType.JUMP_BOOST;case "slowfall"->PotionEffectType.SLOW_FALLING;case "levitation"->PotionEffectType.LEVITATION;default->null;};if(effect!=null)p.addPotionEffect(new PotionEffect(effect,2000,0,false,false));
  if(mode.equals("vehicle")){Entity vehicle=world.spawnEntity(new Location(world,x,65,z),EntityType.MINECART);spawned.add(vehicle);vehicle.addPassenger(p);}
 }
 void report(String label)throws Exception{
  var cache=g.xray().concealment().cache();Runtime rt=Runtime.getRuntime();StringBuilder b=new StringBuilder("{\"label\":").append(q(label)).append(",\"timeMillis\":").append(System.currentTimeMillis()).append(",\"tps\":").append(field(g,"tps")).append(",\"minLagTps\":").append(minLagTps).append(",\"lagActive\":").append(lagActive).append(",\"heapUsed\":").append(rt.totalMemory()-rt.freeMemory()).append(",\"heapCommitted\":").append(rt.totalMemory()).append(",\"heapMax\":").append(rt.maxMemory()).append(",\"enginePlayers\":").append(size(g.engine(),"players")).append(",\"telemetryPlayers\":").append(((EventTelemetry)field(g,"telemetry")).trackedPlayers()).append(",\"packetPlayers\":").append(size(packets,"states")).append(",\"packetQueue\":").append(packets.queuedSamples()).append(",\"replayPlayers\":").append(g.replay().players()).append(",\"replayBytes\":").append(g.replay().bytes()).append(",\"hiddenPlayers\":").append(cache.playerCount()).append(",\"hiddenPositions\":").append(cache.size()).append(",\"xrayViewers\":").append(size(g.xray().concealment(),"viewers")).append(",\"cancelled\":").append(packets.cancelledPackets()).append(",\"malformed\":").append(packets.malformedPackets()).append(",\"dropped\":").append(packets.droppedSamples()).append(",\"contextCaptureFailures\":").append(packets.contextCaptureFailures()).append(",\"findingsCount\":").append(field(g,"findingsCount")).append(",\"banCount\":").append(field(g,"banCount")).append(",\"gcCount\":").append(ManagementFactory.getGarbageCollectorMXBeans().stream().mapToLong(x->Math.max(0,x.getCollectionCount())).sum()).append(",\"players\":[");boolean first=true;
  for(Player p:Bukkit.getOnlinePlayers()){if(!first)b.append(',');first=false;var pc=packets.context(p.getUniqueId());b.append("{\"name\":").append(q(p.getName())).append(",\"uuid\":").append(q(p.getUniqueId())).append(",\"entityId\":").append(p.getEntityId()).append(",\"world\":").append(q(p.getWorld().getName())).append(",\"x\":").append(p.getLocation().getX()).append(",\"y\":").append(p.getLocation().getY()).append(",\"z\":").append(p.getLocation().getZ()).append(",\"ping\":").append(p.getPing()).append(",\"sprinting\":").append(p.isSprinting()).append(",\"sneaking\":").append(p.isSneaking()).append(",\"inWater\":").append(p.isInWater()).append(",\"climbing\":").append(p.isClimbing()).append(",\"vehicle\":").append(p.isInsideVehicle()).append(",\"gliding\":").append(p.isGliding()).append(",\"flags\":[");if(pc.isPresent())b.append(String.join(",",pc.get().flags().stream().sorted().map(ModifierFixture::q).toList()));b.append("],\"findings\":[");boolean ff=true;for(var f:g.engine().current(p.getUniqueId())){if(!ff)b.append(',');ff=false;b.append("{\"check\":").append(q(f.checkId())).append(",\"vl\":").append(f.violationLevel()).append(",\"count\":").append(f.occurrences()).append(",\"hard\":").append(f.hardInvariant()).append('}');}b.append("]}");if(!label.startsWith("soak-"))Files.writeString(getDataFolder().toPath().resolve(label+"-"+p.getName()+"-replay.json"),g.replay().json(p.getUniqueId()));}
  b.append("]}");Files.writeString(getDataFolder().toPath().resolve(label+".json"),b);Files.writeString(getDataFolder().toPath().resolve("metrics.jsonl"),b+"\n",StandardOpenOption.CREATE,StandardOpenOption.APPEND);
 }
 @Override public boolean onCommand(CommandSender sender,Command cmd,String label,String[] a){try{if(a.length==0)return true;switch(a[0]){case "setup"->setup();case "condition"->condition(Objects.requireNonNull(Bukkit.getPlayerExact(a[1])),a[2]);case "report"->report(a[1]);case "velocity"->Objects.requireNonNull(Bukkit.getPlayerExact(a[1])).setVelocity(new Vector(.3,.42,0));case "lag"->{int count=Integer.parseInt(a[1]),millis=Integer.parseInt(a[2]);if(count<1||count>80||millis<1||millis>150||lagActive)throw new IllegalArgumentException("bounded lag only");lagActive=true;minLagTps=20;new BukkitRunnable(){int left=count;public void run(){try{minLagTps=Math.min(minLagTps,(Double)field(g,"tps"));if(left--<=0){lagActive=false;cancel();return;}Thread.sleep(millis);}catch(Exception e){lagActive=false;cancel();throw new RuntimeException(e);}}}.runTaskTimer(this,1,1);}}
  getLogger().info("GACMOD COMMAND "+a[0]+" DONE");}catch(Exception e){getLogger().log(java.util.logging.Level.SEVERE,"GACMOD ERROR",e);}return true;}
}
