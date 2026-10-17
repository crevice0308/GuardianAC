/* SPDX-License-Identifier: GPL-3.0-only */
package test.guardian;

import dev.guardianac.bukkit.*;
import dev.guardianac.security.ConfirmationManager;
import dev.guardianac.storage.*;
import org.bukkit.*;
import org.bukkit.entity.Player;
import org.bukkit.event.inventory.*;
import org.bukkit.inventory.*;
import java.lang.reflect.*;
import java.net.InetAddress;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.TimeUnit;
import java.util.stream.IntStream;

/** Isolated real-inventory routing checks with synthetic actors, not graphical-client coverage. */
final class GuiIpUnbanFixture {
    private final GuardianFixture fixture;
    private final GuardianPlugin plugin;
    private final GuiManager gui;
    private BanRecord target,existing,late,owned,foreign;
    private GuiPlayer actor;
    private String token;
    private static final Set<String> UNBANNER=Set.of("guardianac.view","guardianac.unban");
    private static final String SHARED="127.0.0.41", OWNED="127.0.0.42", FOREIGN="127.0.0.43";
    private GuiIpUnbanFixture(GuardianFixture fixture)throws Exception {
        this.fixture=fixture;plugin=fixture.guardian;
        Field field=GuardianPlugin.class.getDeclaredField("gui");field.setAccessible(true);gui=(GuiManager)field.get(plugin);
    }
    static void run(GuardianFixture fixture)throws Exception {new GuiIpUnbanFixture(fixture).start();}
    private void pass(String key,boolean value){fixture.pass("gui_ip_"+key,value);}
    @FunctionalInterface private interface Step {void run()throws Exception;}
    private void later(Step step) {
        Bukkit.getScheduler().runTaskLater(fixture,()->{try{step.run();}catch(Throwable e){fixture.error("gui_ip_async",e);done();}},2);
    }
    private void done(){fixture.save();fixture.getLogger().info("GACTEST GUI IP ASYNC DONE");}
    private BanRecord make(int suffix,String name,String ip)throws Exception {
        return fixture.make(new UUID(0x4000L,0x8000000000000100L+suffix),name,ip,"GAC-%08X".formatted(0x31000000+suffix));
    }
    private BanRecord current(BanRecord record)throws Exception{return plugin.storage().findCase(record.caseId()).get(3,TimeUnit.SECONDS).orElseThrow();}
    private void detail(GuiPlayer player,BanRecord record)throws Exception {
        Method method=GuiManager.class.getDeclaredMethod("caseDetail",Player.class,BanRecord.class);method.setAccessible(true);method.invoke(gui,player.proxy,record);
    }
    private void sync(BanRecord record,String ip)throws Exception {
        Method method=GuardianPlugin.class.getDeclaredMethod("syncNative",BanRecord.class,String.class);method.setAccessible(true);method.invoke(plugin,record,ip);
    }
    private void click(GuiPlayer player,int slot) {
        var event=new InventoryClickEvent(player.view(),InventoryType.SlotType.CONTAINER,slot,ClickType.LEFT,InventoryAction.PICKUP_ALL);
        gui.click(event);pass("click_"+slot+"_cancelled",event.isCancelled());
    }
    private static String token(Inventory inventory)throws Exception {
        if(inventory==null)return null;Object menu=inventory.getHolder();Field field=menu.getClass().getDeclaredField("confirmationToken");field.setAccessible(true);return (String)field.get(menu);
    }
    private static String visible(Inventory inventory) {
        StringBuilder out=new StringBuilder();for(ItemStack item:inventory.getContents())if(item!=null&&item.hasItemMeta()) {
            var meta=item.getItemMeta();out.append(meta.getDisplayName()).append(meta.getLore());
        }return out.toString();
    }
    private boolean nativeUuid(BanRecord record) {
        org.bukkit.ban.ProfileBanList profiles=Bukkit.getBanList(BanList.Type.PROFILE);
        return profiles.isBanned(Bukkit.createPlayerProfile(record.playerId(),record.playerName()));
    }
    private org.bukkit.BanEntry<InetAddress> nativeIp(String ip)throws Exception {
        org.bukkit.ban.IpBanList ips=Bukkit.getBanList(BanList.Type.IP);return ips.getBanEntry(InetAddress.getByName(ip));
    }
    private void start()throws Exception {
        target=make(1,"GacGuiTarget",SHARED);existing=make(2,"GacGuiExisting",SHARED);
        owned=make(3,"GacGuiOwned",OWNED);foreign=make(4,"GacGuiForeign",FOREIGN);
        sync(target,SHARED);sync(owned,OWNED);sync(foreign,FOREIGN);
        org.bukkit.ban.IpBanList ips=Bukkit.getBanList(BanList.Type.IP);
        ips.addBan(InetAddress.getByName(FOREIGN),"Synthetic foreign administrator ban",(java.util.Date)null,"OtherPlugin");
        GuiPlayer view=new GuiPlayer(Set.of("guardianac.view"));detail(view,target);
        pass("case_button_present",view.top.getItem(37).getType()==Material.LIGHT_BLUE_DYE);
        pass("case_54_slots",view.top.getSize()==54);pass("case_hides_address",!visible(view.top).contains(SHARED));
        for(ClickType type:List.of(ClickType.SHIFT_LEFT,ClickType.NUMBER_KEY,ClickType.DOUBLE_CLICK,ClickType.DROP,ClickType.SWAP_OFFHAND)) {
            var event=new InventoryClickEvent(view.view(),InventoryType.SlotType.CONTAINER,37,type,InventoryAction.PICKUP_ALL,type==ClickType.NUMBER_KEY?1:-1);
            gui.click(event);pass("transfer_"+type+"_cancelled",event.isCancelled());
        }
        var drag=new InventoryDragEvent(view.view(),new ItemStack(Material.STONE),new ItemStack(Material.STONE),false,Map.of(37,new ItemStack(Material.STONE)));
        gui.drag(drag);pass("drag_cancelled",drag.isCancelled());
        click(view,37);
        later(()->{
            pass("view_only_cannot_request",token(view.top)==null&&current(target).ipActiveAt(Instant.now()));
            GuiPlayer revoked=new GuiPlayer(UNBANNER);detail(revoked,target);click(revoked,37);revoked.perms.remove("guardianac.unban");
            later(()->{pass("request_permission_rechecked",token(revoked.top)==null&&current(target).ipActiveAt(Instant.now()));closedAndOtherActor();});
        });
    }
    private void closedAndOtherActor()throws Exception {
        GuiPlayer closed=new GuiPlayer(UNBANNER);plugin.requestUnban(closed.proxy,target,"IP");String closeToken=token(closed.top);
        GuiPlayer other=new GuiPlayer(UNBANNER);plugin.confirm(other.proxy,closeToken,"Must fail for another actor");
        pass("other_actor_cannot_consume",other.messages.stream().anyMatch(m->m.contains("invalid")||m.contains("無効"))&&current(target).ipActiveAt(Instant.now()));
        closed.proxy.closeInventory();plugin.confirm(closed.proxy,closeToken,"Must fail after closing");
        pass("close_invalidates_token",closed.messages.stream().anyMatch(m->m.contains("invalid")||m.contains("無効"))&&current(target).ipActiveAt(Instant.now()));
        GuiPlayer revoked=new GuiPlayer(UNBANNER);plugin.requestUnban(revoked.proxy,target,"IP");String revokeToken=token(revoked.top);
        click(revoked,46);revoked.perms.remove("guardianac.unban");
        later(()->{
            pass("confirm_permission_rechecked",current(target).ipActiveAt(Instant.now()));
            revoked.perms.add("guardianac.unban");plugin.confirm(revoked.proxy,revokeToken,"Must fail after rejected GUI action closes");
            pass("rejected_confirmation_not_replayable",revoked.messages.stream().anyMatch(m->m.contains("invalid")||m.contains("無効")));
            pageTransfer();
        });
    }
    @SuppressWarnings("unchecked") private void pageTransfer()throws Exception {
        Field field=GuardianPlugin.class.getDeclaredField("confirmations");field.setAccessible(true);
        var confirmations=(ConfirmationManager<GuardianPlugin.UnbanAction>)field.get(plugin);
        List<String> ids=IntStream.range(0,37).mapToObj(i->"GAC-%08X".formatted(0x41000000+i)).toList();
        var operation=new GuardianPlugin.UnbanAction(ids,"IP",null,"Protected IP bans");
        GuiPlayer paged=new GuiPlayer(UNBANNER);var pending=confirmations.request(paged.id.toString(),operation,System.nanoTime()/1_000_000);
        gui.confirm(paged.proxy,pending.token(),operation);Inventory first=paged.top;click(paged,50);
        later(()->{
            pass("preview_pagination_keeps_same_token",paged.top!=first&&pending.token().equals(token(paged.top))&&token(first)==null);
            pass("preview_page_has_exact_last_case",visible(paged.top).contains(ids.getLast())&&!visible(paged.top).contains(ids.getFirst()));
            pass("preview_pagination_stays_54_slots",paged.top.getSize()==54);
            paged.proxy.closeInventory();plugin.confirm(paged.proxy,pending.token(),"Must fail after paged close");
            pass("paged_close_invalidates_token",paged.messages.stream().anyMatch(m->m.contains("invalid")||m.contains("無効")));
            actor=new GuiPlayer(UNBANNER);detail(actor,target);click(actor,37);
            later(this::confirmShared);
        });
    }
    private void confirmShared()throws Exception {
        token=token(actor.top);pass("click_opens_ip_confirmation",token!=null&&visible(actor.top).contains("IP")&&visible(actor.top).contains(target.caseId()));
        pass("confirmation_hides_address",!visible(actor.top).contains(SHARED));
        late=make(5,"GacGuiLater",SHARED); // Created after the GUI preview, so it must never be included.
        click(actor,46);awaitRecorded(actor,100,()->{
            BanRecord changed=current(target);pass("dual_retains_uuid_only",changed.uuidActiveAt(Instant.now())&&!changed.ipActiveAt(Instant.now())&&changed.revokedAt()==null);
            pass("existing_same_ip_case_unchanged",current(existing).equals(existing));pass("later_same_ip_case_unchanged",current(late).equals(late));
            pass("uuid_native_remains",nativeUuid(target));
            var survivor=nativeIp(SHARED);pass("native_same_ip_survivor_preserved",survivor!=null&&(survivor.getSource().equals(existing.ownershipMarker())||survivor.getSource().equals(late.ownershipMarker())));
            var audits=plugin.storage().unbanHistory(target.caseId()).get(3,TimeUnit.SECONDS);
            pass("audit_exact_actor_scope_reason",audits.size()==1&&audits.getFirst().scope().equals("IP")&&audits.getFirst().actor().equals(actor.id.toString())&&audits.getFirst().reason().equals("Administrator confirmed in GUI"));
            pass("evidence_preserved",plugin.storage().evidence(target.evidenceId()).get(3,TimeUnit.SECONDS).orElseThrow().payloadAvailable());
            plugin.confirm(actor.proxy,token,"Replay must fail");pass("token_one_use",actor.messages.stream().anyMatch(m->m.contains("invalid")||m.contains("無効"))&&plugin.storage().unbanHistory(target.caseId()).get(3,TimeUnit.SECONDS).size()==1);
            detail(actor,changed);pass("revoked_ip_button_disabled",actor.top.getItem(37).getType()==Material.GRAY_DYE);
            removeOwnedOnly();
        });
    }
    private void removeOwnedOnly()throws Exception {
        GuiPlayer ownActor=new GuiPlayer(UNBANNER);detail(ownActor,owned);click(ownActor,37);
        later(()->{click(ownActor,46);awaitRecorded(ownActor,100,()->{
            pass("owned_native_ip_removed",nativeIp(OWNED)==null);pass("owned_native_uuid_preserved",nativeUuid(owned));
            pass("owned_dual_uuid_still_active",current(owned).uuidActiveAt(Instant.now())&&!current(owned).ipActiveAt(Instant.now()));
            GuiPlayer foreignActor=new GuiPlayer(UNBANNER);detail(foreignActor,foreign);click(foreignActor,37);
            later(()->{click(foreignActor,46);awaitRecorded(foreignActor,100,()->{
                var entry=nativeIp(FOREIGN);pass("foreign_native_ip_untouched",entry!=null&&entry.getSource().equals("OtherPlugin"));
                pass("foreign_case_uuid_preserved",nativeUuid(foreign)&&current(foreign).uuidActiveAt(Instant.now()));done();
            });});
        });});
    }
    private void awaitRecorded(GuiPlayer player,int remaining,Step next) {
        later(()->{
            if(player.messages.stream().anyMatch(m->m.contains("Unban recorded")||m.contains("解除を記録")))next.run();
            else if(remaining<=0){pass("confirmation_finished_before_timeout",false);done();}
            else awaitRecorded(player,remaining-2,next);
        });
    }
    /** Simulates real InventoryCloseEvent delivery on replacement and close. */
    private final class GuiPlayer extends GuardianFixture.FakePlayer {
        GuiPlayer(Set<String> permissions){super(permissions);}
        @Override public Object invoke(Object proxy,Method method,Object[] arguments) {
            if((method.getName().equals("openInventory")||method.getName().equals("closeInventory"))&&top!=null) {
                Bukkit.getPluginManager().callEvent(new InventoryCloseEvent(view()));
                if(method.getName().equals("closeInventory")){top=null;return null;}
            }
            return super.invoke(proxy,method,arguments);
        }
    }
}
