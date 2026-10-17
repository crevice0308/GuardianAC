/* SPDX-License-Identifier: GPL-3.0-only */
package test.guardian;

import dev.guardianac.bukkit.GuardianPlugin;
import dev.guardianac.core.AutomaticBanGate;
import dev.guardianac.security.ConfirmationManager;
import dev.guardianac.security.NativeBanReconciler;
import dev.guardianac.storage.*;
import org.bukkit.*;
import org.bukkit.ban.*;
import java.lang.reflect.*;
import java.net.InetAddress;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.*;

/** Synthetic loopback-only native lifecycle fixtures. Never install this helper on production. */
final class NativeLifecycleFixture {
    private final GuardianFixture f;
    private final GuardianPlugin plugin;
    private boolean done;
    private NativeLifecycleFixture(GuardianFixture f){this.f=f;plugin=f.guardian;}
    @FunctionalInterface private interface Step {void run()throws Exception;}
    @FunctionalInterface private interface Check {boolean get()throws Exception;}
    static void run(GuardianFixture f){var fixture=new NativeLifecycleFixture(f);try{fixture.staleCompletions();}catch(Throwable error){fixture.fail(error);}}
    static void seedRestart(GuardianFixture f){var fixture=new NativeLifecycleFixture(f);try{fixture.seedCrashWindow();}catch(Throwable error){fixture.fail(error);}}
    static void restart(GuardianFixture f){var fixture=new NativeLifecycleFixture(f);try{fixture.verifyRestart();}catch(Throwable error){fixture.fail(error);}}
    private void pass(String key,boolean success){f.pass("native_lifecycle_"+key,success);}
    private void finish(String phase){if(done)return;done=true;f.save();f.getLogger().info("GACTEST NATIVE LIFECYCLE "+phase+" DONE");}
    private void fail(Throwable error){f.error("native_lifecycle_fixture",error);finish("ASYNC");}
    private void later(Step step){Bukkit.getScheduler().runTaskLater(f,()->{if(done)return;try{step.run();}catch(Throwable error){fail(error);}},2);}
    private void await(Check condition,int ticks,Step next)throws Exception {
        if(condition.get()){next.run();return;}if(ticks<=0)throw new IllegalStateException("Native lifecycle fixture timed out");
        later(()->await(condition,ticks-2,next));
    }
    private static String caseId(int suffix){return String.format(Locale.ROOT,"GAC-%08X",suffix);}
    private static UUID id(int suffix){return new UUID(0x7300000000004000L,0x8000000000000000L|suffix);}
    private static String ip(int suffix){return "127.0.2."+suffix;}
    private BanRecord create(int suffix,int target,BanType type)throws Exception {
        String caseId=caseId(suffix);var existing=plugin.storage().findCase(caseId).get(3,TimeUnit.SECONDS);if(existing.isPresent())return existing.get();
        var evidence=plugin.storage().saveEvidence(new EvidenceInput(id(target),caseId,Instant.now(),"{\"fixture\":true,\"source\":\"isolated native lifecycle fixture\"}")).get(3,TimeUnit.SECONDS);
        return plugin.storage().createBan(new BanRequest(id(target),"GACNative"+target,ip(target),type,"Synthetic native lifecycle",null,100,caseId,evidence.id(),"Isolated runtime fixture")).get(3,TimeUnit.SECONDS);
    }
    private void revoke(BanRecord r)throws Exception {plugin.storage().revokeCase(r.caseId(),"CONSOLE","Synthetic lifecycle revocation",Instant.now()).get(3,TimeUnit.SECONDS);}
    private NativeBanReconciler reconciler()throws Exception {Field field=GuardianPlugin.class.getDeclaredField("nativeBans");field.setAccessible(true);return (NativeBanReconciler)field.get(plugin);}
    private long count()throws Exception {Field field=GuardianPlugin.class.getDeclaredField("banCount");field.setAccessible(true);return field.getLong(plugin);}
    private CompletableFuture<Void> repair(BanRecord record)throws Exception {return reconciler().request(record.caseId(),null);}
    private org.bukkit.BanEntry<?> profile(BanRecord r){ProfileBanList list=Bukkit.getBanList(BanList.Type.PROFILE);return list.getBanEntry(Bukkit.createPlayerProfile(r.playerId(),r.playerName()));}
    private org.bukkit.BanEntry<?> address(int target)throws Exception {IpBanList list=Bukkit.getBanList(BanList.Type.IP);return list.getBanEntry(InetAddress.getByName(ip(target)));}
    private void seedNative(BanRecord r,int target,String source)throws Exception {
        ProfileBanList profiles=Bukkit.getBanList(BanList.Type.PROFILE);IpBanList ips=Bukkit.getBanList(BanList.Type.IP);
        profiles.addBan(Bukkit.createPlayerProfile(r.playerId(),r.playerName()),"Synthetic crash-window entry",(Date)null,source);
        ips.addBan(InetAddress.getByName(ip(target)),"Synthetic crash-window entry",(Date)null,source);
    }
    private boolean absent(BanRecord r,int target)throws Exception{return profile(r)==null&&address(target)==null;}
    private boolean owned(BanRecord r,int target)throws Exception{return profile(r)!=null&&address(target)!=null&&r.ownershipMarker().equals(profile(r).getSource())&&r.ownershipMarker().equals(address(target).getSource());}
    private void staleCompletions()throws Exception {
        BanRecord reviewed=create(0x73000001,101,BanType.IP),automatic=create(0x73000002,102,BanType.UUID);
        revoke(reviewed);revoke(automatic);long before=count();
        Method reviewedFinish=GuardianPlugin.class.getDeclaredMethod("finishReviewedPermanentIp",BanRecord.class,String.class);reviewedFinish.setAccessible(true);reviewedFinish.invoke(plugin,reviewed,ip(101));
        Method automaticFinish=GuardianPlugin.class.getDeclaredMethod("reconcileCommitted",String.class);automaticFinish.setAccessible(true);automaticFinish.invoke(plugin,automatic.caseId());
        var barrier=repair(automatic);
        await(barrier::isDone,240,()->{
            barrier.join();pass("revoked_commit_callbacks_no_notification",count()==before);
            pass("revoked_reviewed_ip_not_reinstalled",address(101)==null);
            pass("revoked_automatic_uuid_not_reinstalled",profile(automatic)==null);orphanAndOwnership();
        });
    }
    private void orphanAndOwnership()throws Exception {
        BanRecord orphan=create(0x73000003,103,BanType.DUAL),mismatch=create(0x73000004,104,BanType.DUAL),foreign=create(0x73000005,105,BanType.DUAL);
        revoke(orphan);revoke(mismatch);revoke(foreign);seedNative(orphan,103,orphan.ownershipMarker());seedNative(mismatch,104,mismatch.ownershipMarker()+"-unknown-token");seedNative(foreign,105,"ForeignAdministrator");
        var a=repair(orphan);var b=repair(mismatch);var c=repair(foreign);
        await(()->a.isDone()&&b.isDone()&&c.isDone(),300,()->{
            a.join();b.join();c.join();pass("inactive_uuid_and_ip_orphans_cleaned",absent(orphan,103));
            pass("mismatched_exact_marker_preserved",profile(mismatch)!=null&&address(104)!=null&&profile(mismatch).getSource().endsWith("unknown-token")&&address(104).getSource().endsWith("unknown-token"));
            pass("foreign_uuid_and_ip_preserved",profile(foreign)!=null&&address(105)!=null&&profile(foreign).getSource().equals("ForeignAdministrator")&&address(105).getSource().equals("ForeignAdministrator"));survivingRestriction();
        });
    }
    private void survivingRestriction()throws Exception {
        BanRecord old=create(0x73000006,106,BanType.DUAL),survivor=create(0x73000007,106,BanType.DUAL);
        revoke(old);seedNative(old,106,old.ownershipMarker());var completion=repair(old);
        await(completion::isDone,240,()->{
            completion.join();pass("surviving_uuid_and_ip_reinstated",owned(survivor,106));
            plugin.storage().revokeCaseIp(survivor.caseId(),"CONSOLE","Synthetic partial unban",Instant.now()).get(3,TimeUnit.SECONDS);var partial=repair(survivor);
            await(partial::isDone,240,()->{partial.join();pass("partial_ip_unban_keeps_uuid",profile(survivor)!=null&&survivor.ownershipMarker().equals(profile(survivor).getSource())&&address(106)==null);confirmationBoundary();});
        });
    }
    @SuppressWarnings("unchecked")
    private void confirmationBoundary()throws Exception {
        BanRecord old=create(0x73000008,107,BanType.UUID);
        Field gateField=GuardianPlugin.class.getDeclaredField("automaticBans");gateField.setAccessible(true);AutomaticBanGate gate=(AutomaticBanGate)gateField.get(plugin);
        Method policyMethod=GuardianPlugin.class.getDeclaredMethod("enforcementPolicy");policyMethod.setAccessible(true);AutomaticBanGate.Policy original=(AutomaticBanGate.Policy)policyMethod.invoke(plugin);
        // Isolated held-guard test; does not invent any cheating evidence or run an automatic DB sanction.
        gate.publish(new AutomaticBanGate.Policy(true,true,original.preset(),original.enabledChecks(),original.sanctionPolicy()));
        AutomaticBanGate.Ticket held=gate.issue(old.playerId(),false).orElseThrow(),unrelated=gate.issue(id(108),false).orElseThrow();
        pass("held_guard_authorized_before_confirmation",gate.authorize(held,true)&&gate.authorize(unrelated,true));
        Field confirmationsField=GuardianPlugin.class.getDeclaredField("confirmations");confirmationsField.setAccessible(true);
        ConfirmationManager<GuardianPlugin.UnbanAction> confirmations=(ConfirmationManager<GuardianPlugin.UnbanAction>)confirmationsField.get(plugin);
        var action=GuardianPlugin.UnbanAction.preview(List.of(old),"ALL","Synthetic exact-case reset");
        var pending=confirmations.request("CONSOLE",action,System.nanoTime()/1_000_000);
        GuardianFixture.FakeSender sender=new GuardianFixture.FakeSender(Set.of("guardianac.unban"));
        plugin.confirm(sender.proxy,pending.token(),"Synthetic confirmed old-case reset");
        // No tick, scheduler callback, or SQL wait between confirmation and these worker-safe claims.
        pass("confirmation_immediately_cancels_unclaimed_guard",!held.tryCommit());
        pass("confirmation_preserves_unrelated_guard",unrelated.tryCommit());
        AutomaticBanGate.Ticket fresh=gate.issue(old.playerId(),false).orElseThrow();
        pass("fresh_decision_after_confirmation_remains_eligible",gate.authorize(fresh,true)&&fresh.tryCommit());gate.publish(original);
        BanRecord independent=create(0x73000009,107,BanType.UUID);var completion=repair(old);
        await(completion::isDone,240,()->{
            completion.join();pass("confirmed_case_scope_does_not_revoke_later_independent_case",
                !plugin.storage().findCase(old.caseId()).get(3,TimeUnit.SECONDS).orElseThrow().activeAt(Instant.now())
                &&plugin.storage().findCase(independent.caseId()).get(3,TimeUnit.SECONDS).orElseThrow().activeAt(Instant.now()));
            finish("ASYNC");
        });
    }
    /** Must be the final fixture command before shutdown: freezes ONLY this isolated reconciler. */
    private void seedCrashWindow()throws Exception {
        BanRecord orphan=create(0x74000001,111,BanType.DUAL),foreign=create(0x74000002,112,BanType.DUAL),old=create(0x74000003,113,BanType.DUAL),survivor=create(0x74000004,113,BanType.DUAL),mismatch=create(0x74000005,114,BanType.DUAL);
        revoke(orphan);revoke(foreign);revoke(old);revoke(mismatch);
        reconciler().close(); // models a process lost between durable revocation and native pardon
        seedNative(orphan,111,orphan.ownershipMarker());seedNative(foreign,112,"ForeignRestartAdministrator");seedNative(old,113,old.ownershipMarker());seedNative(mismatch,114,mismatch.ownershipMarker()+"-unknown-token");
        pass("restart_crash_window_seeded",owned(orphan,111)&&owned(old,113)&&survivor.activeAt(Instant.now()));finish("SEED");
    }
    private void verifyRestart()throws Exception {
        BanRecord orphan=plugin.storage().findCase(caseId(0x74000001)).get(3,TimeUnit.SECONDS).orElseThrow(),foreign=plugin.storage().findCase(caseId(0x74000002)).get(3,TimeUnit.SECONDS).orElseThrow(),survivor=plugin.storage().findCase(caseId(0x74000004)).get(3,TimeUnit.SECONDS).orElseThrow(),mismatch=plugin.storage().findCase(caseId(0x74000005)).get(3,TimeUnit.SECONDS).orElseThrow();
        // Do not call repair or syncExisting: this specifically observes the normal startup sweep.
        await(()->absent(orphan,111)&&owned(survivor,113),600,()->{
            pass("restart_inactive_uuid_and_ip_native_orphans_cleaned",absent(orphan,111));pass("restart_remaining_active_case_reinstated",owned(survivor,113));
            pass("restart_foreign_native_entries_preserved",profile(foreign)!=null&&address(112)!=null&&profile(foreign).getSource().equals("ForeignRestartAdministrator")&&address(112).getSource().equals("ForeignRestartAdministrator"));
            pass("restart_unknown_token_preserved",profile(mismatch)!=null&&address(114)!=null&&profile(mismatch).getSource().endsWith("unknown-token")&&address(114).getSource().endsWith("unknown-token"));finish("RESTART");
        });
    }
}
