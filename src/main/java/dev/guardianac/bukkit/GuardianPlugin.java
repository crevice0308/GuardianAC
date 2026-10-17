/* SPDX-License-Identifier: GPL-3.0-only */
package dev.guardianac.bukkit;

import dev.guardianac.checks.CheckCatalog;
import dev.guardianac.core.*;
import dev.guardianac.packet.*;
import dev.guardianac.security.*;
import dev.guardianac.storage.*;
import dev.guardianac.xray.*;
import org.bukkit.*;
import org.bukkit.ban.*;
import org.bukkit.command.*;
import org.bukkit.entity.Player;
import org.bukkit.event.*;
import org.bukkit.event.player.*;
import org.bukkit.plugin.java.JavaPlugin;
import java.io.IOException;
import java.net.InetAddress;
import java.nio.file.*;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicLong;
import static dev.guardianac.bukkit.RuntimeMessages.Key.*;

/** Bukkit orchestration. Network/storage workers never mutate Bukkit objects. */
public final class GuardianPlugin extends JavaPlugin implements Listener,CommandExecutor,TabCompleter {
    public record UnbanAction(List<String> cases,String scope,String protectedIp,String label,Set<UUID> affectedPlayers) {
        public UnbanAction { cases=List.copyOf(cases);affectedPlayers=Set.copyOf(affectedPlayers); }
        public UnbanAction(List<String> cases,String scope,String protectedIp,String label) {this(cases,scope,protectedIp,label,Set.of());}
        /** The preview binds actor resets independently from its unchanged exact-case SQL scope. */
        public static UnbanAction preview(List<BanRecord> records,String scope,String label) {
            return new UnbanAction(records.stream().map(BanRecord::caseId).toList(),scope,null,label,
                records.stream().map(BanRecord::playerId).collect(java.util.stream.Collectors.toUnmodifiableSet()));
        }
        public void cancelUnclaimed(AutomaticBanGate gate,java.util.function.Consumer<UUID> reset) {
            affectedPlayers.forEach(gate::cancelPending);affectedPlayers.forEach(reset);
        }
        @Override public String toString() { return "UnbanAction["+label+"]"; }
    }
    private GuardianSettings settings; private DetectionEngine engine; private SqliteBanRepository storage;
    private PacketAdapter packets; private EventTelemetry telemetry; private XrayService xray; private GuiManager gui;
    private ReplayBuffer replay; private DiscordNotifier discord; private PermanentIpController permanentIps;
    private NativeBanReconciler nativeBans;
    private static final String NATIVE_DELIVERY="guardianac-native-enforcement-session";
    private final BoundedStorageExecutor exports=new BoundedStorageExecutor(8);
    private final BoundedRateLimiter<String> exportsLimit=new BoundedRateLimiter<>(128,2,.1);
    private final BoundedRateLimiter<String> adminOperations=new BoundedRateLimiter<>(128,6,2);
    private final ConfirmationManager<UnbanAction> confirmations=new ConfirmationManager<>();
    private final BoundedRateLimiter<String> alerts=new BoundedRateLimiter<>(4096,2,.05);
    private final BoundedRateLimiter<String> connections=new BoundedRateLimiter<>(8192,20,2);
    private final BoundedRateLimiter<String> globalConnections=new BoundedRateLimiter<>(1,200,40);
    private final Set<UUID> pendingBans=new HashSet<>();
    private final Map<UUID,LoginAssociation> loginAssociations=new HashMap<>();
    record LoginAssociation(Player player, String address, boolean authenticatedNativeIdentity, CompletableFuture<Void> persisted) {
        boolean eligible(Player current,String expectedAddress) {
            return current!=null && current==player && Objects.equals(address,expectedAddress) && authenticatedNativeIdentity
                && persisted.isDone() && !persisted.isCompletedExceptionally() && !persisted.isCancelled();
        }
    }
    private final AutomaticBanGate automaticBans=new AutomaticBanGate(10_000);
    private AutomaticBanGate.Policy enforcementPolicy;
    private boolean supportedRuntime;
    private final ArrayDeque<Finding> recentFindings=new ArrayDeque<>();
    private final Map<UUID,Long> lastEvidence=new HashMap<>();
    private final AtomicLong ingressDenied=new AtomicLong();
    private volatile boolean ready; private volatile long tick; private volatile double tps=20;
    private long lastCancelledPackets,lastMalformedPackets;
    private long previousTickNanos; private double tickMs=50,engineMs; private long findingsCount,banCount;

