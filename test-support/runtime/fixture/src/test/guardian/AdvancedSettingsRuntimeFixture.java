/* SPDX-License-Identifier: GPL-3.0-only */
package test.guardian;

import dev.guardianac.bukkit.*;
import org.bukkit.*;
import org.bukkit.entity.Player;
import org.bukkit.event.inventory.*;
import org.bukkit.inventory.*;
import org.bukkit.configuration.file.YamlConfiguration;
import java.lang.reflect.*;
import java.util.*;
import java.util.regex.*;

/** Bounded real-inventory resource-editor checks; proxy players are synthetic administrators. */
final class AdvancedSettingsRuntimeFixture {
    private static final Set<String> ADMIN=Set.of("guardianac.view","guardianac.admin");
    private static final String[] KEYS={"max-players","packets-per-tick","evidence-mib","evidence-days","replay-total-mib","replay-per-player-kib"};
    private static final int[] MIN={16,64,16,1,1,4},MAX={10000,4096,2048,3650,256,512},DEFAULT={2000,1024,256,90,32,64};
    private final GuardianFixture f;private final GuardianPlugin plugin;private final GuiManager gui;private final Runnable completion;
    private final int[] saved=new int[6];private final List<Actor> actors=new ArrayList<>();private final boolean broadcast;
    private boolean finished;
    @FunctionalInterface private interface Step {void run()throws Exception;}
    private AdvancedSettingsRuntimeFixture(GuardianFixture f,Runnable completion)throws Exception {
        this.f=f;plugin=f.guardian;this.completion=completion;Field field=GuardianPlugin.class.getDeclaredField("gui");field.setAccessible(true);gui=(GuiManager)field.get(plugin);
        for(int i=0;i<6;i++)saved[i]=read(i);broadcast=plugin.settings().enabled("broadcast");
    }
    static void run(GuardianFixture f,Runnable completion)throws Exception {var run=new AdvancedSettingsRuntimeFixture(f,completion);try{run.start();}catch(Throwable e){run.fail(e);}}
    private void pass(String key,boolean value){f.pass("advanced_settings_"+key,value);}
    private int read(int i){return plugin.settings().limit(KEYS[i],DEFAULT[i],MIN[i],MAX[i]);}
    private void later(Step step){Bukkit.getScheduler().runTaskLater(f,()->{if(!finished)try{step.run();}catch(Throwable e){fail(e);}},6);}
    private void fail(Throwable e){f.error("advanced_settings_fixture",e);finish();}
    private void finish(){if(finished)return;finished=true;try{
        for(Actor actor:actors)if(actor.top!=null)actor.proxy.closeInventory();
        for(int i=0;i<6;i++)plugin.settings().set("limits."+KEYS[i],saved[i]);plugin.settings().set("broadcast",broadcast);plugin.applySettings();
    }catch(Throwable e){f.error("advanced_settings_restore",e);}completion.run();}
    private static String text(Inventory inventory){StringBuilder result=new StringBuilder();for(ItemStack item:inventory.getContents())if(item!=null&&item.hasItemMeta()){var meta=item.getItemMeta();result.append(meta.getDisplayName()).append(meta.getLore());}return ChatColor.stripColor(result.toString());}
    private static int proposed(Actor actor){Matcher matcher=Pattern.compile("[0-9]+").matcher(ChatColor.stripColor(actor.top.getItem(13).getItemMeta().getDisplayName()));if(!matcher.find())throw new IllegalStateException("Missing resource draft value");return Integer.parseInt(matcher.group());}
    private InventoryClickEvent event(Actor actor,int slot,ClickType type){return new InventoryClickEvent(actor.view(),InventoryType.SlotType.CONTAINER,slot,type,InventoryAction.PICKUP_ALL,type==ClickType.NUMBER_KEY?1:-1);}
    private void click(Actor actor,int slot,Step next){Bukkit.getPluginManager().callEvent(event(actor,slot,ClickType.LEFT));later(next);}
    private void list(Actor actor)throws Exception {Method method=GuiManager.class.getDeclaredMethod("advanced",Player.class,int.class);method.setAccessible(true);method.invoke(gui,actor.proxy,0);}
    private void editor(Actor actor,int index,Step next)throws Exception {list(actor);click(actor,9+index,next);}
    private void start()throws Exception {
        Actor viewer=new Actor(Set.of("guardianac.view"));gui.main(viewer.proxy);
        click(viewer,40,()->click(viewer,22,()->{
            pass("reachable_from_system_settings",viewer.top.getSize()==54&&text(viewer.top).contains("limits.max-players")&&text(viewer.top).contains("limits.replay-per-player-kib"));
            click(viewer,9,()->{
                pass("view_only_can_inspect_but_not_edit",viewer.top.getItem(46).getType()!=Material.LIME_CONCRETE&&viewer.top.getItem(19).getType()==Material.BLACK_STAINED_GLASS_PANE);
                click(viewer,46,()->{pass("view_only_cannot_save",read(0)==saved[0]);viewer.proxy.closeInventory();permissionLoss();});
            });
        }));
    }
    private void permissionLoss()throws Exception {
        Actor actor=new Actor(ADMIN);editor(actor,0,()->click(actor,23,()->{
            pass("adjustment_is_unsaved",proposed(actor)==saved[0]+1&&read(0)==saved[0]);
            Bukkit.getPluginManager().callEvent(event(actor,46,ClickType.LEFT));actor.perms.remove("guardianac.admin");
            later(()->{pass("save_rechecks_admin_at_dispatch",read(0)==saved[0]);actor.perms.add("guardianac.admin");staleRevision(actor,false);});
        }));
    }
    private void staleRevision(Actor actor,boolean reload)throws Exception {
        editor(actor,0,()->click(actor,23,()->{
            if(reload){plugin.settings().reload();plugin.applySettings();}else {plugin.settings().set("broadcast",!broadcast);plugin.applySettings();}
            click(actor,46,()->{pass(reload?"reload_invalidates_unsaved_draft":"unrelated_change_invalidates_draft",read(0)==saved[0]&&actor.messages.stream().anyMatch(m->m.contains("最新")||m.contains("latest")));
                plugin.settings().set("broadcast",broadcast);plugin.applySettings();if(reload)closeQueued(actor);else staleRevision(actor,true);
            });
        }));
    }
    private void closeQueued(Actor actor)throws Exception {
        editor(actor,0,()->click(actor,23,()->{
            Bukkit.getPluginManager().callEvent(event(actor,46,ClickType.LEFT));actor.proxy.closeInventory();
            later(()->{pass("close_invalidates_queued_save",read(0)==saved[0]);editor(actor,0,()->{
                Actor replacement=new Actor(ADMIN);replacement.id=actor.id;replacement.top=actor.top;var wrong=event(replacement,23,ClickType.LEFT);Bukkit.getPluginManager().callEvent(wrong);
                later(()->{pass("same_uuid_wrong_player_cannot_edit",wrong.isCancelled()&&proposed(actor)==saved[0]);replacement.top=null;bounds(actor,0);});
            });});
        }));
    }
    private void bounds(Actor actor,int index)throws Exception {
        if(index==6){finish();return;}
        editor(actor,index,()->{
            String lore=text(actor.top);pass(KEYS[index]+"_labels_and_54_slots",actor.top.getSize()==54&&lore.contains("limits."+KEYS[index])&&lore.contains(Integer.toString(MIN[index]))&&lore.contains(Integer.toString(MAX[index]))&&(index==1?(lore.contains("次のtick")||lore.contains("next tick")):(lore.contains("再起動")||lore.contains("restart"))));
            click(actor,29,()->click(actor,19,()->{
                pass(KEYS[index]+"_minimum_is_clamped_and_unsaved",proposed(actor)==MIN[index]&&read(index)==saved[index]);
                click(actor,33,()->click(actor,25,()->{
                    pass(KEYS[index]+"_maximum_is_clamped_and_unsaved",proposed(actor)==MAX[index]&&read(index)==saved[index]);
                    click(actor,31,()->{pass(KEYS[index]+"_default_staged_only",proposed(actor)==DEFAULT[index]&&read(index)==saved[index]);
                        click(actor,23,()->click(actor,46,()->{
                            int expected=DEFAULT[index]+1;var disk=YamlConfiguration.loadConfiguration(plugin.getDataFolder().toPath().resolve("config.yml").toFile());
                            pass(KEYS[index]+"_explicit_save_persisted",read(index)==expected&&disk.getInt("limits."+KEYS[index])==expected);
                            if(index!=1)pass(KEYS[index]+"_restart_pending_visible",text(actor.top).contains("再起動待ち")||text(actor.top).contains("waiting for restart"));
                            else pass("packet_budget_live_value_visible",text(actor.top).contains("1025")&&(text(actor.top).contains("次のtick")||text(actor.top).contains("next tick")));
                            if(index==0){Field max=dev.guardianac.core.DetectionEngine.class.getDeclaredField("maxPlayers");max.setAccessible(true);pass("saved_capacity_does_not_reallocate_running_engine",max.getInt(plugin.engine())==37);}
                            plugin.settings().set("limits."+KEYS[index],saved[index]);plugin.applySettings();bounds(actor,index+1);
                        }));
                    });
                }));
            }));
        });
    }
    private final class Actor extends GuardianFixture.FakePlayer {
        Actor(Set<String> permissions){super(permissions);actors.add(this);}
        @Override public Object invoke(Object proxy,Method method,Object[] args){
            if((method.getName().equals("openInventory")||method.getName().equals("closeInventory"))&&top!=null){Bukkit.getPluginManager().callEvent(new InventoryCloseEvent(view()));if(method.getName().equals("closeInventory")){top=null;return null;}}
            return super.invoke(proxy,method,args);
        }
    }
}
