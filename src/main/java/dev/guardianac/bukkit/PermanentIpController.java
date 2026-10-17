/* SPDX-License-Identifier: GPL-3.0-only */
package dev.guardianac.bukkit;

import com.google.gson.GsonBuilder;
import com.google.gson.JsonElement;
import com.google.gson.JsonParser;
import dev.guardianac.core.AutomaticBanGate;
import dev.guardianac.security.BoundedRateLimiter;
import dev.guardianac.security.PermanentIpApprovalGate;
import dev.guardianac.security.SessionActionGate;
import dev.guardianac.storage.*;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.*;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.time.Instant;
import java.util.*;
import java.util.function.Consumer;
import java.util.function.BooleanSupplier;
import java.util.function.Function;
import java.util.function.LongSupplier;

/** Main-thread-only review UI; the one-shot commit guard never reads Bukkit on the SQL worker. */
public final class PermanentIpController implements Listener, AutoCloseable {
    private static final int CAPACITY=8, PAGE_SIZE=36, LINES_PER_ITEM=6;
    private static final int MAX_EVIDENCE_BYTES=1024*1024, MAX_EVIDENCE_LINES=131_072;
    private static final long LOADING_AUDIT_NANOS=600_000_000_000L, CONFIRM_NANOS=30_000_000_000L;
    static final String ATTESTATION="Reviewed the exact bound evidence and acknowledged permanent shared-address impact; IP match does not establish a person's identity.";
    private final GuardianPlugin plugin;
    private final BooleanSupplier nativeEnvironment;
    private final Function<UUID,Player> actorLookup;
    private final LongSupplier nanoTime;
    private final Clock clock;
    private final PermanentIpApprovalGate approvals;
    private final BoundedRateLimiter<UUID> clicks=new BoundedRateLimiter<>(128,4,4);
    private final Map<UUID,Review> active=new HashMap<>();
    private boolean closed;
    private enum Phase { LOADING, REVIEW, EVIDENCE, SAVING, CONFIRM, APPLYING, AUDIT }