    @Override public void onLoad() {
        supportedRuntime=RuntimeTarget.supportsBukkitVersion(Bukkit.getBukkitVersion());
        packets=new PacketAdapter(this,this::accept);
        if(supportedRuntime)packets.load();
    }
    @Override public void onEnable() {
        try {
            settings=new GuardianSettings(this);settings.beforePublish(()->{automaticBans.invalidate();if(permanentIps!=null)permanentIps.invalidate();});
            engine=new DetectionEngine(CheckCatalog.create(),settings.limit("max-players",2000,16,10000));
            storage=SqliteBanRepository.open(getDataFolder().toPath(),new StorageConfig(1024,
                settings.limit("evidence-mib",256,16,2048)*1024L*1024,1024*1024,
                Duration.ofDays(settings.limit("evidence-days",90,1,3650)),7));
            replay=new ReplayBuffer(settings.limit("replay-total-mib",32,1,256)*1024*1024,
                settings.limit("replay-per-player-kib",64,4,512)*1024);
            discord=new DiscordNotifier();try { discord.load(getDataFolder().toPath()); }
            catch(IOException e) { getLogger().warning("Discord configuration rejected; notification stays disabled."); }
            telemetry=new EventTelemetry(this,this::accept,()->tick,()->tps,settings.limit("max-players",2000,16,10000));
            telemetry.packetGround((id,now)->packets.movement(id).filter(m->packets.exactProtocol(id)&&!m.protocolTranslated()&&now>=m.atMillis()&&now-m.atMillis()<250).map(PacketMovement::onGround));
            telemetry.translatedPlayers(id->!packets.exactProtocol(id));
            getServer().getPluginManager().registerEvents(this,this);
            gui=new GuiManager(this);getServer().getPluginManager().registerEvents(gui,this);
            permanentIps=new PermanentIpController(this);getServer().getPluginManager().registerEvents(permanentIps,this);
            xray=new XrayService(this,packets,this::accept);
            Objects.requireNonNull(getCommand("guardian")).setExecutor(this);
            Objects.requireNonNull(getCommand("guardian")).setTabCompleter(this);
            if(supportedRuntime){packets.init();supportedRuntime=packets.supportedRuntime();}
            applySettings();
            if(supportedRuntime){getServer().getPluginManager().registerEvents(telemetry,this);telemetry.start();xray.start();}
            else getLogger().warning("Unsupported Minecraft runtime: only 26.3 / protocol 777 is eligible. Automatic enforcement, telemetry and packet/Xray decoding are disabled; stored bans and administration remain available.");
            nativeBans=new NativeBanReconciler(new NativeBanReconciler.Store() {
                public long revision(){return storage.mutationRevision();}
                public CompletableFuture<BanReconciliationSnapshot> read(String caseId){return storage.reconciliationState(caseId);}
                public CompletableFuture<List<String>> page(String after,int limit){return storage.retainedCaseIdsAfter(after,limit);}
            },this::applyNativeState,this::enforceCurrentOnlineBan,this::systemError);
            ready=true;
            Bukkit.getScheduler().runTaskTimer(this,this::pulse,1,1);
            Bukkit.getScheduler().runTaskTimer(this,()->storage.pruneEvidence(Instant.now()).exceptionally(e->{systemError("Evidence maintenance failed");return null;}),1200,72_000);
            Bukkit.getScheduler().runTaskTimer(this,()->storage.expirePermanentIpReviews().exceptionally(e->{systemError("Permanent IP review expiry audit failed");return 0;}),1200,1200);
            Bukkit.getScheduler().runTaskTimer(this,()->storage.backup().exceptionally(e->{systemError("Database backup failed");return null;}),600,1_728_000);
            syncExisting();
            getLogger().info("GuardianAC internal development build enabled: "+engine.checks().size()+" observable rules; unverified release gates remain. Embedded PacketEvents metrics are removed; update checks are disabled.");
        } catch(Exception e) {
            ready=false;getLogger().severe("GuardianAC failed initialization safely: "+e.getClass().getSimpleName());
            getServer().getPluginManager().disablePlugin(this);
        }
    }
    @Override public void onDisable() {
        ready=false;automaticBans.invalidate();if(permanentIps!=null)permanentIps.invalidate();Bukkit.getScheduler().cancelTasks(this);
        if(nativeBans!=null)nativeBans.close();
        for(Player player:Bukkit.getOnlinePlayers())if(nativeDeliveryClaimed(player))player.removeMetadata(NATIVE_DELIVERY,this);
        if(permanentIps!=null)permanentIps.close();
        if(gui!=null)gui.closeAll();if(telemetry!=null)telemetry.close();if(xray!=null)xray.close();
        if(packets!=null)packets.close();if(discord!=null)discord.close();exports.close();if(storage!=null)storage.close();
        connections.clear();globalConnections.clear();alerts.clear();exportsLimit.clear();adminOperations.clear();
        if(engine!=null)engine.clear();if(replay!=null)replay.clear();confirmations.clear();pendingBans.clear();lastEvidence.clear();loginAssociations.clear();
    }
    private void pulse() {
        long now=System.nanoTime();if(previousTickNanos!=0)tickMs=tickMs*.95+Math.min(5000,(now-previousTickNanos)/1_000_000.0)*.05;
        previousTickNanos=now;tps=Math.min(20,1000/Math.max(50,tickMs));tick++;
        if(permanentIps!=null)permanentIps.pulse();
        if(nativeBans!=null)nativeBans.tick(tick);
        for(Player p:Bukkit.getOnlinePlayers()) {
            automaticBans.observeBypass(p.getUniqueId(),p.hasPermission("guardianac.bypass"));
            if(!supportedRuntime)continue;
            Set<String> flags=new HashSet<>(telemetry.context(p));
            flags.addAll(p.hasPermission("guardianac.bypass")?Set.of("bypass"):Set.of());
            packets.publishContext(p,new PacketContext(p.getWorld().getUID(),System.currentTimeMillis(),tick,tps,p.getPing(),
                flags,telemetry.containerOpen(p),System.currentTimeMillis()-telemetry.sinceCloseMillis(p),p.getWorld().getMinHeight(),p.getWorld().getMaxHeight()));
        }
        if(supportedRuntime){packets.drain(settings.limit("packets-per-tick",1024,64,4096));xray.tick(tick,tps);}
        if(tick%100==0)engine.automaticTuning(settings.enabled("automatic-tuning"),tps);
        if(tick%100==0) {
            long cancelled=packets.cancelledPackets(),malformed=packets.malformedPackets();
            if((cancelled-lastCancelledPackets>=100||malformed-lastMalformedPackets>=10)&&alerts.allow("packet-defense",System.currentTimeMillis())) {
                String message=message(PACKET_DEFENSE,cancelled,malformed);
                for(Player staff:Bukkit.getOnlinePlayers())if(staff.hasPermission("guardianac.alerts"))staff.sendMessage(message);
                discord.notify(message);
                lastCancelledPackets=cancelled;lastMalformedPackets=malformed;
            }
        }
    }
    public void applySettings() {
        Set<String> enabledChecks=new HashSet<>();for(Check check:engine.checks())if(settings.checkEnabled(check.id()))enabledChecks.add(check.id());
        enforcementPolicy=new AutomaticBanGate.Policy(settings.enabled("auto-ban"),supportedRuntime,settings.preset(),enabledChecks,
            settings.banType()+":"+settings.hours(false)+":"+settings.hours(true)+":"+settings.enabled("allow-permanent-ip"));
        automaticBans.publish(enforcementPolicy);
        if(permanentIps!=null)permanentIps.publish();
        engine.preset(enforcementPolicy.preset());engine.enabled(enforcementPolicy.enabledChecks()::contains);
        engine.automaticTuning(settings.enabled("automatic-tuning"),tps);
        if(discord!=null)discord.enabled(settings.enabled("discord-enabled")&&discord.configured());
        if(xray!=null)xray.configure(XraySettings.defaults().withEnabled(settings.enabled("xray.enabled")).withConcealCaves(settings.enabled("xray.hide-caves")));
    }
    public void accept(Sample sample) {
        if(!ready||!supportedRuntime||!Bukkit.isPrimaryThread())return;
        Player player=Bukkit.getPlayer(sample.playerId());if(player==null)return;
        boolean bypass=player.hasPermission("guardianac.bypass");automaticBans.observeBypass(player.getUniqueId(),bypass);
        if(bypass)return;
        long start=System.nanoTime();replay.add(sample);DetectionEngine.Evaluation result=engine.accept(sample);
        engineMs=engineMs*.95+(System.nanoTime()-start)/1_000_000.0*.05;
        for(Finding finding:result.findings()) {
            findingsCount++;recentFindings.addFirst(finding);while(recentFindings.size()>500)recentFindings.removeLast();
            if(alerts.allow("flag:"+player.getUniqueId(),sample.timeMillis())) {
                String message=message(FINDING_ALERT,player.getName(),finding.checkId(),Math.round(finding.violationLevel()));
                for(Player staff:Bukkit.getOnlinePlayers())if(staff.hasPermission("guardianac.alerts"))staff.sendMessage(message);
                if(finding.hardInvariant())discord.notify(message(SEVERE_ANOMALY,player.getName(),finding.checkId()));
            }
        }
        if(!result.findings().isEmpty()&&sample.timeMillis()-lastEvidence.getOrDefault(sample.playerId(),0L)>30_000) {
            lastEvidence.put(sample.playerId(),sample.timeMillis());String caseId=SqliteBanRepository.newCaseId();
            storage.saveEvidence(new EvidenceInput(sample.playerId(),caseId,Instant.now(),evidenceJson(player,result))).exceptionally(e->{systemError("Evidence persistence failed; sanctions remain guarded");return null;});
        }
        if(result.banRecommended()&&settings.enabled("auto-ban")&&!pendingBans.contains(player.getUniqueId()))automaticBan(player,result);
    }
    private String evidenceJson(Player player,DetectionEngine.Evaluation result) {
        StringBuilder b=new StringBuilder("{\"version\":1,\"player\":").append(ReplayBuffer.quote(player.getUniqueId().toString()))
            .append(",\"families\":").append(result.independentFamilies()).append(",\"vl\":").append(result.totalVl()).append(",\"findings\":[");
        boolean first=true;for(Finding f:engine.current(player.getUniqueId())) { if(!first)b.append(',');first=false;
            b.append("{\"check\":").append(ReplayBuffer.quote(f.checkId())).append(",\"family\":").append(ReplayBuffer.quote(f.family()))
                .append(",\"hard\":").append(f.hardInvariant()).append(",\"confidence\":").append(f.confidence()).append(",\"count\":").append(f.occurrences())
                .append(",\"observation\":").append(ReplayBuffer.sampleJson(f.sample())).append('}'); }
        return b.append("],\"replay\":").append(replay.json(player.getUniqueId())).append('}').toString();
    }
    private void automaticBan(Player player,DetectionEngine.Evaluation result) {
        UUID id=player.getUniqueId();
        BanEvidence decision=engine.evidence(id,System.currentTimeMillis());
        if(!decision.evaluate(engine.checks(),enforcementPolicy,System.currentTimeMillis()).banRecommended())return;
        Optional<AutomaticBanGate.Ticket> issued=automaticBans.issue(id,player.hasPermission("guardianac.bypass"));
        if(issued.isEmpty())return;
        AutomaticBanGate.Ticket ticket=issued.get();
        String name=player.getName(),ip=player.getAddress()==null?null:player.getAddress().getAddress().getHostAddress();
        LoginAssociation login=loginAssociations.get(id);
        AutomaticBanGate.Policy sourcePolicy=enforcementPolicy;
        String caseId=SqliteBanRepository.newCaseId(),json;
        try {json=PersistedBanEvidence.encode(id,decision,sourcePolicy,replay.json(id));}
        catch(RuntimeException invalidEvidence) {automaticBans.finish(ticket);systemError("Immutable evidence serialization failed; no sanction applied");return;}
        pendingBans.add(id);
        BanType requested=settings.banType();int uuidHours=settings.hours(false),ipHours=settings.hours(true);
        Instant requestedAt=Instant.now(),uuidExpiry=uuidHours==0?null:requestedAt.plus(Duration.ofHours(uuidHours));
        storage.saveEvidence(new EvidenceInput(id,caseId,Instant.now(),json)).thenCompose(evidence->
            (ip==null?CompletableFuture.completedFuture(IpBanPolicy.Association.unknown()):storage.ipAssociation(id,ip,requestedAt.minus(Duration.ofDays(30))))
            .thenCompose(association->onMain(()->{
                // Bukkit permissions are read only here/on pulse, never on the storage worker.
                Player current=Bukkit.getPlayer(id);
                if(current!=null)automaticBans.observeBypass(id,current.hasPermission("guardianac.bypass"));
                DetectionEngine.Evaluation validated=decision.evaluate(engine.checks(),enforcementPolicy,System.currentTimeMillis());
                if(!ready||!automaticBans.authorize(ticket,validated.banRecommended()))throw new CancellationException("Automatic ban authorization expired or changed");
                // Only this successfully persisted native online-mode session may qualify. Proxy/offline identities,
                // missing/failed joins, disconnects and a different session during async persistence stay UUID-only.
                boolean nativeIdentity=current==player&&current!=null&&nativeOnlineIdentity(current)&&login!=null
                    &&loginAssociations.get(id)==login&&login.eligible(current,ip);
                boolean eligibleIpIdentity=ip!=null&&requested!=BanType.UUID&&IpBanPolicy.evaluate(new IpBanPolicy.Context(association,nativeIdentity,validated.independentFamilies(),0,.995,true,true,false)).allowed();
                boolean reviewCandidate=eligibleIpIdentity&&ipHours==0&&settings.enabled("allow-permanent-ip");
                boolean temporaryIp=eligibleIpIdentity&&ipHours>0;
                // Temporary IP always includes its UUID sanction. Permanent mode grants no IP restriction:
                // only a provenance-bound encrypted candidate is saved beside the independently justified UUID.
                BanType selected=temporaryIp?BanType.DUAL:BanType.UUID;
                Instant expiry=temporaryIp?requestedAt.plus(Duration.ofHours(ipHours)):uuidExpiry;
                BanRequest request=new BanRequest(id,name,temporaryIp||reviewCandidate?ip:null,selected,"Unfair Advantage",expiry,validated.totalVl(),caseId,evidence.id(),"GuardianAC automatic evidence policy");
                return new AutomaticChoice(request,nativeIdentity,reviewCandidate,enforcementPolicy);
            }).thenCompose(choice->choice.reviewCandidate()
                ?storage.createAutomaticReviewCandidate(choice.request(),requestedAt.minus(Duration.ofDays(30)),choice.nativeIdentity(),choice.policy(),ticket::tryCommit)
                :storage.createAutomaticBan(choice.request(),requestedAt.minus(Duration.ofDays(30)),uuidExpiry,choice.nativeIdentity(),ticket::tryCommit))))
            .whenComplete((record,error)->{
                automaticBans.finish(ticket);
                main(()->{
                    pendingBans.remove(id);
                    if(error!=null){if(!cancelledAuthorization(error))systemError("Ban persistence failed; no punishment applied");return;}
                    // Do not leave the captured evidence hot while bounded reconciliation waits.
                    if(Bukkit.getPlayer(id)==player)engine.reset(id);
                    // A commit is durable, but its scopes may already have been revoked while this
                    // callback waited. Re-read on the storage worker before any external effect.
                    reconcileCommitted(record.caseId());
                });
            });
    }
    private record AutomaticChoice(BanRequest request,boolean nativeIdentity,boolean reviewCandidate,AutomaticBanGate.Policy policy) {}
    private static boolean cancelledAuthorization(Throwable error) {
        while((error instanceof CompletionException||error instanceof ExecutionException)&&error.getCause()!=null)error=error.getCause();
        return error instanceof CancellationException;
    }
    private void announce(BanRecord record) {
        if(settings.enabled("broadcast")&&alerts.allow("broadcast",System.currentTimeMillis()))
            Bukkit.broadcastMessage(message(record.permanent()?BAN_BROADCAST_PERMANENT:BAN_BROADCAST_TEMPORARY,record.playerName()));
        for(Player staff:Bukkit.getOnlinePlayers())if(staff.hasPermission("guardianac.alerts"))staff.sendMessage(message(BAN_STAFF,record.caseId(),record.type(),record.playerName()));
        discord.notify(message(BAN_DISCORD,record.playerName(),record.type(),record.caseId(),
            record.permanent()?message(BAN_DURATION_PERMANENT):message(BAN_DURATION_UNTIL,record.expiresAt())));
    }
    public String banScreen(BanRecord record) {
        return RuntimeMessages.banScreen(messageLanguage(),record.permanent(),record.expiresAt(),record.caseId());
    }
    @EventHandler(priority=EventPriority.HIGHEST)
    public void login(AsyncPlayerPreLoginEvent event) {
        if(event.getLoginResult()!=AsyncPlayerPreLoginEvent.Result.ALLOWED)return;
        if(!ready){event.disallow(AsyncPlayerPreLoginEvent.Result.KICK_OTHER,message(LOGIN_INITIALIZING));return;}
        long now=System.currentTimeMillis();String ip=event.getAddress().getHostAddress();
        // Keys are ephemeral in-memory addresses, discarded on disable; never stored in logs.
        if(!globalConnections.allow("all",now)||!connections.allow(ip,now)) {
            ingressDenied.incrementAndGet();event.disallow(AsyncPlayerPreLoginEvent.Result.KICK_OTHER,message(LOGIN_RATE_LIMIT));
            if(alerts.allow("connection-alert",now)&&discord!=null)discord.notify(message(CONNECTION_FLOOD));return;
        }
        try {
            var records=storage.activeForPlayer(event.getUniqueId(),Instant.now()).get(500,TimeUnit.MILLISECONDS);
            if(records.isEmpty()) {
                records=storage.matchIp(ip,Instant.now()).get(500,TimeUnit.MILLISECONDS);
                if(!records.isEmpty())recordRestrictedIpAttempt(event.getUniqueId(),records.getFirst().caseId());
            }
            if(!records.isEmpty())event.disallow(AsyncPlayerPreLoginEvent.Result.KICK_BANNED,banScreen(records.getFirst()));
        } catch(Exception e) {event.disallow(AsyncPlayerPreLoginEvent.Result.KICK_OTHER,message(LOGIN_BUSY));}
    }
    private void recordRestrictedIpAttempt(UUID attemptedPlayer,String matchedCase) {
        // A different UUID at a restricted address is only an advisory; NAT/VPN use does not prove evasion.
        if(!alerts.allow("ip-match:"+matchedCase,System.currentTimeMillis()))return;
        String json="{\"version\":1,\"type\":\"restricted-ip-login-advisory\",\"player\":"+ReplayBuffer.quote(attemptedPlayer.toString())
            +",\"matchedCase\":"+ReplayBuffer.quote(matchedCase)+",\"provenEvasion\":false,\"replay\":[]}";
        storage.saveEvidence(new EvidenceInput(attemptedPlayer,SqliteBanRepository.newCaseId(),Instant.now(),json))
            .exceptionally(error->{systemError("Restricted-address advisory could not be persisted");return null;});
        main(()->{
            String message=message(RESTRICTED_IP_ADVISORY,matchedCase);
            for(Player staff:Bukkit.getOnlinePlayers())if(staff.hasPermission("guardianac.alerts"))staff.sendMessage(message);
            if(discord!=null)discord.notify(message);
        });
    }
    @EventHandler public void join(PlayerJoinEvent event) {
        Player p=event.getPlayer();p.removeMetadata(NATIVE_DELIVERY,this);automaticBans.observeBypass(p.getUniqueId(),p.hasPermission("guardianac.bypass"));if(storage==null||p.getAddress()==null)return;
        // Trust is native online-mode authentication only. All joined UUIDs still count for shared-IP protection.
        String ip=p.getAddress().getAddress().getHostAddress();boolean authenticated=nativeOnlineIdentity(p);
        CompletableFuture<Void> persisted=storage.registerIp(p.getUniqueId(),ip,authenticated,Instant.now());
        loginAssociations.put(p.getUniqueId(),new LoginAssociation(p,ip,authenticated,persisted));
        persisted.exceptionally(e->{systemError("IP association persistence failed; automatic IP enforcement unavailable for this session");return null;});
    }
    /** Called only on the server thread. The actor must be native authenticated, never an offline/proxy identity. */
    private boolean nativeOnlineIdentity(Player player) {
        return player.getUniqueId().version()==4&&nativeIpReviewEnvironment();
    }
    /** A banned target need not remain online for review; supported native authentication is still required. */
    boolean nativeIpReviewEnvironment() {
        try {
            if(!Bukkit.isPrimaryThread()||!Bukkit.getOnlineMode()
                ||getServer().spigot().getConfig().getBoolean("settings.bungeecord",true))return false;
            // Paper/Purpur's public 26.3 API exposes all supported proxy modes. Keep the Spigot artifact portable.
            java.lang.reflect.Method getter;
            try {getter=getServer().getClass().getMethod("getServerConfig");}
            catch(NoSuchMethodException spigotWithoutPaperApi) {return true;}
            Object config=getter.invoke(getServer());
            return Boolean.FALSE.equals(getter.getReturnType().getMethod("isProxyEnabled").invoke(config));
        } catch(ReflectiveOperationException|RuntimeException|LinkageError unsupportedIdentityApi) {
            return false; // Unavailable/changed identity APIs are unknown, never trusted.
        }
    }
    @EventHandler public void quit(PlayerQuitEvent event) {
        if(permanentIps!=null)permanentIps.quit(event.getPlayer());
        UUID id=event.getPlayer().getUniqueId();
        if(nativeDeliveryClaimed(event.getPlayer()))event.getPlayer().removeMetadata(NATIVE_DELIVERY,this);
        LoginAssociation login=loginAssociations.get(id);
        if(login!=null&&login.player()==event.getPlayer())loginAssociations.remove(id,login);
        automaticBans.observeBypass(id,event.getPlayer().hasPermission("guardianac.bypass"));
        engine.reset(id);replay.forget(id);packets.forget(event.getPlayer());telemetry.forget(event.getPlayer());
        lastEvidence.remove(id);confirmations.forget(id.toString());
    }
    @EventHandler public void worldChange(PlayerChangedWorldEvent event) {
        UUID id=event.getPlayer().getUniqueId();automaticBans.observeBypass(id,event.getPlayer().hasPermission("guardianac.bypass"));engine.reset(id);replay.forget(id);packets.forget(event.getPlayer());lastEvidence.remove(id);
    }
    /** Incremental keyset sweep includes inactive retained cases, including crash-time orphan entries. */
    private void syncExisting() { if(nativeBans!=null)nativeBans.requestSweep(); }
    private void applyNativeState(BanReconciliationSnapshot snapshot) {
        if(!Bukkit.isPrimaryThread())throw new IllegalStateException("Native reconciliation must run on the server thread");
        if(!ready||snapshot.record().isEmpty())return;
        BanRecord owner=snapshot.record().get();String ip=snapshot.protectedIp().orElse(null);Instant now=Instant.now();
        if(!NativeBanDecision.mayNotify(owner,now)) {
            Player online=Bukkit.getPlayer(owner.playerId());
            if(online!=null&&online.getMetadata(NATIVE_DELIVERY).stream().anyMatch(value->value.getOwningPlugin()==this
                &&value.value() instanceof NativeBanDelivery delivery&&delivery.belongsTo(online)&&delivery.caseId().equals(owner.caseId())))
                online.removeMetadata(NATIVE_DELIVERY,this);
        }
        // Every removal needs the entire retained case/token marker AND an inactive exact scope.
        // Foreign entries and unknown/changed tokens are never pardoned, even if the prefix matches.
        if(storage.mutationRevision()!=snapshot.revision())return;
        if(!owner.uuidActiveAt(now))removeOwnedNative(owner,null,"UUID");
        if(storage.mutationRevision()!=snapshot.revision())return;
        if(!owner.ipActiveAt(now))removeOwnedNative(owner,ip,"IP");
        if(storage.mutationRevision()!=snapshot.revision())return;
        // A surviving case can reinstate its restriction after an exact stale owner was removed.
        // The existing native ownership policy prevents replacing a different case or foreign ban.
        snapshot.uuidRestriction().ifPresent(record->syncNative(record,null,true,false));
        if(storage.mutationRevision()!=snapshot.revision())return;
        snapshot.ipRestriction().ifPresent(record->syncNative(record,ip,false,true));
    }
    private void reconcileCommitted(String caseId) {
        if(!ready||nativeBans==null)return;
        nativeBans.request(caseId,this::finishCurrentCommittedBan).exceptionally(error->{
            if(ready)systemError("Committed ban awaits native reconciliation; review the case and native lists");return null;
        });
    }
    /** The final fence is best-effort cross-system reconciliation, not an atomic SQLite/Bukkit commit. */
    private boolean finishCurrentCommittedBan(BanReconciliationSnapshot snapshot) {
        return enforceCurrentBan(snapshot,true);
    }
    /** Repeated retained-case scans recover online enforcement even when an urgent queue/retry was exhausted.
     * Historical announcements are never replayed. The delivery claim lives only on the target session. */
    private boolean enforceCurrentOnlineBan(BanReconciliationSnapshot snapshot) {
        return enforceCurrentBan(snapshot,false);
    }
    private boolean nativeDeliveryClaimed(Player player) {
        return player.getMetadata(NATIVE_DELIVERY).stream().anyMatch(value->value.getOwningPlugin()==this
            &&value.value() instanceof NativeBanDelivery delivery&&delivery.belongsTo(player));
    }
    private boolean enforceCurrentBan(BanReconciliationSnapshot snapshot,boolean notify) {
        if(!ready)return true;
        if(storage.mutationRevision()!=snapshot.revision())return false;
        if(snapshot.record().isEmpty())return true;
        BanRecord current=snapshot.record().get();Instant now=Instant.now();
        if(!NativeBanDecision.mayNotify(current,now))return true;
        Player online=Bukkit.getPlayer(current.playerId());
        boolean sameAddress=online!=null&&online.isOnline()&&online.getAddress()!=null&&snapshot.protectedIp().isPresent()
            &&IpPrivacy.normalize(online.getAddress().getAddress().getHostAddress()).equals(IpPrivacy.normalize(snapshot.protectedIp().get()));
        if(storage.mutationRevision()!=snapshot.revision())return false;
        // Never mass-disconnect other accounts sharing the reviewed target's address.
        if(online!=null&&online.isOnline()&&!nativeDeliveryClaimed(online)
            &&NativeBanDecision.mayDisconnect(current,online.getUniqueId(),sameAddress,Instant.now())) {
            // Main-thread session claim prevents duplicate disconnect attempts across queued jobs/sweeps.
            online.setMetadata(NATIVE_DELIVERY,new org.bukkit.metadata.FixedMetadataValue(this,new NativeBanDelivery(online,current.caseId())));
            online.kickPlayer(banScreen(current));
        }
        if(storage.mutationRevision()!=snapshot.revision()) {
            // A kick already delivered cannot be undone. Repair native state without replaying it.
            nativeBans.request(current.caseId(),null);return true;
        }
        if(notify){banCount++;announce(current);engine.reset(current.playerId());}return true;
    }
    private void syncNative(BanRecord r,String ip) {syncNative(r,ip,true,true);}
    private void syncNative(BanRecord r,String ip,boolean uuidScope,boolean ipScope) {
        if(!settings.enabled("native-ban-sync"))return;
        Date expiry=r.expiresAt()==null?null:Date.from(r.expiresAt());
        if(uuidScope&&r.uuidActiveAt(Instant.now())) {
            ProfileBanList list=Bukkit.getBanList(BanList.Type.PROFILE);var profile=Bukkit.createPlayerProfile(r.playerId(),r.playerName());
            var existing=list.getBanEntry(profile);
            if(NativeBanOwnership.mayReplace(r.ownershipMarker(),existing!=null,existing==null?null:existing.getSource())
                &&(existing==null||!Objects.equals(existing.getExpiration(),expiry)||!Objects.equals(existing.getReason(),banScreen(r)))) {
                var added=list.addBan(profile,banScreen(r),expiry,r.ownershipMarker());
                if(added==null||!NativeBanOwnership.mayRemove(r.ownershipMarker(),added.getSource()))throw new IllegalStateException("Native profile ban was not installed");
            }
        }
        if(ipScope&&ip!=null&&r.ipActiveAt(Instant.now()))try {
            IpBanList list=Bukkit.getBanList(BanList.Type.IP);InetAddress address=InetAddress.getByName(ip);var existing=list.getBanEntry(address);
            if(NativeBanOwnership.mayReplace(r.ownershipMarker(),existing!=null,existing==null?null:existing.getSource())
                &&(existing==null||!Objects.equals(existing.getExpiration(),expiry)||!Objects.equals(existing.getReason(),banScreen(r)))) {
                var added=list.addBan(address,banScreen(r),expiry,r.ownershipMarker());
                if(added==null||!NativeBanOwnership.mayRemove(r.ownershipMarker(),added.getSource()))throw new IllegalStateException("Native IP ban was not installed");
            }
        }catch(java.net.UnknownHostException ignored){throw new IllegalStateException("Protected IP synchronization failed");}
    }
    public void cancelConfirmation(UUID owner,String token) {confirmations.cancel(token,owner.toString());}
    public void requestUnban(Player player,BanRecord record,String scope) {
        if(!player.hasPermission("guardianac.unban")){denied(player);return;}
        if(!Set.of("UUID","IP","ALL").contains(scope))throw new IllegalArgumentException("Invalid unban scope");
        var pending=confirmations.request(player.getUniqueId().toString(),UnbanAction.preview(List.of(record),scope,record.playerName()+" / "+record.caseId()),System.nanoTime()/1_000_000);
        gui.confirm(player,pending.token(),pending.action());
    }
    public void confirm(CommandSender sender,String token,String reason) {
        if(!sender.hasPermission("guardianac.unban")){denied(sender);return;}
        Optional<UnbanAction> operation=confirmations.consume(token,actor(sender),System.nanoTime()/1_000_000);
        if(operation.isEmpty()){sender.sendMessage(message(CONFIRM_EXPIRED));return;}
        UnbanAction action=operation.get();
        // Confirmation is the reset boundary: an old unclaimed automatic decision may not slip
        // through while revocation waits in the storage queue. Already claimed/new cases remain separate.
        action.cancelUnclaimed(automaticBans,id->{engine.reset(id);telemetry.reset(id);});
        if(permanentIps!=null)for(String id:action.cases())permanentIps.cancelCase(id);
        String who=actor(sender);String why=reason.isBlank()?"Administrator confirmed unban":reason.substring(0,Math.min(200,reason.length()));
        CompletableFuture<List<BanRecord>> revoked=CompletableFuture.completedFuture(new ArrayList<>());
        java.util.concurrent.atomic.AtomicInteger failures=new java.util.concurrent.atomic.AtomicInteger();
        for(String id:action.cases())revoked=revoked.thenCompose(records->{
            CompletableFuture<List<BanRecord>> next=switch(action.scope()) {
                case "UUID" -> storage.revokeCaseUuid(id,who,why,Instant.now());
                case "IP" -> storage.revokeCaseIp(id,who,why,Instant.now());
                default -> storage.revokeCase(id,who,why,Instant.now());
            };
            return next.handle((changed,error)->{if(error!=null)failures.incrementAndGet();else records.addAll(changed);return records;});
        });
        revoked.whenComplete((records,error)->{
            if(error!=null){main(()->sender.sendMessage(message(UNBAN_FAILED)));return;}
            CompletableFuture<Void> synchronization=CompletableFuture.completedFuture(null);
            java.util.concurrent.atomic.AtomicInteger nativeFailures=new java.util.concurrent.atomic.AtomicInteger();
            for(BanRecord record:records)synchronization=synchronization.thenCompose(ignored->
                reconcileUnban(record,action.scope()).exceptionally(failure->{nativeFailures.incrementAndGet();return null;}));
            synchronization.whenComplete((ignored,syncError)->main(()->{
                sender.sendMessage(message(UNBAN_RECORDED,records.size()));
                if(failures.get()>0)sender.sendMessage(message(UNBAN_PARTIAL,failures.get()));
                if(nativeFailures.get()>0||syncError!=null)sender.sendMessage(message(UNBAN_NATIVE_INCOMPLETE));
            }));
        });
    }
    private CompletableFuture<Void> reconcileUnban(BanRecord record,String scope) {
        return onMain(()->{
            if(nativeBans==null)throw new IllegalStateException("Native reconciliation is unavailable");
            // Both scopes are re-read, so partial unbans preserve the other current scope.
            return nativeBans.request(record.caseId(),null);
        }).thenCompose(result->result);
    }
    private void removeOwnedNative(BanRecord old,String ip,String scope) {
        if(!scope.equals("IP")) {
            ProfileBanList list=Bukkit.getBanList(BanList.Type.PROFILE);var profile=Bukkit.createPlayerProfile(old.playerId(),old.playerName());var entry=list.getBanEntry(profile);
            if(entry!=null&&NativeBanDecision.cleanInactive(old,true,entry.getSource(),Instant.now())) {
                list.pardon(profile);var remaining=list.getBanEntry(profile);
                if(remaining!=null&&NativeBanOwnership.mayRemove(old.ownershipMarker(),remaining.getSource()))throw new IllegalStateException("Native profile ban was not removed");
            }
        }
        if(!scope.equals("UUID")&&ip!=null)try {
            IpBanList list=Bukkit.getBanList(BanList.Type.IP);InetAddress address=InetAddress.getByName(ip);var entry=list.getBanEntry(address);
            if(entry!=null&&NativeBanDecision.cleanInactive(old,false,entry.getSource(),Instant.now())) {
                list.pardon(address);var remaining=list.getBanEntry(address);
                if(remaining!=null&&NativeBanOwnership.mayRemove(old.ownershipMarker(),remaining.getSource()))throw new IllegalStateException("Native IP ban was not removed");
            }
        }catch(java.net.UnknownHostException invalid){throw new IllegalStateException("Native IP unban synchronization failed");}
    }
    @Override public boolean onCommand(CommandSender sender,Command command,String label,String[] args) {
        if(!sender.hasPermission("guardianac.view")){denied(sender);return true;}
        if(args.length==0||args[0].equalsIgnoreCase("gui")) {if(sender instanceof Player p)gui.main(p);else sender.sendMessage(message(CONSOLE_USAGE));return true;}
        String sub=args[0].toLowerCase(Locale.ROOT);
        if(!sub.equals("status")&&!adminOperations.allow(actor(sender),System.currentTimeMillis())){sender.sendMessage(message(MANAGEMENT_WAIT));return true;}
        if(sub.equals("status")){sender.sendMessage(status());return true;}
        if(sub.equals("confirm")&&args.length>=2){confirm(sender,args[1],args.length>2?String.join(" ",Arrays.copyOfRange(args,2,args.length)):"");return true;}
        if(sub.equals("reload")) {
            if(!sender.hasPermission("guardianac.admin")){denied(sender);return true;}
            try{settings.reload();applySettings();discord.load(getDataFolder().toPath());discord.enabled(settings.enabled("discord-enabled")&&discord.configured());sender.sendMessage(message(CONFIG_LOADED));}catch(IOException e){sender.sendMessage(message(CONFIG_REJECTED));}return true;
        }
        if(Set.of("unban","unbanall","unbanip").contains(sub)&&args.length==2) {
            if(!sender.hasPermission("guardianac.unban")){denied(sender);return true;}
            String input=args[1];CompletableFuture<List<BanRecord>> lookup;
            if(sub.equals("unbanip")) {
                try {IpPrivacy.normalize(input);}catch(IllegalArgumentException e){sender.sendMessage(message(INVALID_IP));return true;}
                lookup=storage.matchIp(input,Instant.now());
            } else if(input.matches("GAC-[A-Fa-f0-9]{8,32}"))lookup=storage.findCase(input.toUpperCase(Locale.ROOT)).thenApply(o->o.map(List::of).orElse(List.of()));
            else {if(!input.matches("[A-Za-z0-9_]{1,64}")){sender.sendMessage(message(INVALID_PLAYER));return true;}lookup=storage.findCasesByPlayerName(input,1000);}
            lookup.whenComplete((records,error)->main(()->{
                if(!sender.hasPermission("guardianac.view")||!sender.hasPermission("guardianac.unban")||(sender instanceof Player p&&!p.isOnline()))return;
                if(error!=null){sender.sendMessage(message(BAN_LOOKUP_FAILED));return;}
                var active=records.stream().filter(r->r.activeAt(Instant.now())).toList();if(active.isEmpty()){sender.sendMessage(message(NO_ACTIVE_BAN));return;}
                String scope=sub.equals("unbanip")?"IP":sub.equals("unbanall")||input.toUpperCase(Locale.ROOT).startsWith("GAC-")?"ALL":"UUID";
                String display=scope.equals("IP")?message(PROTECTED_IP_BANS):active.getFirst().playerName();
                var pending=confirmations.request(actor(sender),UnbanAction.preview(active,scope,display),System.nanoTime()/1_000_000);
                if(sender instanceof Player p)gui.confirm(p,pending.token(),pending.action());
                else sender.sendMessage(message(CONFIRM_PROMPT,pending.token()));
            }));return true;
        }
        if(sub.equals("replay")&&args.length==2) {exportEvidence(sender,args[1]);return true;}
        sender.sendMessage(message(COMMAND_USAGE));return true;
    }
    private void exportEvidence(CommandSender sender,String caseId) {
        if(!sender.hasPermission("guardianac.admin")){denied(sender);return;}
        if(!caseId.matches("GAC-[A-Fa-f0-9]{8,32}")){sender.sendMessage(message(INVALID_CASE));return;}
        if(!exportsLimit.allow(actor(sender),System.currentTimeMillis())){sender.sendMessage(message(EXPORT_WAIT));return;}
        Path directory=getDataFolder().toPath().resolve("exports");String filename=caseId.toUpperCase(Locale.ROOT)+".json";
        storage.evidenceForCase(caseId.toUpperCase(Locale.ROOT)).whenComplete((evidence,error)->main(()->{
            if(!sender.hasPermission("guardianac.admin")||(sender instanceof Player p&&!p.isOnline()))return;
            if(error!=null||evidence.isEmpty()||!evidence.get().payloadAvailable()){sender.sendMessage(message(EVIDENCE_UNAVAILABLE));return;}
            String json=evidence.get().telemetryJson();
            exports.submit(()->PrivateFiles.replace(directory,filename,json.getBytes(java.nio.charset.StandardCharsets.UTF_8),1024*1024,128))
                .whenComplete((file,writeError)->main(()->{
                    if(!sender.hasPermission("guardianac.admin")||(sender instanceof Player p&&!p.isOnline()))return;
                    sender.sendMessage(writeError==null?message(EVIDENCE_EXPORTED,file.getFileName()):message(EVIDENCE_EXPORT_FAILED));
                }));
        }));
    }
    @Override public List<String> onTabComplete(CommandSender sender,Command command,String alias,String[] args) {
        if(!sender.hasPermission("guardianac.view"))return List.of();
        if(args.length==1)return List.of("gui","status","unban","unbanall","unbanip","confirm","replay","reload").stream().filter(s->s.startsWith(args[0].toLowerCase(Locale.ROOT))).toList();return List.of();
    }
    public String status() {return RuntimeMessages.status(messageLanguage(),"GuardianAC "+getDescription().getVersion()+" | runtime "+(supportedRuntime?"26.3 target accepted":"UNSUPPORTED: telemetry/auto-ban/Xray OFF")+" | "+engine.checks().size()+" rules | "+settings.preset()+" | TPS "+String.format(Locale.ROOT,"%.1f",tps)+" | engine avg "+String.format(Locale.ROOT,"%.3f",engineMs)+"ms | evidence flags "+findingsCount+" | announced bans "+banCount+" | packet drops "+packets.droppedSamples()+" | context capture failures "+packets.contextCaptureFailures()+" | queued "+packets.queuedSamples()+" | replay "+replay.bytes()+" bytes | denied joins "+ingressDenied.get(),supportedRuntime);}
    /** A single language read uses the safely published immutable configuration snapshot. */
    private String messageLanguage(){GuardianSettings current=settings;return current==null?"ja":current.language();}
    private String message(RuntimeMessages.Key key,Object... values){return RuntimeMessages.text(messageLanguage(),key,values);}
    private static String actor(CommandSender sender) {return sender instanceof Player p?p.getUniqueId().toString():"CONSOLE";}
    public void denied(CommandSender sender){sender.sendMessage(message(PERMISSION_DENIED));}
    public void main(Runnable action){if(!isEnabled())return;if(Bukkit.isPrimaryThread())action.run();else Bukkit.getScheduler().runTask(this,action);}
    private <T> CompletableFuture<T> onMain(Callable<T> action) {
        CompletableFuture<T> result=new CompletableFuture<>();
        if(!isEnabled()){result.completeExceptionally(new IllegalStateException("Plugin is disabled"));return result;}
        Runnable task=()->{
            if(!isEnabled()){result.completeExceptionally(new IllegalStateException("Plugin is disabled"));return;}
            try{result.complete(action.call());}catch(Exception error){result.completeExceptionally(error);}
        };
        try {if(Bukkit.isPrimaryThread())task.run();else Bukkit.getScheduler().runTask(this,task);}
        catch(RuntimeException error){result.completeExceptionally(error);}
        return result;
    }
    private void systemError(String message){if(!alerts.allow("system:"+message,System.currentTimeMillis()))return;getLogger().warning(message);if(discord!=null)discord.notify(RuntimeMessages.systemError(messageLanguage(),message));}
    AutomaticBanGate.Policy enforcementPolicy(){return enforcementPolicy;}
    public PermanentIpController permanentIps(){return permanentIps;}
    /** Called only after the reviewed IP-only case committed; no plaintext address is broadcast or logged. */
    void finishReviewedPermanentIp(BanRecord record,String protectedIp) {
        if(!Bukkit.isPrimaryThread())throw new IllegalStateException("Review completion must run on the server thread");
        if(record.type()!=BanType.IP||!record.permanent())throw new IllegalArgumentException("Expected reviewed permanent IP case");
        // Discard the commit-time plaintext and record state; obtain a fresh coherent snapshot.
        reconcileCommitted(record.caseId());
    }
    public GuardianSettings settings(){return settings;} public DetectionEngine engine(){return engine;}
    public SqliteBanRepository storage(){return storage;} public List<Finding> recentFindings(){return List.copyOf(recentFindings);}
    public DiscordNotifier discord(){return discord;} public XrayService xray(){return xray;} public ReplayBuffer replay(){return replay;}
}
