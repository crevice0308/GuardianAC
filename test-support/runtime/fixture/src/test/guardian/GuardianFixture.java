package test.guardian;
import dev.guardianac.bukkit.*;
import dev.guardianac.storage.*;
import dev.guardianac.packet.*;
import dev.guardianac.xray.*;
import org.bukkit.*;
import org.bukkit.ban.*;
import org.bukkit.command.*;
import org.bukkit.entity.*;
import org.bukkit.event.*;
import org.bukkit.event.player.*;
import org.bukkit.event.inventory.*;
import org.bukkit.inventory.*;
import org.bukkit.plugin.java.JavaPlugin;
import java.lang.reflect.*;
import java.net.InetAddress;
import java.nio.file.*;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;

/** Isolated synthetic integration fixtures. Never install on a production server. */
public class GuardianFixture extends JavaPlugin implements CommandExecutor {
 GuardianPlugin guardian; final List<String> results=new ArrayList<>();
 static final UUID OWN=UUID.fromString("00000000-0000-4000-8000-000000000001"), FOREIGN=UUID.fromString("00000000-0000-4000-8000-000000000002"), CLEAN=UUID.fromString("00000000-0000-4000-8000-000000000003");
 public void onEnable(){guardian=(GuardianPlugin)Bukkit.getPluginManager().getPlugin("GuardianAC");getCommand("gactest").setExecutor(this);}
 void pass(String key,boolean success){results.add("{\"test\":\""+key+"\",\"passed\":"+success+"}");getLogger().info("GACTEST "+key+" "+(success?"PASS":"FAIL"));}
 void error(String key,Throwable e){pass(key,false);getLogger().warning(key+" "+e);e.printStackTrace();}
 void save(){try{getDataFolder().mkdirs();Files.writeString(getDataFolder().toPath().resolve("results.json"),"[\n"+String.join(",\n",results)+"\n]\n");}catch(Exception e){error("report_write",e);}}
 public boolean onCommand(CommandSender s,Command c,String l,String[] a){if(a.length==0)return true;try{switch(a[0]){case "core"->core();case "seed"->seed();case "restart"->restart();case "gui"->gui();case "confirmclose"->confirmClose();case "guiipunban"->GuiIpUnbanFixture.run(this);case "settingsruntime"->SettingsRuntimeFixture.run(this);case "settingsseed"->SettingsRuntimeFixture.seedRestart(this);case "settingsrestart"->SettingsRuntimeFixture.restart(this);case "permanentip"->PermanentIpFixture.run(this);case "permanentipoffline"->PermanentIpFixture.offline(this);case "permanentiprestart"->PermanentIpFixture.restart(this);case "nativelifecycle"->NativeLifecycleFixture.run(this);case "nativeseed"->NativeLifecycleFixture.seedRestart(this);case "nativerestart"->NativeLifecycleFixture.restart(this);case "packet"->PacketFixture.run(this,guardian);case "telemetry"->TelemetryLifecycleFixture.run(this);case "disable"->disable();}}catch(Throwable e){error(a[0],e);}save();getLogger().info("GACTEST COMMAND "+a[0]+" DONE");return true;}
 void core() throws Exception {
  getLogger().info("GACTEST bukkit_api="+Bukkit.getBukkitVersion());pass("runtime_supported",!guardian.status().contains("UNSUPPORTED"));
  pass("plugin_enabled",guardian.isEnabled());pass("rules_at_least_61",guardian.engine().checks().size()>=61);
  Set<String> listeners=new HashSet<>();for(var rl:HandlerList.getRegisteredListeners(guardian))listeners.add(rl.getListener().getClass().getSimpleName());
  pass("all_bukkit_listener_families",listeners.containsAll(Set.of("GuardianPlugin","EventTelemetry","GuiManager","XrayService")));
  pass("native_xray_config_readable",!guardian.xray().nativeModes().isEmpty()&&!guardian.xray().nativeModes().containsValue(NativeAntiXray.Mode.UNKNOWN));
  getLogger().info("GACTEST native_modes "+guardian.xray().nativeModes());
  FakeSender denied=new FakeSender(Set.of()),view=new FakeSender(Set.of("guardianac.view"));
  guardian.onCommand(denied.proxy,guardian.getCommand("guardian"),"gac",new String[]{"status"});pass("command_no_permission_denied",denied.messages.toString().contains("権限")||denied.messages.toString().contains("denied"));
  guardian.onCommand(view.proxy,guardian.getCommand("guardian"),"gac",new String[]{"reload"});pass("command_view_cannot_reload",view.messages.toString().contains("権限")||view.messages.toString().contains("denied"));
  view.messages.clear();guardian.onCommand(view.proxy,guardian.getCommand("guardian"),"gac",new String[]{"unban","GAC-11111111"});pass("command_view_cannot_unban",view.messages.toString().contains("権限")||view.messages.toString().contains("denied"));
  FakeSender unbanner=new FakeSender(Set.of("guardianac.view","guardianac.unban"));guardian.onCommand(unbanner.proxy,guardian.getCommand("guardian"),"gac",new String[]{"unbanip","deadbeef"});pass("command_unbanip_rejects_hex_hostname",(unbanner.messages.toString().contains("Invalid numeric IP address")||unbanner.messages.toString().contains("数値形式のIPアドレスが無効")));
  pass("unauthorized_tab_empty",guardian.onTabComplete(denied.proxy,guardian.getCommand("guardian"),"gac",new String[]{""}).isEmpty());
  pass("operator_bypass_default_false",Bukkit.getPluginManager().getPermission("guardianac.bypass").getDefault()==org.bukkit.permissions.PermissionDefault.FALSE);
 }
 BanRecord make(UUID id,String name,String ip,String caseId) throws Exception {
  var found=guardian.storage().findCase(caseId).get(3,TimeUnit.SECONDS);if(found.isPresent())return found.get();
  var e=guardian.storage().saveEvidence(new EvidenceInput(id,caseId,Instant.now(),"{\"fixture\":true,\"source\":\"isolated runtime\"}")).get(3,TimeUnit.SECONDS);
  return guardian.storage().createBan(new BanRequest(id,name,ip,BanType.DUAL,"Unfair Advantage",Instant.now().plusSeconds(3600),999,caseId,e.id(),"Isolated runtime fixture")).get(3,TimeUnit.SECONDS);
 }
 static AsyncPlayerPreLoginEvent preLogin(String name,InetAddress address,UUID uuid)throws Exception{return AsyncPlayerPreLoginEvent.class.getConstructor(String.class,InetAddress.class,UUID.class).newInstance(name,address,uuid);}
 void seed()throws Exception{
  BanRecord own=make(OWN,"GACOwnedFixture","127.0.0.11","GAC-11111111"),other=make(FOREIGN,"GACOtherFixture","127.0.0.12","GAC-22222222");
  Method sync=GuardianPlugin.class.getDeclaredMethod("syncNative",BanRecord.class,String.class);sync.setAccessible(true);sync.invoke(guardian,own,"127.0.0.11");sync.invoke(guardian,other,"127.0.0.12");
  ProfileBanList profiles=Bukkit.getBanList(BanList.Type.PROFILE);IpBanList ips=Bukkit.getBanList(BanList.Type.IP);
  var ownProfile=Bukkit.createPlayerProfile(OWN,"GACOwnedFixture");var foreignProfile=Bukkit.createPlayerProfile(FOREIGN,"GACOtherFixture");
  pass("native_uuid_marker",profiles.getBanEntry(ownProfile).getSource().equals(own.ownershipMarker()));
  pass("native_ip_marker",ips.getBanEntry(InetAddress.getByName("127.0.0.11")).getSource().equals(own.ownershipMarker()));
  profiles.addBan(foreignProfile,"Foreign administrator fixture",(java.util.Date)null,"OtherPlugin");ips.addBan(InetAddress.getByName("127.0.0.12"),"Foreign fixture",(java.util.Date)null,"OtherPlugin");
  sync.invoke(guardian,other,"127.0.0.12");pass("sync_preserves_foreign_uuid",profiles.getBanEntry(foreignProfile).getSource().equals("OtherPlugin"));pass("sync_preserves_foreign_ip",ips.getBanEntry(InetAddress.getByName("127.0.0.12")).getSource().equals("OtherPlugin"));
  Method remove=GuardianPlugin.class.getDeclaredMethod("removeOwnedNative",BanRecord.class,String.class,String.class);remove.setAccessible(true);remove.invoke(guardian,other,"127.0.0.12","ALL");pass("unban_preserves_foreign_uuid",profiles.isBanned(foreignProfile));pass("unban_preserves_foreign_ip",ips.isBanned(InetAddress.getByName("127.0.0.12")));
  pass("evidence_saved",guardian.storage().evidence(own.evidenceId()).get(3,TimeUnit.SECONDS).isPresent());
  Path backup=guardian.storage().backup().get(3,TimeUnit.SECONDS);pass("consistent_backup_written",Files.size(backup)>0);
  Bukkit.getScheduler().runTaskAsynchronously(this,()->{try{
   var blocked=preLogin("GACOwnedFixture",InetAddress.getLoopbackAddress(),OWN);guardian.login(blocked);pass("login_denies_committed_uuid_ban",blocked.getLoginResult()==AsyncPlayerPreLoginEvent.Result.KICK_BANNED);
   var clean=preLogin("GACCleanFixture",InetAddress.getByName("127.0.0.30"),CLEAN);guardian.login(clean);pass("login_allows_clean_fixture",clean.getLoginResult()==AsyncPlayerPreLoginEvent.Result.ALLOWED);
   var otherDenied=preLogin("GACCleanFixture",InetAddress.getByName("127.0.0.31"),CLEAN);otherDenied.disallow(AsyncPlayerPreLoginEvent.Result.KICK_WHITELIST,"another plugin");guardian.login(otherDenied);pass("login_preserves_other_denial",otherDenied.getLoginResult()==AsyncPlayerPreLoginEvent.Result.KICK_WHITELIST);
  }catch(Exception e){error("login_async",e);}save();getLogger().info("GACTEST ASYNC DONE");});
 }
 void restart()throws Exception{
  var own=guardian.storage().findCase("GAC-11111111").get(3,TimeUnit.SECONDS).orElseThrow();pass("restart_db_record_persisted",own.playerId().equals(OWN));pass("restart_evidence_persisted",guardian.storage().evidence(own.evidenceId()).get(3,TimeUnit.SECONDS).isPresent());
  ProfileBanList profiles=Bukkit.getBanList(BanList.Type.PROFILE);IpBanList ips=Bukkit.getBanList(BanList.Type.IP);
  pass("restart_foreign_native_preserved",profiles.getBanEntry(Bukkit.createPlayerProfile(FOREIGN,"GACOtherFixture")).getSource().equals("OtherPlugin"));
  pass("restart_foreign_ip_preserved",ips.getBanEntry(InetAddress.getByName("127.0.0.12")).getSource().equals("OtherPlugin"));
 }
 void gui() throws Exception{
  Field gf=GuardianPlugin.class.getDeclaredField("gui");gf.setAccessible(true);GuiManager gm=(GuiManager)gf.get(guardian);
  FakePlayer view=new FakePlayer(Set.of("guardianac.view"));gm.main(view.proxy);pass("gui_54_slots",view.top!=null&&view.top.getSize()==54);pass("gui_custom_holder",view.top.getHolder()!=null&&view.top.getHolder().getClass().getName().contains("GuiManager"));
  for(ClickType click:List.of(ClickType.SHIFT_LEFT,ClickType.NUMBER_KEY,ClickType.DOUBLE_CLICK,ClickType.DROP,ClickType.SWAP_OFFHAND,ClickType.LEFT)){
   InventoryClickEvent ev=new InventoryClickEvent(view.view(),InventoryType.SlotType.CONTAINER,40,click,InventoryAction.PICKUP_ALL,click==ClickType.NUMBER_KEY?1:-1);Bukkit.getPluginManager().callEvent(ev);pass("gui_cancel_"+click,ev.isCancelled());
  }
  var drag=new InventoryDragEvent(view.view(),new ItemStack(Material.STONE),new ItemStack(Material.STONE),false,Map.of(4,new ItemStack(Material.STONE)));Bukkit.getPluginManager().callEvent(drag);pass("gui_drag_cancelled",drag.isCancelled());
  Bukkit.getScheduler().runTaskLater(this,()->{try{
   boolean before=guardian.settings().enabled("auto-ban");InventoryClickEvent ev=new InventoryClickEvent(view.view(),InventoryType.SlotType.CONTAINER,14,ClickType.LEFT,InventoryAction.PICKUP_ALL);gm.click(ev);
   Bukkit.getScheduler().runTaskLater(this,()->{pass("gui_view_cannot_mutate",guardian.settings().enabled("auto-ban")==before);adminGui(gm);},2);
  }catch(Exception e){error("gui_delayed",e);}},3);
 }
 void confirmClose()throws Exception{
  FakePlayer actor=new FakePlayer(Set.of("guardianac.view","guardianac.unban"));
  BanRecord own=guardian.storage().findCase("GAC-11111111").get(3,TimeUnit.SECONDS).orElseThrow();
  guardian.requestUnban(actor.proxy,own,"ALL");Object menu=actor.top.getHolder();Field tf=menu.getClass().getDeclaredField("confirmationToken");tf.setAccessible(true);String token=(String)tf.get(menu);
  pass("gui_confirmation_token_created",token!=null&&!token.isBlank());
  Bukkit.getPluginManager().callEvent(new InventoryCloseEvent(actor.view()));guardian.confirm(actor.proxy,token,"must remain invalid after close");
  pass("gui_closed_confirmation_invalidated",actor.messages.toString().contains("無効")||actor.messages.toString().contains("invalid"));
  pass("gui_closed_confirmation_keeps_ban",guardian.storage().findCase("GAC-11111111").get(3,TimeUnit.SECONDS).orElseThrow().activeAt(Instant.now()));
 }
 void adminGui(GuiManager gm){
  try{
   FakePlayer admin=new FakePlayer(Set.of("guardianac.view","guardianac.admin"));
   Method settings=GuiManager.class.getDeclaredMethod("settings",Player.class);settings.setAccessible(true);settings.invoke(gm,admin.proxy);
   boolean before=guardian.settings().enabled("auto-ban");
   gm.click(new InventoryClickEvent(admin.view(),InventoryType.SlotType.CONTAINER,14,ClickType.LEFT,InventoryAction.PICKUP_ALL));
   Bukkit.getScheduler().runTaskLater(this,()->{try{
    pass("gui_admin_can_change_setting",guardian.settings().enabled("auto-ban")!=before);
    pass("gui_admin_setting_persisted",Files.readString(guardian.getDataFolder().toPath().resolve("config.yml")).contains("auto-ban: "+(!before)));
    guardian.settings().set("auto-ban",before);guardian.applySettings();settings.invoke(gm,admin.proxy);
    gm.click(new InventoryClickEvent(admin.view(),InventoryType.SlotType.CONTAINER,14,ClickType.LEFT,InventoryAction.PICKUP_ALL));
    admin.perms.remove("guardianac.admin");
    Bukkit.getScheduler().runTaskLater(this,()->{pass("gui_delayed_admin_permission_rechecked",guardian.settings().enabled("auto-ban")==before);save();getLogger().info("GACTEST GUI ASYNC DONE");},2);
   }catch(Exception e){error("gui_admin_delayed",e);save();getLogger().info("GACTEST GUI ASYNC DONE");}},2);
  }catch(Exception e){error("gui_admin",e);save();getLogger().info("GACTEST GUI ASYNC DONE");}
 }
 void disable(){Bukkit.getPluginManager().disablePlugin(guardian);pass("disabled_state",!guardian.isEnabled());pass("disable_no_listeners",HandlerList.getRegisteredListeners(guardian).isEmpty());pass("disable_no_scheduled_tasks",Bukkit.getScheduler().getPendingTasks().stream().noneMatch(t->t.getOwner()==guardian));}
 static Object defaultValue(Class<?> c){if(!c.isPrimitive())return null;if(c==boolean.class)return false;if(c==int.class)return 0;if(c==long.class)return 0L;if(c==double.class)return 0d;if(c==float.class)return 0f;if(c==short.class)return (short)0;if(c==byte.class)return (byte)0;if(c==char.class)return '\0';return null;}
 static class FakeSender implements InvocationHandler{Set<String> perms;List<String> messages=new ArrayList<>();CommandSender proxy;FakeSender(Set<String> p){perms=new HashSet<>(p);proxy=(CommandSender)Proxy.newProxyInstance(getClass().getClassLoader(),new Class[]{CommandSender.class},this);}public Object invoke(Object p,Method m,Object[] a){return switch(m.getName()){case "hasPermission"->perms.contains(a[0].toString());case "sendMessage"->{messages.add(Arrays.toString(a));yield null;}case "getName"->"GACFixtureConsole";case "getServer"->Bukkit.getServer();case "toString"->"GACFixtureConsole";case "hashCode"->System.identityHashCode(p);case "equals"->p==a[0];default->defaultValue(m.getReturnType());};}}
 static class FakePlayer implements InvocationHandler{Set<String> perms;List<String> messages=new ArrayList<>();Player proxy;Inventory top;UUID id=UUID.randomUUID();FakePlayer(Set<String> p){perms=new HashSet<>(p);proxy=(Player)Proxy.newProxyInstance(getClass().getClassLoader(),new Class[]{Player.class},this);}InventoryView view(){return (InventoryView)Proxy.newProxyInstance(getClass().getClassLoader(),new Class[]{InventoryView.class},(p,m,a)->switch(m.getName()){case "getTopInventory"->top;case "getBottomInventory"->top;case "getPlayer"->proxy;case "getType"->InventoryType.CHEST;case "getCursor"->new ItemStack(Material.AIR);case "convertSlot"->a[0];case "getInventory"->top;case "countSlots"->90;case "getTitle","getOriginalTitle"->"Fixture";default->defaultValue(m.getReturnType());});}public Object invoke(Object p,Method m,Object[] a){return switch(m.getName()){case "getUniqueId"->id;case "getName"->"GACGuiFixture";case "sendMessage"->{messages.add(Arrays.toString(a));yield null;}case "isOnline"->true;case "hasPermission"->perms.contains(a[0].toString());case "getServer"->Bukkit.getServer();case "openInventory"->{top=(Inventory)a[0];yield view();}case "getOpenInventory"->view();case "toString"->"GACGuiFixture";case "hashCode"->System.identityHashCode(p);case "equals"->p==a[0];default->defaultValue(m.getReturnType());};}}
}
