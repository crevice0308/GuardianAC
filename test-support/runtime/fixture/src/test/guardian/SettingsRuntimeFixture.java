/* SPDX-License-Identifier: GPL-3.0-only */
package test.guardian;

import dev.guardianac.bukkit.*;
import dev.guardianac.core.*;
import dev.guardianac.packet.PacketAdapter;
import dev.guardianac.xray.*;
import dev.guardianac.storage.StorageConfig;
import dev.guardianac.security.ReplayBuffer;
import org.bukkit.entity.Player;
import org.bukkit.inventory.*;
import org.bukkit.event.inventory.InventoryCloseEvent;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.*;
import org.bukkit.block.Block;
import org.bukkit.block.data.BlockData;
import org.bukkit.event.HandlerList;
import org.bukkit.event.block.BlockBreakEvent;
import java.lang.reflect.*;
import java.util.*;

/** Isolated settings binding checks; simulated TPS and proxy actors are explicitly synthetic. */
final class SettingsRuntimeFixture {
    private final GuardianFixture f;
    private final GuardianPlugin plugin;
    private final boolean originalTuning,originalXray;
    private final double originalTps;
    private boolean finished;
    private SettingsRuntimeFixture(GuardianFixture f)throws Exception {
        this.f=f;plugin=f.guardian;originalTuning=plugin.settings().enabled("automatic-tuning");originalXray=plugin.settings().enabled("xray.enabled");
        originalTps=(double)field(GuardianPlugin.class,"tps").get(plugin);
    }
    @FunctionalInterface private interface Step {void run()throws Exception;}
    static void run(GuardianFixture f)throws Exception {var run=new SettingsRuntimeFixture(f);try{run.start();}catch(Throwable failure){run.fail(failure);}}
    static void seedRestart(GuardianFixture f)throws Exception {new SettingsRuntimeFixture(f).seedRestartState();}
    static void restart(GuardianFixture f)throws Exception {new SettingsRuntimeFixture(f).verifyRestartState();}
    private void pass(String name,boolean value){f.pass("settings_runtime_"+name,value);}
    private static Field field(Class<?> type,String name)throws Exception {Field result=type.getDeclaredField(name);result.setAccessible(true);return result;}
    private void later(int ticks,Step next){Bukkit.getScheduler().runTaskLater(f,()->{if(!finished)try{next.run();}catch(Throwable failure){fail(failure);}},ticks);}
    private void fail(Throwable failure){f.error("settings_runtime_fixture",failure);finish();}
    private void finish(){
        if(finished)return;finished=true;
        try{plugin.settings().set("automatic-tuning",originalTuning);plugin.settings().set("xray.enabled",originalXray);field(GuardianPlugin.class,"tps").setDouble(plugin,originalTps);plugin.applySettings();}
        catch(Throwable failure){f.error("settings_runtime_restore",failure);}
        f.save();f.getLogger().info("GACTEST SETTINGS RUNTIME DONE");
    }
    private int telemetryCapacity(EventTelemetry telemetry)throws Exception {Object sessions=field(EventTelemetry.class,"players").get(telemetry);return field(sessions.getClass(),"capacity").getInt(sessions);}
    private double relaxation()throws Exception{return field(DetectionEngine.class,"relaxation").getDouble(plugin.engine());}
    private void start()throws Exception {
        int expected=plugin.settings().limit("max-players",2000,16,10000),engine=field(DetectionEngine.class,"maxPlayers").getInt(plugin.engine());
        EventTelemetry telemetry=(EventTelemetry)field(GuardianPlugin.class,"telemetry").get(plugin);
        pass("startup_nondefault_capacity_from_pinned_config",expected==37);
        pass("engine_uses_validated_startup_capacity",engine==expected);
        pass("telemetry_uses_same_validated_startup_capacity",telemetryCapacity(telemetry)==engine);
        EventTelemetry explicit=new EventTelemetry(f,s->{},()->0L,()->20d,37),legacy=new EventTelemetry(f,s->{},()->0L,()->20d);
        pass("explicit_telemetry_capacity_overrides_plugin_cache",telemetryCapacity(explicit)==37&&f.getConfig().getInt("limits.max-players",2000)==2000);
        pass("legacy_four_argument_telemetry_ctor_retained",telemetryCapacity(legacy)==2000);explicit.close();legacy.close();
        // This is a synthetic input to the configuration binding, not a claim of measured server lag.
        field(GuardianPlugin.class,"tps").setDouble(plugin,18d);plugin.settings().set("automatic-tuning",true);plugin.applySettings();
        pass("tuning_on_low_tps_applies_immediately",relaxation()==2d);
        plugin.settings().set("automatic-tuning",false);plugin.applySettings();pass("tuning_off_resets_immediately",relaxation()==1d);
        plugin.engine().relaxation(2);plugin.applySettings();pass("repeated_off_apply_clears_stale_multiplier",relaxation()==1d);
        plugin.engine().relaxation(2);awaitPeriodicReset(130);
    }
    private void awaitPeriodicReset(int remaining)throws Exception {
        if(relaxation()==1d){pass("disabled_periodic_pulse_resets_multiplier",true);xrayEvents();return;}
        if(remaining<=0){pass("disabled_periodic_pulse_resets_multiplier",false);xrayEvents();return;}
        later(2,()->awaitPeriodicReset(remaining-2));
    }
    private MiningStatistics mining(XrayService service)throws Exception{return (MiningStatistics)field(XrayService.class,"mining").get(service);}
    @SuppressWarnings("unchecked") private Map<UUID,?> candidates(XrayService service)throws Exception{return (Map<UUID,?>)field(XrayService.class,"hiddenCandidates").get(service);}
    private void emit(Block block,MiningPlayer actor){Bukkit.getPluginManager().callEvent(new BlockBreakEvent(block,actor.proxy));}
    private void xrayEvents()throws Exception {
        World world=Bukkit.getWorlds().getFirst();Location spawn=world.getSpawnLocation();int chunkX=spawn.getBlockX()>>4,chunkZ=spawn.getBlockZ()>>4;
        boolean loadedBefore=world.isChunkLoaded(chunkX,chunkZ);world.getChunkAt(chunkX,chunkZ); // One bounded, explicitly isolated fixture chunk.
        int x=(chunkX<<4)+8,z=(chunkZ<<4)+8,y=Math.min(world.getMaxHeight()-3,Math.max(world.getMinHeight()+4,spawn.getBlockY()+5));
        List<Sample> sink=new ArrayList<>();PacketAdapter isolatedAdapter=new PacketAdapter(f,s->{});XrayService observed=new XrayService(f,isolatedAdapter,sink::add);
        Map<Block,BlockData> restore=new LinkedHashMap<>();MiningPlayer actor=new MiningPlayer(world,new Location(world,x+.5,y,z+.5));
        try {
            for(int dx=-1;dx<=1;dx++)for(int dy=-1;dy<=1;dy++)for(int dz=-1;dz<=1;dz++){Block b=world.getBlockAt(x+dx,y+dy,z+dz);restore.put(b,b.getBlockData().clone());b.setType(Material.STONE,false);}
            Block ore=world.getBlockAt(x,y,z),adjacent=world.getBlockAt(x-1,y,z);ore.setType(Material.DIAMOND_ORE,false);
            plugin.settings().set("xray.enabled",true);plugin.applySettings();observed.configure(plugin.xray().settings());Bukkit.getPluginManager().registerEvents(observed,f);
            emit(adjacent,actor);
            pass("xray_on_real_break_emits_to_isolated_sink",sink.size()==1&&mining(observed).historySize(actor.id)==1);
            Object entries=candidates(observed).get(actor.id);pass("xray_on_enclosed_ore_candidate_recorded",entries instanceof Map<?,?> m&&!m.isEmpty());
            emit(ore,actor);pass("xray_on_mining_history_grows",sink.size()==2&&sink.getLast().v("mined-blocks")==2&&mining(plugin.xray()).historySize(actor.id)==2);
            plugin.settings().set("xray.enabled",false);plugin.applySettings();observed.configure(plugin.xray().settings());
            pass("xray_master_off_reaches_production_service",!plugin.xray().settings().observesMining()&&!observed.settings().observesMining());
            pass("xray_off_clears_mining_and_candidate_history",mining(observed).historySize(actor.id)==0&&candidates(observed).isEmpty()&&mining(plugin.xray()).historySize(actor.id)==0&&!candidates(plugin.xray()).containsKey(actor.id));
            int before=sink.size();emit(adjacent,actor);emit(ore,actor);
            pass("xray_off_real_break_has_no_sink_growth",sink.size()==before&&mining(observed).historySize(actor.id)==0&&mining(plugin.xray()).historySize(actor.id)==0);
            plugin.settings().set("xray.enabled",true);plugin.applySettings();observed.configure(plugin.xray().settings());emit(ore,actor);
            pass("xray_reenable_starts_fresh_history",sink.size()==before+1&&sink.getLast().v("mined-blocks")==1&&mining(observed).historySize(actor.id)==1&&mining(plugin.xray()).historySize(actor.id)==1);
            observed.configure(observed.settings().withMiningAnalysis(false));before=sink.size();emit(adjacent,actor);
            pass("mining_analysis_off_also_stops_sink",sink.size()==before&&mining(observed).historySize(actor.id)==0&&candidates(observed).isEmpty());
        }finally {
            HandlerList.unregisterAll(observed);observed.close();isolatedAdapter.close();
            for(var entry:restore.entrySet())entry.getKey().setBlockData(entry.getValue(),false);
            Method forget=XrayService.class.getDeclaredMethod("forget",UUID.class);forget.setAccessible(true);forget.invoke(plugin.xray(),actor.id);
            plugin.engine().reset(actor.id);plugin.replay().forget(actor.id);
            if(!loadedBefore)world.unloadChunk(chunkX,chunkZ,false);
        }
        AdvancedSettingsRuntimeFixture.run(f,this::finish);
    }
    private int engineCapacity()throws Exception{return field(DetectionEngine.class,"maxPlayers").getInt(plugin.engine());}
    private int liveTelemetryCapacity()throws Exception{return telemetryCapacity((EventTelemetry)field(GuardianPlugin.class,"telemetry").get(plugin));}
    private StorageConfig storageConfig()throws Exception{return (StorageConfig)field(plugin.storage().getClass(),"config").get(plugin.storage());}
    private int replayLimit(String name)throws Exception{return field(ReplayBuffer.class,name).getInt(plugin.replay());}
    private static String itemText(ItemStack item){var meta=item.getItemMeta();return ChatColor.stripColor(meta.getDisplayName()+meta.getLore());}
    private Inventory advancedMenu(GuardianFixture.FakePlayer actor)throws Exception {
        GuiManager gui=(GuiManager)field(GuardianPlugin.class,"gui").get(plugin);Method method=GuiManager.class.getDeclaredMethod("advanced",Player.class,int.class);method.setAccessible(true);method.invoke(gui,actor.proxy,0);return actor.top;
    }
    private void closeMenu(GuardianFixture.FakePlayer actor){if(actor.top!=null){Bukkit.getPluginManager().callEvent(new InventoryCloseEvent(actor.view()));actor.top=null;}}
    private void seedRestartState()throws Exception {
        plugin.settings().set("limits.max-players",41);plugin.settings().set("limits.evidence-mib",257);plugin.settings().set("limits.evidence-days",91);
        plugin.settings().set("limits.replay-total-mib",33);plugin.settings().set("limits.replay-per-player-kib",65);plugin.applySettings();plugin.settings().reload();plugin.applySettings();
        var disk=YamlConfiguration.loadConfiguration(plugin.getDataFolder().toPath().resolve("config.yml").toFile());
        pass("restart_seed_five_allocations_persisted",disk.getInt("limits.max-players")==41&&disk.getInt("limits.evidence-mib")==257&&disk.getInt("limits.evidence-days")==91&&disk.getInt("limits.replay-total-mib")==33&&disk.getInt("limits.replay-per-player-kib")==65);
        pass("reload_does_not_resize_live_tracking",engineCapacity()==37&&liveTelemetryCapacity()==37);
        pass("reload_does_not_resize_live_storage",storageConfig().maxEvidenceBytes()==256L*1024*1024&&storageConfig().evidenceRetention().toDays()==90);
        pass("reload_does_not_resize_live_replay",replayLimit("globalLimit")==32*1024*1024&&replayLimit("playerLimit")==64*1024);
        GuardianFixture.FakePlayer actor=new GuardianFixture.FakePlayer(Set.of("guardianac.view","guardianac.admin"));Inventory menu=advancedMenu(actor);
        pass("restart_seed_gui_pending_and_active_37",itemText(menu.getItem(9)).contains("41")&&(itemText(menu.getItem(9)).contains("稼働中の値: 37")||itemText(menu.getItem(9)).contains("Active this session: 37"))&&(itemText(menu.getItem(9)).contains("再起動待ち")||itemText(menu.getItem(9)).contains("waiting for restart")));closeMenu(actor);
        f.save();f.getLogger().info("GACTEST SETTINGS RESTART SEED DONE");
    }
    private void verifyRestartState()throws Exception {
        pass("restart_engine_and_telemetry_capacity_41",engineCapacity()==41&&liveTelemetryCapacity()==41&&plugin.settings().limit("max-players",2000,16,10000)==41);
        pass("restart_storage_allocations_applied",storageConfig().maxEvidenceBytes()==257L*1024*1024&&storageConfig().evidenceRetention().toDays()==91);
        pass("restart_replay_allocations_applied",replayLimit("globalLimit")==33*1024*1024&&replayLimit("playerLimit")==65*1024);
        GuardianFixture.FakePlayer actor=new GuardianFixture.FakePlayer(Set.of("guardianac.view","guardianac.admin"));Inventory menu=advancedMenu(actor);
        int[] slots={9,11,12,13,14},expected={41,257,91,33,65};boolean active=true;
        for(int i=0;i<slots.length;i++){String text=itemText(menu.getItem(slots[i]));active&=(text.contains("稼働中の値: "+expected[i])||text.contains("Active this session: "+expected[i]))&&!text.contains("再起動待ち")&&!text.contains("waiting for restart");}
        pass("restart_gui_five_active_values_updated",menu.getSize()==54&&active);closeMenu(actor);
        f.save();f.getLogger().info("GACTEST SETTINGS RESTART VERIFIED DONE");
    }
    private final class MiningPlayer extends GuardianFixture.FakePlayer {
        final World world;final Location location;
        MiningPlayer(World world,Location location){super(Set.of());this.world=world;this.location=location;}
        @Override public Object invoke(Object proxy,Method method,Object[] args){return switch(method.getName()){
            case "getWorld"->world;case "getLocation"->location.clone();case "getGameMode"->GameMode.SURVIVAL;
            default->super.invoke(proxy,method,args);
        };}
    }
}
