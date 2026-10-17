/* SPDX-License-Identifier: GPL-3.0-only */
package test.guardian;

import com.google.gson.JsonArray;
import com.google.gson.JsonParser;
import dev.guardianac.bukkit.*;
import dev.guardianac.core.*;
import dev.guardianac.checks.CheckCatalog;
import dev.guardianac.storage.*;
import org.bukkit.*;
import org.bukkit.entity.Player;
import org.bukkit.event.HandlerList;
import org.bukkit.event.inventory.*;
import org.bukkit.inventory.*;
import java.lang.reflect.*;
import java.net.InetAddress;
import java.nio.file.*;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.*;

/**
 * Isolated, explicitly SYNTHETIC trusted-identity/proof fixtures. The injected controller is a
 * separate instance: no production controller, authentication or network identity is modified.
 * Inventories, event dispatch, SQLite transactions and native ban entries use the real server.
 */
final class PermanentIpFixture {
    private static final Set<String> ADMIN=Set.of("guardianac.view","guardianac.admin");
    private static final String POSITIVE_CASE="GAC-72000001", POSITIVE_IP="127.0.0.71";
    private static final String STATE="permanent-ip-state.properties";
    private final GuardianFixture f;
    private final GuardianPlugin plugin;
    private final Map<UUID,Player> sessions=new HashMap<>();
    private final Map<String,Object> savedSettings=new LinkedHashMap<>();
    private PermanentIpController controller,otherController;
    private BanRecord positive;
    private int nextId=2;
    private boolean finished;
    @FunctionalInterface private interface Step {void run()throws Exception;}
    @FunctionalInterface private interface Check {boolean get()throws Exception;}
    private PermanentIpFixture(GuardianFixture f){this.f=f;plugin=f.guardian;}
    static void run(GuardianFixture f){PermanentIpFixture run=new PermanentIpFixture(f);try{run.start();}catch(Throwable failure){f.error("permanent_ip_start",failure);run.done();}}
    static void offline(GuardianFixture f)throws Exception {new PermanentIpFixture(f).offlineActor();}
    static void restart(GuardianFixture f)throws Exception {new PermanentIpFixture(f).restartAndRevoke();}
    private void pass(String key,boolean success){f.pass("permanent_ip_"+key,success);}
    private void later(long ticks,Step step){Bukkit.getScheduler().runTaskLater(f,()->{if(finished)return;try{step.run();}catch(Throwable e){f.error("permanent_ip_async",e);done();}},ticks);}
    private void later(Step step){later(6,step);}
    private void await(Check condition,int remaining,Step next)throws Exception {
        if(condition.get()){next.run();return;}
        if(remaining<=0)throw new IllegalStateException("Permanent-IP fixture stage timed out");
        later(2,()->await(condition,remaining-2,next));
    }
    private void done(){
        if(finished)return;finished=true;
        if(controller!=null){controller.close();HandlerList.unregisterAll(controller);}
        if(otherController!=null){otherController.close();HandlerList.unregisterAll(otherController);}
        try{restoreSettings();}catch(Exception error){f.error("permanent_ip_restore_settings",error);}
        f.save();f.getLogger().info("GACTEST PERMANENT IP ASYNC DONE");
    }
    private AutomaticBanGate.Policy policy()throws Exception {
        Method method=GuardianPlugin.class.getDeclaredMethod("enforcementPolicy");method.setAccessible(true);return (AutomaticBanGate.Policy)method.invoke(plugin);
    }
    private void configure()throws Exception {
        savedSettings.put("ban-type",plugin.settings().banType().name());savedSettings.put("ban-duration-hours",plugin.settings().hours(false));
        savedSettings.put("ip-duration-hours",plugin.settings().hours(true));savedSettings.put("allow-permanent-ip",plugin.settings().enabled("allow-permanent-ip"));
        savedSettings.put("auto-ban",plugin.settings().enabled("auto-ban"));savedSettings.put("preset",plugin.settings().preset().name());
        plugin.settings().set("ban-type","DUAL");plugin.settings().set("ban-duration-hours",24);plugin.settings().set("ip-duration-hours",0);
        plugin.settings().set("allow-permanent-ip",true);plugin.settings().set("auto-ban",true);plugin.settings().set("preset","STRICT");plugin.applySettings();
    }
    private void restoreSettings()throws Exception {
        for(var entry:savedSettings.entrySet())plugin.settings().set(entry.getKey(),entry.getValue());
        if(!savedSettings.isEmpty()){plugin.applySettings();savedSettings.clear();}
    }
    private PermanentIpController syntheticController()throws Exception {
        var ctor=PermanentIpController.class.getDeclaredConstructor(GuardianPlugin.class,BooleanSupplier.class,Function.class,LongSupplier.class,Clock.class);
        ctor.setAccessible(true);
        var result=ctor.newInstance(plugin,(BooleanSupplier)()->true,(Function<UUID,Player>)sessions::get,(LongSupplier)System::nanoTime,Clock.systemUTC());
        Bukkit.getPluginManager().registerEvents(result,f);result.publish();return result;
    }
    private static UUID syntheticId(int number){return UUID.fromString("70000000-0000-4000-8000-%012x".formatted(number));}
    private String proof(UUID player,long now,boolean longReplay)throws Exception {
        Sample pitch=Sample.builder(player,Sample.Kind.PACKET,now,200).value("pitch",100).flag("rotation-present").build();
        Sample held=Sample.builder(player,Sample.Kind.PACKET,now,200).value("held-slot",15).flag("held-slot-present").build();
        BanEvidence decision=new BanEvidence(List.of(new Finding("packet.pitch","PROTOCOL_MOVEMENT",true,.995,80,12,now-6000,now,pitch),
            new Finding("packet.held-slot","PROTOCOL_INVENTORY",true,.995,80,12,now-6000,now,held)),now);
        String encoded=PersistedBanEvidence.encode(player,decision,policy(),"[]");
        if(longReplay){JsonArray replay=new JsonArray();var observation=JsonParser.parseString(encoded).getAsJsonObject().getAsJsonArray("findings").get(0).getAsJsonObject().get("observation");
            for(int i=0;i<25;i++)replay.add(observation.deepCopy());encoded=PersistedBanEvidence.encode(player,decision,policy(),replay.toString());}
        return encoded;
    }
    private BanRecord seed(int suffix,String address,boolean trusted,boolean nativeIdentity,String invalid,boolean longReplay)throws Exception {
        UUID player=syntheticId(suffix);String caseId="GAC-%08X".formatted(0x72000000+suffix);Instant now=Instant.now();
        plugin.storage().registerIp(player,address,trusted,now).get(3,TimeUnit.SECONDS);
        String json=proof(player,System.currentTimeMillis(),longReplay);
        if(invalid.equals("legacy"))json="{\"version\":1,\"families\":2,\"vl\":160}";
        if(invalid.equals("predicate")){var obj=JsonParser.parseString(json).getAsJsonObject();obj.getAsJsonArray("findings").get(0).getAsJsonObject().getAsJsonObject("observation").getAsJsonObject("measurements").addProperty("pitch",0);json=obj.toString();}
        if(invalid.equals("missing-field")){var obj=JsonParser.parseString(json).getAsJsonObject();obj.remove("capturedAt");json=obj.toString();}
        var evidence=plugin.storage().saveEvidence(new EvidenceInput(player,caseId,Instant.now(),json)).get(3,TimeUnit.SECONDS);
        AtomicInteger guardCalls=new AtomicInteger();
        BanRecord source=plugin.storage().createAutomaticReviewCandidate(new BanRequest(player,"GacPerma"+suffix,address,BanType.UUID,"Synthetic immutable hard evidence",Instant.ofEpochMilli(System.currentTimeMillis()+86_400_000),160,caseId,evidence.id(),"GuardianAC synthetic fixture"),
            now.minus(Duration.ofDays(30)),nativeIdentity,policy(),()->guardCalls.incrementAndGet()==1).get(3,TimeUnit.SECONDS);
        pass("candidate_"+suffix+"_guard_once_uuid_only",guardCalls.get()==1&&source.type()==BanType.UUID&&!source.permanent());return source;
    }
    private BanRecord newCandidate()throws Exception {int id=nextId++;return seed(id,"127.0.1."+id,true,true,"",false);}
    private BanRecord current(BanRecord record)throws Exception{return plugin.storage().findCase(record.caseId()).get(3,TimeUnit.SECONDS).orElseThrow();}
    private PermanentIpReview review(BanRecord record)throws Exception{return plugin.storage().previewPermanentIp(record.caseId(),policy(),true).get(3,TimeUnit.SECONDS).orElseThrow();}
    private boolean noIp(BanRecord record)throws Exception {
        var address=plugin.storage().protectedIp(record.caseId()).get(3,TimeUnit.SECONDS);
        return current(record).type()==BanType.UUID&&(address.isEmpty()||plugin.storage().matchIp(address.get(),Instant.now()).get(3,TimeUnit.SECONDS).isEmpty());
    }
    private static String visible(Inventory inventory){StringBuilder result=new StringBuilder();if(inventory!=null)for(ItemStack item:inventory.getContents())if(item!=null&&item.hasItemMeta()){var meta=item.getItemMeta();result.append(meta.getDisplayName()).append(meta.getLore());}return result.toString();}
    private static String phase(Inventory inventory)throws Exception {if(inventory==null||inventory.getHolder()==null)return "CLOSED";Field field=inventory.getHolder().getClass().getDeclaredField("phase");field.setAccessible(true);return field.get(inventory.getHolder()).toString();}
    private void open(SyntheticPlayer player,BanRecord source,Step next)throws Exception {controller.open(player.proxy,source.caseId());await(()->phase(player.top).equals("REVIEW"),100,next);}
    private InventoryClickEvent event(SyntheticPlayer player,int slot,ClickType type){return new InventoryClickEvent(player.view(),InventoryType.SlotType.CONTAINER,slot,type,InventoryAction.PICKUP_ALL,type==ClickType.NUMBER_KEY?1:-1);}
    private void click(SyntheticPlayer player,int slot,Step next){Bukkit.getPluginManager().callEvent(event(player,slot,ClickType.LEFT));later(next);}
    private void inspect(SyntheticPlayer player,Step next){click(player,28,()->inspectPages(player,next));}
    private void inspectPages(SyntheticPlayer player,Step next)throws Exception {
        if(!phase(player.top).equals("EVIDENCE"))throw new IllegalStateException("Evidence page did not open");
        player.seenEvidence.append(visible(player.top));player.evidencePagesSeen++;
        if(player.top.getItem(50).getType()==Material.ARROW)click(player,50,()->inspectPages(player,next));else click(player,49,next);
    }
    private void confirm(SyntheticPlayer player,BanRecord source,Step next)throws Exception {
        open(player,source,()->inspect(player,()->click(player,30,()->click(player,32,()->click(player,46,()->await(()->phase(player.top).equals("CONFIRM"),100,next))))));
    }
    private void outcome(SyntheticPlayer player,BanRecord source,String assertion,Step next)throws Exception {
        await(()->player.top==null,100,()->{pass(assertion,noIp(source));next.run();});
    }
    private void sourceNative(BanRecord source)throws Exception {Method method=GuardianPlugin.class.getDeclaredMethod("syncNative",BanRecord.class,String.class);method.setAccessible(true);method.invoke(plugin,source,plugin.storage().protectedIp(source.caseId()).get(3,TimeUnit.SECONDS).orElse(null));}
    private boolean nativeUuid(BanRecord record){org.bukkit.ban.ProfileBanList profiles=Bukkit.getBanList(BanList.Type.PROFILE);return profiles.isBanned(Bukkit.createPlayerProfile(record.playerId(),record.playerName()));}
    private org.bukkit.BanEntry<InetAddress> nativeIp(String address)throws Exception {org.bukkit.ban.IpBanList ips=Bukkit.getBanList(BanList.Type.IP);return ips.getBanEntry(InetAddress.getByName(address));}