    private final class Review implements InventoryHolder {
        final Player player; final UUID actor; final String sourceCase;
        final Inventory inventory; final SessionActionGate actionsGate=new SessionActionGate();
        final Map<Integer,Consumer<Player>> actions=new HashMap<>();
        final BitSet seenPages=new BitSet(); long phaseNanos=nanoTime.getAsLong();
        Phase phase=Phase.LOADING; PermanentIpReview value; List<String> evidence=List.of();
        int evidencePage; boolean proofAcknowledged,impactAcknowledged,auditOnly;
        PermanentIpApprovalGate.Ticket ticket; long ticketNanos;
        Review(Player player,String sourceCase) {
            this.player=player;actor=player.getUniqueId();this.sourceCase=sourceCase;
            inventory=Bukkit.createInventory(this,54,"§8GuardianAC §7· "+t("永久IP審査","Permanent IP review"));
        }
        private PermanentIpController owner(){return PermanentIpController.this;}
        @Override public Inventory getInventory(){return inventory;}
        @Override public String toString(){return "PermanentIpReviewMenu[sourceCase="+sourceCase+"]";}
    }
    public PermanentIpController(GuardianPlugin plugin){
        this(plugin,plugin::nativeIpReviewEnvironment,Bukkit::getPlayer,System::nanoTime,Clock.systemUTC());
    }
    /** Package-private deterministic fixture seam; never exposed as a command or configuration bypass. */
    PermanentIpController(GuardianPlugin plugin,BooleanSupplier nativeEnvironment,
                          Function<UUID,Player> actorLookup,LongSupplier nanoTime,Clock clock) {
        this.plugin=Objects.requireNonNull(plugin);this.nativeEnvironment=Objects.requireNonNull(nativeEnvironment);
        this.actorLookup=Objects.requireNonNull(actorLookup);this.nanoTime=Objects.requireNonNull(nanoTime);
        this.clock=Objects.requireNonNull(clock);this.approvals=new PermanentIpApprovalGate(CAPACITY,nanoTime,clock);
    }
    private String t(String ja,String en){return plugin.settings().text(ja,en);}
    private boolean authorized(Player player) {
        return !closed&&player.isOnline()&&actorLookup.apply(player.getUniqueId())==player
            &&player.hasPermission("guardianac.admin")&&player.hasPermission("guardianac.view");
    }
    private boolean current(Review review) {
        return active.get(review.actor)==review&&authorized(review.player)
            &&review.player.getOpenInventory().getTopInventory()==review.inventory;
    }
    private boolean permitted() {
        AutomaticBanGate.Policy policy=plugin.enforcementPolicy();
        return policy!=null&&policy.permits()&&nativeEnvironment.getAsBoolean()
            &&plugin.settings().enabled("allow-permanent-ip")&&plugin.settings().hours(true)==0
            &&plugin.settings().banType()!=BanType.UUID;
    }
    static String policyKey(AutomaticBanGate.Policy policy) {
        return policy.enabled()+":"+policy.supportedRuntime()+":"+policy.preset()+":"+policy.sanctionPolicy()
            +":"+String.join(",",new TreeSet<>(policy.enabledChecks()));
    }
    /** Always invalidate first, including before changed settings become visible. */
    public void invalidate() {
        approvals.invalidate();
        // Settings are normally main-thread operations; no Bukkit access is needed to revoke a guard.
        if(Bukkit.isPrimaryThread())cancelAll("POLICY_CHANGED");
        else plugin.main(()->cancelAll("POLICY_CHANGED"));
    }
    public void publish() {
        cancelAll("POLICY_CHANGED");
        AutomaticBanGate.Policy policy=plugin.enforcementPolicy();
        approvals.publish(policy==null?"unavailable":policyKey(policy),!closed&&permitted());
    }
    public void open(Player player,String sourceCase) {
        if(!authorized(player)){plugin.denied(player);return;}
        Review previous=active.get(player.getUniqueId());if(previous!=null)cancel(previous,"CLOSED",false);
        if(!permitted()) {player.sendMessage(t("この環境・設定では永久IP審査を開始できません。","Permanent IP review is unavailable for this environment or policy."));return;}
        if(active.size()>=CAPACITY){player.sendMessage(t("審査画面が混み合っています。後で再試行してください。","Review capacity reached; try again later."));return;}
        Review review=new Review(player,sourceCase);active.put(review.actor,review);renderBusy(review,t("候補と証拠を確認中","Validating candidate and evidence"));
        player.openInventory(review.inventory);
        AutomaticBanGate.Policy policy=plugin.enforcementPolicy();boolean nativeSupported=nativeEnvironment.getAsBoolean();
        plugin.storage().previewPermanentIp(sourceCase,policy,nativeSupported).thenCompose(value->{
            if(value.isEmpty())return java.util.concurrent.CompletableFuture.<Loaded>completedFuture(null);
            PermanentIpReview candidate=value.get();
            return plugin.storage().evidence(candidate.candidate().evidenceId()).thenApply(evidence->
                new Loaded(candidate,evidenceLines(evidence.orElseThrow(()->new IllegalArgumentException("Evidence unavailable")),candidate.candidate())));
        }).whenComplete((loaded,error)->plugin.main(()->{
            if(!current(review)||review.phase!=Phase.LOADING)return;
            if(error!=null||loaded==null){
                fail(review,displayLimit(error)?t("証拠が審査画面の上限（1 MiB・131072行）を超えています。適用していません。","Evidence exceeds the review display limit (1 MiB / 131072 lines); no restriction was applied."):
                    t("審査可能な候補がありません。期限・証拠・本人性・設定を再確認してください。","No eligible candidate. Check expiry, evidence, trusted identity and policy."));return;
            }
            review.value=loaded.review();review.evidence=loaded.lines();renderReview(review);
        }));
    }
    /** Read-only audit stays accessible after a candidate is consumed, revoked or expires. */
    public void openAudit(Player player,String sourceCase) {
        if(!authorized(player)){plugin.denied(player);return;}
        Review previous=active.get(player.getUniqueId());if(previous!=null)cancel(previous,"CLOSED",false);
        if(active.size()>=CAPACITY){player.sendMessage(t("審査画面が混み合っています。","Review capacity reached."));return;}
        Review review=new Review(player,sourceCase);review.auditOnly=true;active.put(review.actor,review);
        renderBusy(review,t("監査履歴を取得中","Loading audit history"));player.openInventory(review.inventory);loadAudit(review);
    }
    private record Loaded(PermanentIpReview review,List<String> lines) {
        @Override public String toString(){return "LoadedPermanentIpReview[address=[protected]]";}
    }
    /** Complete, bounded rendering of the bound JSON; unsupported/oversized evidence fails closed. */
    static List<String> evidenceLines(EvidenceRecord evidence,PermanentIpCandidate candidate) {
        if(!evidence.payloadAvailable()||!evidence.id().equals(candidate.evidenceId())
            ||!evidence.caseId().equals(candidate.sourceCaseId())||!evidence.playerId().equals(candidate.playerId())
            ||!evidence.sha256().equals(candidate.evidenceSha256()))throw new IllegalArgumentException("Evidence binding changed");
        byte[] bytes=evidence.telemetryJson().getBytes(StandardCharsets.UTF_8);
        if(bytes.length>MAX_EVIDENCE_BYTES)throw new DisplayLimitException();
        try {
            if(!HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes)).equals(candidate.evidenceSha256()))
                throw new IllegalArgumentException("Evidence digest changed");
        }catch(NoSuchAlgorithmException impossible){throw new IllegalStateException("SHA-256 unavailable",impossible);}
        JsonElement parsed=JsonParser.parseString(evidence.telemetryJson());
        if(!parsed.isJsonObject())throw new IllegalArgumentException("Unsupported evidence structure");
        checkDepth(parsed,0);
        String pretty=new GsonBuilder().setPrettyPrinting().disableHtmlEscaping().create().toJson(parsed)
            .replace("§","\\u00a7");
        List<String> lines=new ArrayList<>();
        for(String line:pretty.split("\n",-1)) {
            if(line.isEmpty())lines.add("");
            else for(int from=0;from<line.length();) {
                int end=Math.min(from+96,line.length());
                if(end<line.length()&&Character.isHighSurrogate(line.charAt(end-1))&&Character.isLowSurrogate(line.charAt(end)))end--;
                lines.add(line.substring(from,end));from=end;
            }
            if(lines.size()>MAX_EVIDENCE_LINES)throw new DisplayLimitException();
        }
        return List.copyOf(lines);
    }
    private static final class DisplayLimitException extends IllegalArgumentException {
        private static final long serialVersionUID=1L;
        DisplayLimitException(){super("Evidence exceeds review display limit");}
    }
    private static boolean displayLimit(Throwable error) {
        while(error!=null&&error.getCause()!=null&&(error instanceof java.util.concurrent.CompletionException
            ||error instanceof java.util.concurrent.ExecutionException))error=error.getCause();
        return error instanceof DisplayLimitException;
    }
    private static void checkDepth(JsonElement value,int depth) {
        if(depth>32)throw new IllegalArgumentException("Unsupported evidence structure");
        if(value.isJsonObject())for(var entry:value.getAsJsonObject().entrySet())checkDepth(entry.getValue(),depth+1);
        else if(value.isJsonArray())for(var entry:value.getAsJsonArray())checkDepth(entry,depth+1);
    }
    private void phase(Review review,Phase next){review.phase=next;review.phaseNanos=nanoTime.getAsLong();}
    private void fill(Review review) {
        review.actions.clear();ItemStack filler=item(Material.BLACK_STAINED_GLASS_PANE," ",List.of());
        for(int slot=0;slot<54;slot++)review.inventory.setItem(slot,filler);
        button(review,52,Material.BARRIER,t("キャンセルして閉じる","Cancel and close"),List.of(t("未適用の確認を無効にします。","Invalidates any uncommitted approval.")),p->cancel(review,"CANCELLED",true));
    }
    private void renderBusy(Review review,String label) {
        fill(review);button(review,22,Material.CLOCK,label,List.of(t("画面を閉じると未適用の操作を中止します。","Closing cancels any uncommitted operation.")),null);
    }
    private void renderReview(Review review) {
        phase(review,Phase.REVIEW);fill(review);
        BanRecord source=review.value.source();PermanentIpCandidate candidate=review.value.candidate();
        button(review,11,Material.PAPER,t("元UUIDケース","Source UUID case"),List.of(source.caseId(),source.playerName(),source.playerId().toString(),
            t("UUID期限は変更しません: ","UUID expiry remains: ")+(source.permanent()?t("永久","Permanent"):source.expiresAt().toString())),null);
        button(review,15,Material.ENDER_EYE,t("管理者限定: 正確な対象IP","Administrator only: exact target IP"),List.of(review.value.ipAddress(),
            t("このアドレスへの永久制限を別ケースとして作成。","Creates a separate permanent restriction on this address."),
            t("共有IPから同一人物とは断定できません。","A shared IP does not establish the same person.")),null);
        button(review,22,Material.WRITTEN_BOOK,t("固定された証拠","Bound evidence"),List.of(candidate.evidenceId(),"SHA-256:",candidate.evidenceSha256(),
            t("候補期限: ","Candidate expires: ")+candidate.reviewExpiresAt(),t("信頼済み観測: ","Trusted observation: ")+candidate.identityObservedAt()),null);
        button(review,28,Material.SPYGLASS,t("証拠の全ページを読む","Inspect all evidence pages"),List.of(
            t("ページ数: ","Pages: ")+evidencePages(review.evidence),t("表示済み: ","Viewed: ")+review.seenPages.cardinality(),
            t("保存済みJSONの全項目を表示します。","Displays all fields of the saved JSON.")),p->renderEvidence(review,0));
        boolean allSeen=review.seenPages.cardinality()==evidencePages(review.evidence);
        button(review,30,review.proofAcknowledged?Material.LIME_DYE:Material.GRAY_DYE,t("証拠確認の明示同意","Explicit evidence acknowledgement"),evidenceAcknowledgement(review.value,allSeen,review.proofAcknowledged,plugin.settings().language()),allSeen?p->{review.proofAcknowledged=!review.proofAcknowledged;renderReview(review);}:null);
        button(review,32,review.impactAcknowledged?Material.LIME_DYE:Material.GRAY_DYE,t("永久・共有IP影響への同意","Acknowledge permanent shared-IP impact"),impactLore(review.value,review.impactAcknowledged,plugin.settings().language()),p->{review.impactAcknowledged=!review.impactAcknowledged;renderReview(review);});
        button(review,39,Material.BOOK,t("永久IP審査監査","Permanent IP review audit"),List.of(t("申請・取消・適用。IPは表示しません。","Requests, cancellations and results; no addresses.")),p->loadAudit(review));
        boolean ready=allSeen&&review.proofAcknowledged&&review.impactAcknowledged;
        button(review,46,ready?Material.YELLOW_CONCRETE:Material.GRAY_CONCRETE,t("30秒の最終確認へ","Continue to 30-second confirmation"),List.of(
            t("証拠確認と共有IP影響の両方に同意が必要。","Both evidence and shared-address acknowledgements are required."),
            t("監査申請の保存後に確認を有効化します。","Confirmation is enabled only after the request is saved.")),ready?p->beginConfirmation(review):null);
    }
    static List<String> evidenceAcknowledgement(PermanentIpReview review,boolean allSeen,boolean acknowledged,String language) {
        boolean ja=!"en".equals(language);
        return List.of((ja?"状態: ":"State: ")+(acknowledged?"ON":"OFF"),review.candidate().evidenceId(),review.candidate().evidenceSha256(),
            allSeen?(ja?"クリック: この固定証拠を確認したと記録。":"Click to attest that you reviewed this exact evidence."):
                (ja?"先に証拠の全ページを開いてください。":"Open every evidence page first."));
    }
    static List<String> impactLore(PermanentIpReview review,boolean acknowledged,String language) {
        boolean ja=!"en".equals(language);
        return List.of((ja?"状態: ":"State: ")+(acknowledged?"ON":"OFF"),
            ja?"このIPの全アカウントを無期限に拒否します。":"Indefinitely denies all accounts using this IP.",
            ja?"家族・同居人・共有回線の他人にも影響します。":"May affect family, housemates and unrelated shared-network users.",
            ja?"同じIPでも同一人物とは限りません。":"The same IP does not mean the same person.",
            (ja?"元UUIDケースの期限は変更しません: ":"Source UUID expiry remains unchanged: ")+(review.source().expiresAt()==null?(ja?"永久":"Permanent"):review.source().expiresAt()),
            ja?"別の永久IP専用ケースを作成。後でIPだけ解除可能。":"Creates a separate permanent IP-only case; its IP restriction can later be removed.");
    }
    static int evidencePages(List<String> lines){return Math.max(1,(lines.size()+PAGE_SIZE*LINES_PER_ITEM-1)/(PAGE_SIZE*LINES_PER_ITEM));}
    private void renderEvidence(Review review,int page) {
        int total=evidencePages(review.evidence);if(page<0||page>=total)return;
        phase(review,Phase.EVIDENCE);review.evidencePage=page;review.seenPages.set(page);fill(review);
        button(review,4,Material.WRITTEN_BOOK,review.value.candidate().evidenceId(),List.of(review.value.candidate().evidenceSha256(),(page+1)+" / "+total),null);
        int start=page*PAGE_SIZE*LINES_PER_ITEM;
        for(int i=0;i<PAGE_SIZE;i++) {
            int from=start+i*LINES_PER_ITEM;if(from>=review.evidence.size())break;
            int end=Math.min(from+LINES_PER_ITEM,review.evidence.size());
            button(review,9+i,Material.PAPER,t("証拠行 ","Evidence lines ")+(from+1)+"–"+end,review.evidence.subList(from,end),null);
        }
        if(page>0)button(review,45,Material.ARROW,t("前へ","Previous"),List.of(),p->renderEvidence(review,page-1));
        if(page+1<total)button(review,50,Material.ARROW,t("次へ","Next"),List.of(),p->renderEvidence(review,page+1));
        button(review,49,Material.OBSIDIAN,t("審査に戻る","Return to review"),List.of(),p->renderReview(review));
    }
    private void beginConfirmation(Review review) {
        if(!current(review)||review.phase!=Phase.REVIEW||!review.proofAcknowledged||!review.impactAcknowledged
            ||review.seenPages.cardinality()!=evidencePages(review.evidence)||!permitted())return;
        Optional<PermanentIpApprovalGate.Ticket> issued=approvals.issue(review.actor,review.player,review.value.candidate(),authorized(review.player));
        if(issued.isEmpty()){fail(review,t("確認を発行できません。候補を開き直してください。","Cannot issue confirmation; reopen the candidate."));return;}
        review.ticket=issued.get();review.ticketNanos=nanoTime.getAsLong();phase(review,Phase.SAVING);renderBusy(review,t("監査申請を保存中","Saving review request"));
        plugin.storage().openPermanentIpReview(review.ticket.token(),review.actor,review.ticket.candidate()).whenComplete((ignored,error)->plugin.main(()->{
            if(!current(review)||review.phase!=Phase.SAVING)return;
            if(error!=null){fail(review,t("監査申請を保存できませんでした。適用していません。","Review request could not be saved; no restriction was applied."));return;}
            if(expired(review)){cancel(review,"EXPIRED",true);return;}
            renderConfirmation(review);
        }));
    }
    private void renderConfirmation(Review review) {
        phase(review,Phase.CONFIRM);fill(review);
        button(review,13,Material.ENDER_EYE,t("管理者限定: 最終対象IP","Administrator only: final target IP"),List.of(review.value.ipAddress(),review.sourceCase,review.value.candidate().evidenceId(),review.value.candidate().evidenceSha256()),null);
        button(review,22,Material.WRITTEN_BOOK,t("永久IP専用ケースを作成","Create permanent IP-only case"),impactLore(review.value,true,plugin.settings().language()),null);
        button(review,46,Material.RED_CONCRETE,t("確認して永久IP制限を適用","Confirm permanent IP restriction"),List.of(
            t("この管理者・接続・ケース・IP・証拠に固定。","Bound to this administrator, session, case, address and evidence."),
            t("確認は発行から30秒、1回限り。","Confirmation expires 30 seconds after issue and works once."),
            t("固定の証拠確認・共有IP影響への同意理由を記録。","Records the fixed evidence-review and shared-address acknowledgement.")),p->apply(review));
    }
    private void apply(Review review) {
        if(!current(review)||review.phase!=Phase.CONFIRM||!review.proofAcknowledged||!review.impactAcknowledged)return;
        var authorized=approvals.authorize(review.ticket.token(),review.actor,review.player,authorized(review.player)&&permitted());
        if(authorized.isEmpty()){fail(review,t("確認が失効しました。適用していません。","Confirmation expired or changed; no restriction was applied."));return;}
        PermanentIpApprovalGate.Ticket ticket=authorized.get();
        phase(review,Phase.APPLYING);renderBusy(review,t("最終条件を再検証して適用中","Revalidating and applying"));
        plugin.storage().applyReviewedPermanentIp(ticket.token(),review.actor,ticket.candidate(),ATTESTATION,
            plugin.enforcementPolicy(),nativeEnvironment.getAsBoolean(),ticket::tryCommit).whenComplete((record,error)->plugin.main(()->{
                if(error!=null) {
                    if(current(review))fail(review,ticket.claimed()?t("保存結果を確認できません。ケース・監査履歴を確認してください。","The persistence result could not be verified. Check case and audit history."):
                        t("条件が変わったか保存に失敗しました。適用していません。","Conditions changed or persistence failed; no restriction was applied."));
                    else cancel(review,"FAILED",false);
                    return;
                }
                // A successful guard claim is the authorization boundary. Later closing cannot undo it.
                plugin.finishReviewedPermanentIp(record,review.value.ipAddress());
                boolean stillVisible=current(review);active.remove(review.actor,review);review.actionsGate.close();
                if(stillVisible){review.player.closeInventory();review.player.sendMessage(t("永久IP専用ケースを作成しました: ","Created permanent IP-only case: ")+record.caseId());}
            }));
    }
    private void loadAudit(Review review) {
        if(!current(review)||review.ticket!=null)return;
        phase(review,Phase.AUDIT);renderBusy(review,t("監査履歴を取得中","Loading audit history"));
        plugin.storage().permanentIpReviewHistory(review.sourceCase,36).whenComplete((rows,error)->plugin.main(()->{
            if(!current(review)||review.phase!=Phase.AUDIT)return;
            if(error!=null){if(review.auditOnly)fail(review,t("監査履歴を取得できません。","Audit history unavailable."));else {review.player.sendMessage(t("監査履歴を取得できません。","Audit history unavailable."));renderReview(review);}return;}
            fill(review);
            for(int i=0;i<rows.size()&&i<PAGE_SIZE;i++) {
                PermanentIpAudit row=rows.get(i);
                button(review,9+i,Material.BOOK,row.event(),List.of(row.sourceCaseId(),row.actor().toString(),row.recordedAt().toString(),row.reasonCode(),
                    row.appliedCaseId()==null?t("適用ケースなし","No applied case"):row.appliedCaseId()),null);
            }
            if(rows.isEmpty())button(review,22,Material.PAPER,t("審査監査の記録なし","No review audit records"),List.of(review.sourceCase),null);
            if(!review.auditOnly)button(review,49,Material.OBSIDIAN,t("審査に戻る","Return to review"),List.of(),p->renderReview(review));
        }));
    }
    static boolean reviewExpired(boolean loadingOrAudit,long phaseAgeNanos,PermanentIpReview value,Instant now) {
        return phaseAgeNanos<0||(loadingOrAudit&&phaseAgeNanos>LOADING_AUDIT_NANOS)
            ||(value!=null&&(!value.candidate().validAt(now)||!value.source().uuidActiveAt(now)));
    }
    private boolean expired(Review review) {
        long now=nanoTime.getAsLong(),tokenAge=now-review.ticketNanos;
        return reviewExpired(review.phase==Phase.LOADING||review.phase==Phase.AUDIT,now-review.phaseNanos,review.value,clock.instant())
            ||(review.ticket!=null&&(tokenAge<0||tokenAge>CONFIRM_NANOS));
    }
    public void pulse() {
        for(Review review:List.copyOf(active.values())) {
            boolean allowed=authorized(review.player);approvals.observeActor(review.actor,actorLookup.apply(review.actor),allowed);
            if(!allowed)cancel(review,actorLookup.apply(review.actor)==review.player?"PERMISSION_LOST":"SESSION_CHANGED",true);
            else if(review.player.getOpenInventory().getTopInventory()!=review.inventory)cancel(review,"CLOSED",false);
            else if(expired(review))cancel(review,"EXPIRED",true);
            else if(!review.auditOnly&&!permitted())cancel(review,"POLICY_CHANGED",true);
        }
    }
    public void quit(Player player) {
        Review review=active.get(player.getUniqueId());
        if(review!=null&&review.player==player){approvals.forget(review.actor);cancel(review,"SESSION_CHANGED",false);}
    }
    public void cancelCase(String sourceCase) {
        approvals.cancelCase(sourceCase);
        for(Review review:List.copyOf(active.values()))if(review.sourceCase.equals(sourceCase))cancel(review,"SOURCE_CHANGED",true);
    }
    private void fail(Review review,String message) {
        boolean visible=current(review);cancel(review,"FAILED",true);if(visible)review.player.sendMessage(message);
    }
    private void cancel(Review review,String reason,boolean closeInventory) {
        boolean owned=active.remove(review.actor,review);review.actionsGate.close();
        if(review.ticket!=null) {
            approvals.cancel(review.actor,review.ticket.token());
            if(owned)plugin.storage().cancelPermanentIpReview(review.ticket.token(),review.actor,reason)
                .exceptionally(error->{plugin.main(()->plugin.getLogger().warning("Permanent IP review cancellation audit could not be saved."));return null;});
        }
        if(closeInventory&&review.player.isOnline()&&review.player.getOpenInventory().getTopInventory()==review.inventory)review.player.closeInventory();
    }
    private void cancelAll(String reason){for(Review review:List.copyOf(active.values()))cancel(review,reason,true);}
    @Override public void close(){closed=true;approvals.invalidate();cancelAll("SHUTDOWN");clicks.clear();}
    @EventHandler(priority=EventPriority.HIGHEST) public void click(InventoryClickEvent event) {
        if(!(event.getView().getTopInventory().getHolder() instanceof PermanentIpController.Review review)||review.owner()!=this)return;
        event.setCancelled(true);
        if(!(event.getWhoClicked() instanceof Player player)||player!=review.player||!current(review))return;
        if(event.getRawSlot()<0||event.getRawSlot()>=54||!(event.getClick()==ClickType.LEFT||event.getClick()==ClickType.RIGHT)
            ||!clicks.allow(review.actor,System.currentTimeMillis()))return;
        Consumer<Player> action=review.actions.get(event.getRawSlot());if(action==null)return;
        long revision=review.actionsGate.issue();Bukkit.getScheduler().runTask(plugin,()->{
            if(review.actionsGate.consume(revision,current(review)))action.accept(player);
        });
    }
    @EventHandler(priority=EventPriority.HIGHEST) public void drag(InventoryDragEvent event) {
        if(event.getView().getTopInventory().getHolder() instanceof PermanentIpController.Review review&&review.owner()==this)event.setCancelled(true);
    }
    @EventHandler(priority=EventPriority.HIGHEST) public void inventoryOpen(InventoryOpenEvent event) {
        if(event.getInventory().getHolder() instanceof PermanentIpController.Review review&&review.owner()==this
            &&(event.getPlayer()!=review.player||!authorized(review.player)||active.get(review.actor)!=review))event.setCancelled(true);
    }
    @EventHandler public void inventoryClose(InventoryCloseEvent event) {
        if(event.getInventory().getHolder() instanceof PermanentIpController.Review review&&review.owner()==this)cancel(review,"CLOSED",false);
    }
    private static ItemStack item(Material material,String name,List<String> lore) {
        ItemStack item=new ItemStack(material);ItemMeta meta=item.getItemMeta();
        meta.setDisplayName("§f"+name);meta.setLore(lore.stream().map(line->"§7"+line).toList());item.setItemMeta(meta);return item;
    }
    private void button(Review review,int slot,Material material,String name,List<String> lore,Consumer<Player> action) {
        review.inventory.setItem(slot,item(material,name,lore));if(action!=null)review.actions.put(slot,action);
    }
}