    private void start()throws Exception {
        configure();controller=syntheticController();otherController=syntheticController();
        f.getLogger().info("GACTEST permanent_ip_trust_source=SYNTHETIC_DI_NOT_MICROSOFT_AUTH");
        positive=seed(1,POSITIVE_IP,true,true,"",true);sourceNative(positive);
        PermanentIpReview preview=review(positive);var evidence=plugin.storage().evidence(positive.evidenceId()).get(3,TimeUnit.SECONDS).orElseThrow();
        var immutable=PersistedBanEvidence.parse(evidence.telemetryJson(),positive.playerId());var eval=immutable.evaluate(CheckCatalog.create(),policy());
        pass("synthetic_v2_hard_proof_sufficient",eval.banRecommended()&&eval.independentFamilies()==2&&eval.totalVl()>=100&&immutable.evidence().findings().stream().allMatch(x->x.occurrences()>=6&&x.lastSeenMillis()-x.firstSeenMillis()>=3000));
        pass("candidate_not_ip_enforcement",noIp(positive)&&nativeIp(POSITIVE_IP)==null&&nativeUuid(positive));
        pass("protected_provenance_exact_binding",preview.candidate().evidenceId().equals(evidence.id())&&preview.candidate().evidenceSha256().equals(evidence.sha256())&&preview.source().equals(positive)&&preview.ipAddress().equals(POSITIVE_IP));
        pass("ordinary_records_hide_exact_ip",!positive.toString().contains(POSITIVE_IP)&&!preview.toString().contains(POSITIVE_IP)&&!preview.candidate().toString().contains(POSITIVE_IP)&&!plugin.storage().historyForPlayer(positive.playerId(),10).get(3,TimeUnit.SECONDS).toString().contains(POSITIVE_IP));
        for(Set<String> permissions:List.of(Set.of("guardianac.view"),Set.of("guardianac.view","guardianac.unban"))){SyntheticPlayer actor=new SyntheticPlayer(permissions);controller.open(actor.proxy,positive.caseId());pass(permissions.size()==1?"view_only_denied":"unban_only_denied",actor.top==null&&!actor.messages.isEmpty());}
        for(String invalid:List.of("legacy","predicate","missing-field")){int n=nextId++;BanRecord record=seed(n,"127.0.1."+n,true,true,invalid,false);pass(invalid+"_proof_fails_closed",plugin.storage().previewPermanentIp(record.caseId(),policy(),true).get(3,TimeUnit.SECONDS).isEmpty()&&noIp(record));}
        int noTrustId=nextId++;BanRecord untrusted=seed(noTrustId,"127.0.1."+noTrustId,false,true,"",false);pass("untrusted_target_fails_closed",plugin.storage().previewPermanentIp(untrusted.caseId(),policy(),true).get(3,TimeUnit.SECONDS).isEmpty()&&noIp(untrusted));
        int offlineId=nextId++;BanRecord offline=seed(offlineId,"127.0.1."+offlineId,true,false,"",false);pass("non_native_identity_fails_closed",plugin.storage().previewPermanentIp(offline.caseId(),policy(),true).get(3,TimeUnit.SECONDS).isEmpty()&&noIp(offline));
        BanRecord shared=newCandidate();String sharedIp=review(shared).ipAddress();plugin.storage().registerIp(syntheticId(500),sharedIp,false,Instant.now()).get(3,TimeUnit.SECONDS);
        pass("all_observed_accounts_count_untrusted",plugin.storage().observedAccountCount(sharedIp,Instant.now().minus(Duration.ofDays(30))).get(3,TimeUnit.SECONDS)==2&&plugin.storage().previewPermanentIp(shared.caseId(),policy(),true).get(3,TimeUnit.SECONDS).isEmpty());
        pass("legacy_arbitrary_source_not_upgradeable",plugin.storage().previewPermanentIp("GAC-11111111",policy(),true).get(3,TimeUnit.SECONDS).isEmpty());
        SyntheticPlayer ordinary=new SyntheticPlayer(Set.of("guardianac.view"));
        Field guiField=GuardianPlugin.class.getDeclaredField("gui");guiField.setAccessible(true);GuiManager gui=(GuiManager)guiField.get(plugin);
        Method detail=GuiManager.class.getDeclaredMethod("caseDetail",Player.class,BanRecord.class);detail.setAccessible(true);detail.invoke(gui,ordinary.proxy,positive);
        pass("ordinary_case_gui_hides_exact_ip",ordinary.top.getSize()==54&&!visible(ordinary.top).contains(POSITIVE_IP));ordinary.proxy.closeInventory();
        SyntheticPlayer initial=new SyntheticPlayer(ADMIN);open(initial,positive,()->click(initial,39,()->await(()->initial.top.getItem(49).getType()==Material.OBSIDIAN,100,()->{
            pass("audit_gui_hides_exact_ip",!visible(initial.top).contains(POSITIVE_IP));click(initial,49,()->inspectRequired(initial));
        })));
    }
    private void inspectRequired(SyntheticPlayer actor)throws Exception {
        pass("review_54_slots_exact_admin_ip",actor.top.getSize()==54&&visible(actor.top).contains(POSITIVE_IP));
        pass("review_binds_evidence_digest",visible(actor.top).contains(review(positive).candidate().evidenceSha256()));
        boolean protectedAll=true;
        for(int slot=0;slot<54;slot++)for(ClickType type:List.of(ClickType.SHIFT_LEFT,ClickType.NUMBER_KEY,ClickType.DOUBLE_CLICK,ClickType.DROP,ClickType.SWAP_OFFHAND)){var e=event(actor,slot,type);Bukkit.getPluginManager().callEvent(e);protectedAll&=e.isCancelled();}
        var drag=new InventoryDragEvent(actor.view(),new ItemStack(Material.STONE),new ItemStack(Material.STONE),false,Map.of(5,new ItemStack(Material.STONE),53,new ItemStack(Material.STONE)));Bukkit.getPluginManager().callEvent(drag);
        pass("all_54_slots_transfer_protected",protectedAll);pass("review_drag_protected",drag.isCancelled());
        var foreignEvent=event(actor,52,ClickType.LEFT);otherController.click(foreignEvent);otherController.inventoryClose(new InventoryCloseEvent(actor.view()));otherController.inventoryOpen(new InventoryOpenEvent(actor.view()));
        pass("separate_controllers_cannot_cross_menus",!foreignEvent.isCancelled()&&phase(actor.top).equals("REVIEW"));
        click(actor,30,()->{pass("proof_ack_disabled_before_read",actor.top.getItem(30).getType()==Material.GRAY_DYE);click(actor,46,()->{
            pass("cannot_confirm_before_acknowledgements",phase(actor.top).equals("REVIEW")&&plugin.storage().permanentIpReviewHistory(positive.caseId(),20).get(3,TimeUnit.SECONDS).isEmpty());
            inspect(actor,()->click(actor,30,()->{pass("proof_reader_preserves_full_timing_fields",actor.top.getItem(30).getType()==Material.LIME_DYE&&actor.seenEvidence.toString().contains("firstSeen")&&actor.seenEvidence.toString().contains("lastSeen")&&actor.seenEvidence.toString().contains("findingVL")&&actor.seenEvidence.toString().contains("capturedAt")&&actor.seenEvidence.toString().contains("sanctionPolicy"));pass("all_evidence_pages_traversed",actor.evidencePagesSeen>1);click(actor,46,()->{
                pass("impact_ack_separately_required",phase(actor.top).equals("REVIEW"));
                click(actor,32,()->{pass("shared_address_consequence_visible",visible(actor.top).contains("家族")||visible(actor.top).contains("family"));click(actor,46,()->await(()->phase(actor.top).equals("CONFIRM"),100,()->{
                    pass("final_54_slots_bound_exact_ip",actor.top.getSize()==54&&visible(actor.top).contains(POSITIVE_IP)&&visible(actor.top).contains(positive.caseId()));
                    actor.proxy.closeInventory();later(()->{pass("close_cancels_confirmation",noIp(positive)&&plugin.storage().permanentIpReviewHistory(positive.caseId(),20).get(3,TimeUnit.SECONDS).stream().anyMatch(a->a.event().equals("CANCEL")));negativeScenarios(0);});
                }));});
            });}));
        });});
    }
    private void negativeScenarios(int which)throws Exception {
        if(which==9){expiryScenario();return;}
        BanRecord source=newCandidate();SyntheticPlayer actor=new SyntheticPlayer(ADMIN);
        confirm(actor,source,()->{
            switch(which){
                case 0->{SyntheticPlayer wrong=new SyntheticPlayer(ADMIN);wrong.top=actor.top;var event=event(wrong,46,ClickType.LEFT);Bukkit.getPluginManager().callEvent(event);
                    later(()->{pass("wrong_actor_cannot_apply",event.isCancelled()&&noIp(source)&&phase(actor.top).equals("CONFIRM"));
                        SyntheticPlayer replacement=new SyntheticPlayer(ADMIN,actor.id);replacement.top=actor.top;controller.pulse();
                        later(()->{pass("same_uuid_new_player_session_denied",actor.top==null&&noIp(source));negativeScenarios(which+1);});});}
                case 1->{actor.proxy.openInventory(Bukkit.createInventory(null,54,"Synthetic navigation"));later(()->{pass("navigation_cancels_confirmation",noIp(source)&&plugin.storage().permanentIpReviewHistory(source.caseId(),20).get(3,TimeUnit.SECONDS).stream().anyMatch(a->a.event().equals("CANCEL")));negativeScenarios(which+1);});}
                case 2->{Bukkit.getPluginManager().callEvent(event(actor,46,ClickType.LEFT));actor.perms.remove("guardianac.admin");controller.pulse();outcome(actor,source,"permission_loss_before_action_denied",()->negativeScenarios(which+1));}
                case 3->{Bukkit.getPluginManager().callEvent(event(actor,46,ClickType.LEFT));plugin.settings().set("auto-ban",false);plugin.applySettings();controller.pulse();outcome(actor,source,"settings_change_before_action_denied",()->{plugin.settings().set("auto-ban",true);plugin.applySettings();controller.publish();negativeScenarios(which+1);});}
                case 4->{plugin.storage().registerIp(syntheticId(600),review(source).ipAddress(),false,Instant.now()).get(3,TimeUnit.SECONDS);click(actor,46,()->outcome(actor,source,"late_untrusted_second_account_denied",()->negativeScenarios(which+1)));}
                case 5->{plugin.storage().revokeCaseUuid(source.caseId(),actor.id.toString(),"Synthetic source revocation before final claim",Instant.now()).get(3,TimeUnit.SECONDS);click(actor,46,()->outcome(actor,source,"source_revoked_before_sql_claim_denied",()->negativeScenarios(which+1)));}
                case 6->click(actor,52,()->outcome(actor,source,"explicit_cancel_denied",()->negativeScenarios(which+1)));
                case 7->{controller.quit(actor.proxy);actor.online=false;actor.top=null;later(()->{pass("quit_invalidates_session",noIp(source));negativeScenarios(which+1);});}
                case 8->{controller.cancelCase(source.caseId());outcome(actor,source,"case_invalidation_denied",()->negativeScenarios(which+1));}
                default->throw new IllegalStateException("Unknown scenario");
            }
        });
    }
    private void expiryScenario()throws Exception {
        BanRecord source=newCandidate();SyntheticPlayer actor=new SyntheticPlayer(ADMIN);confirm(actor,source,()->{
            long issued=System.nanoTime();later(625,()->{controller.pulse();pass("actual_30_second_confirmation_expired",System.nanoTime()-issued>=30_000_000_000L&&actor.top==null&&noIp(source));
                pass("expiry_audit_retained",plugin.storage().permanentIpReviewHistory(source.caseId(),20).get(3,TimeUnit.SECONDS).stream().anyMatch(a->a.event().equals("CANCEL")||a.event().equals("EXPIRE")));positiveScenario();});
        });
    }
    private void positiveScenario()throws Exception {
        SyntheticPlayer actor=new SyntheticPlayer(ADMIN);confirm(actor,positive,()->{
            Inventory captured=actor.top;String bound=visible(captured);var before=plugin.storage().permanentIpReviewHistory(positive.caseId(),100).get(3,TimeUnit.SECONDS);
            pass("request_audit_actor_and_time",before.stream().anyMatch(a->a.event().equals("REQUEST")&&a.actor().equals(actor.id)&&a.recordedAt()!=null));
            pass("review_audit_hides_address",!before.toString().contains(POSITIVE_IP));
            var first=event(actor,46,ClickType.LEFT);var duplicate=event(actor,46,ClickType.LEFT);Bukkit.getPluginManager().callEvent(first);Bukkit.getPluginManager().callEvent(duplicate);
            await(()->plugin.storage().matchIp(POSITIVE_IP,Instant.now()).get(3,TimeUnit.SECONDS).size()==1&&actor.top==null&&nativeIp(POSITIVE_IP)!=null,160,()->{
                var ips=plugin.storage().matchIp(POSITIVE_IP,Instant.now()).get(3,TimeUnit.SECONDS);BanRecord applied=ips.getFirst();BanRecord unchanged=current(positive);
                pass("synthetic_gui_positive_reachable",bound.contains(POSITIVE_IP)&&first.isCancelled()&&duplicate.isCancelled()&&applied.type()==BanType.IP&&applied.permanent()&&!applied.caseId().equals(positive.caseId()));
                pass("source_uuid_record_unchanged",unchanged.equals(positive)&&unchanged.uuidActiveAt(Instant.now()));
                pass("permanent_ip_has_owned_native_entry",nativeIp(POSITIVE_IP)!=null&&nativeIp(POSITIVE_IP).getSource().equals(applied.ownershipMarker())&&nativeIp(POSITIVE_IP).getExpiration()==null&&nativeUuid(positive));
                var linked=plugin.storage().evidence(applied.evidenceId()).get(3,TimeUnit.SECONDS).orElseThrow();var json=JsonParser.parseString(linked.telemetryJson()).getAsJsonObject();
                pass("linked_proof_and_actor_retained",linked.payloadAvailable()&&json.get("sourceCaseId").getAsString().equals(positive.caseId())&&json.get("sourceEvidenceId").getAsString().equals(positive.evidenceId())&&json.get("sourceEvidenceSha256").getAsString().equals(reviewHash(positive))&&json.get("administratorId").getAsString().equals(actor.id.toString())&&json.get("approvedAtMillis").getAsLong()>0&&json.getAsJsonObject("sourceProof").get("version").getAsInt()==2);
                pass("approval_reason_records_shared_impact",applied.reason().contains("shared-address")&&applied.reason().contains("evidence")&&!applied.reason().contains(POSITIVE_IP));
                var after=plugin.storage().permanentIpReviewHistory(positive.caseId(),100).get(3,TimeUnit.SECONDS);
                pass("one_apply_audit_exact_actor_and_case",after.stream().filter(a->a.event().equals("APPLY")).count()==1&&after.stream().anyMatch(a->a.event().equals("APPLY")&&a.actor().equals(actor.id)&&applied.caseId().equals(a.appliedCaseId())));
                pass("consumed_candidate_not_reviewable",plugin.storage().previewPermanentIp(positive.caseId(),policy(),true).get(3,TimeUnit.SECONDS).isEmpty());
                actor.top=captured;Bukkit.getPluginManager().callEvent(event(actor,46,ClickType.LEFT));later(()->{
                    pass("double_click_and_replay_one_case",plugin.storage().matchIp(POSITIVE_IP,Instant.now()).get(3,TimeUnit.SECONDS).size()==1&&plugin.storage().permanentIpReviewHistory(positive.caseId(),100).get(3,TimeUnit.SECONDS).stream().filter(a->a.event().equals("APPLY")).count()==1);
                    Properties state=new Properties();state.setProperty("source",positive.caseId());state.setProperty("applied",applied.caseId());state.setProperty("uuid",positive.playerId().toString());state.setProperty("expiry",positive.expiresAt().toString());state.setProperty("actor",actor.id.toString());state.setProperty("sourceSha",reviewHash(positive));
                    Files.createDirectories(f.getDataFolder().toPath());try(var output=Files.newOutputStream(f.getDataFolder().toPath().resolve(STATE))){state.store(output,"Explicitly synthetic isolated runtime fixture");}
                    SyntheticPlayer auditor=new SyntheticPlayer(ADMIN);controller.openAudit(auditor.proxy,applied.caseId());
                    await(()->visible(auditor.top).contains("APPLY"),100,()->{
                        pass("consumed_applied_case_audit_gui_accessible",auditor.top.getSize()==54&&visible(auditor.top).contains(positive.caseId())&&visible(auditor.top).contains(applied.caseId()));
                        pass("consumed_audit_gui_has_no_exact_ip",!visible(auditor.top).contains(POSITIVE_IP));auditor.proxy.closeInventory();done();
                    });
                });
            });
        });
    }
    private String reviewHash(BanRecord source)throws Exception{return plugin.storage().evidence(source.evidenceId()).get(3,TimeUnit.SECONDS).orElseThrow().sha256();}

    private void offlineActor()throws Exception {
        Player actor=Bukkit.getPlayerExact("GacLifeFixture");pass("default_offline_real_actor_present",actor!=null&&actor.isOnline());if(actor==null)return;
        configure();var attachment=actor.addAttachment(f,"guardianac.admin",true);attachment.setPermission("guardianac.view",true);
        try {
            Method nativeCheck=GuardianPlugin.class.getDeclaredMethod("nativeIpReviewEnvironment");nativeCheck.setAccessible(true);
            boolean nativeEnvironment=(Boolean)nativeCheck.invoke(plugin);pass("default_offline_environment_refused",!Bukkit.getOnlineMode()&&!nativeEnvironment);
            String address=actor.getAddress().getAddress().getHostAddress();var association=plugin.storage().ipAssociation(actor.getUniqueId(),address,Instant.now().minus(Duration.ofDays(30))).get(3,TimeUnit.SECONDS);
            pass("default_offline_tcp_observation_untrusted",association.observedAccounts()>=1&&!association.targetTrusted());
            Inventory original=actor.getOpenInventory().getTopInventory();plugin.permanentIps().open(actor,"GAC-72000001");
            pass("default_offline_production_controller_denied",actor.getOpenInventory().getTopInventory()==original);
        }finally{actor.removeAttachment(attachment);restoreSettings();f.save();}
    }
    private void restartAndRevoke()throws Exception {
        Properties state=new Properties();try(var input=Files.newInputStream(f.getDataFolder().toPath().resolve(STATE))){state.load(input);}
        BanRecord source=plugin.storage().findCase(state.getProperty("source")).get(3,TimeUnit.SECONDS).orElseThrow();BanRecord applied=plugin.storage().findCase(state.getProperty("applied")).get(3,TimeUnit.SECONDS).orElseThrow();
        pass("restart_permanent_ip_durable",applied.type()==BanType.IP&&applied.permanent()&&applied.ipActiveAt(Instant.now())&&nativeIp(POSITIVE_IP)!=null&&nativeIp(POSITIVE_IP).getSource().equals(applied.ownershipMarker()));
        pass("restart_source_uuid_expiry_unchanged",source.type()==BanType.UUID&&source.uuidActiveAt(Instant.now())&&source.expiresAt().toString().equals(state.getProperty("expiry"))&&nativeUuid(source));
        pass("restart_linked_evidence_and_audit_durable",reviewHash(source).equals(state.getProperty("sourceSha"))&&plugin.storage().evidence(applied.evidenceId()).get(3,TimeUnit.SECONDS).orElseThrow().payloadAvailable()&&plugin.storage().permanentIpReviewHistory(source.caseId(),100).get(3,TimeUnit.SECONDS).stream().filter(a->a.event().equals("APPLY")).count()==1);
        SyntheticPlayer unbanner=new SyntheticPlayer(Set.of("guardianac.view","guardianac.unban"));plugin.requestUnban(unbanner.proxy,applied,"IP");
        Object holder=unbanner.top.getHolder();Field token=holder.getClass().getDeclaredField("confirmationToken");token.setAccessible(true);String value=(String)token.get(holder);
        plugin.confirm(unbanner.proxy,value,"Synthetic reviewed permanent IP-only revocation");
        await(()->!current(applied).ipActiveAt(Instant.now())&&nativeIp(POSITIVE_IP)==null,100,()->{
            pass("restart_revoke_only_ip_preserves_source_uuid",current(source).equals(source)&&nativeUuid(source)&&plugin.storage().matchIp(POSITIVE_IP,Instant.now()).get(3,TimeUnit.SECONDS).isEmpty());
            pass("restart_ip_revoke_audit_exact_scope_actor",plugin.storage().unbanHistory(applied.caseId()).get(3,TimeUnit.SECONDS).stream().anyMatch(a->a.scope().equals("IP")&&a.actor().equals(unbanner.id.toString())));
            pass("restart_revoke_retains_both_proofs",plugin.storage().evidence(source.evidenceId()).get(3,TimeUnit.SECONDS).orElseThrow().payloadAvailable()&&plugin.storage().evidence(applied.evidenceId()).get(3,TimeUnit.SECONDS).orElseThrow().payloadAvailable());
            finished=true;f.save();f.getLogger().info("GACTEST PERMANENT IP RESTART DONE");
        });
    }
    private final class SyntheticPlayer extends GuardianFixture.FakePlayer {
        boolean online=true;final StringBuilder seenEvidence=new StringBuilder();int evidencePagesSeen;
        SyntheticPlayer(Set<String> permissions){this(permissions,UUID.randomUUID());}
        SyntheticPlayer(Set<String> permissions,UUID id){super(permissions);this.id=id;sessions.put(id,proxy);}
        @Override public Object invoke(Object proxy,Method method,Object[] args){
            if(method.getName().equals("isOnline"))return online;
            if((method.getName().equals("openInventory")||method.getName().equals("closeInventory"))&&top!=null){Bukkit.getPluginManager().callEvent(new InventoryCloseEvent(view()));if(method.getName().equals("closeInventory")){top=null;return null;}}
            Object result=super.invoke(proxy,method,args);
            if(method.getName().equals("openInventory")&&top!=null){InventoryOpenEvent opened=new InventoryOpenEvent(view());Bukkit.getPluginManager().callEvent(opened);if(opened.isCancelled())top=null;}
            return result;
        }
    }
}
