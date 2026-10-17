/* SPDX-License-Identifier: GPL-3.0-only */
package dev.guardianac.bukkit;

import dev.guardianac.core.*;
import dev.guardianac.security.BoundedRateLimiter;
import dev.guardianac.security.SessionActionGate;
import dev.guardianac.storage.*;
import org.bukkit.*;
import org.bukkit.entity.Player;
import org.bukkit.event.*;
import org.bukkit.event.inventory.*;
import org.bukkit.inventory.*;
import org.bukkit.inventory.meta.ItemMeta;
import java.time.Instant;
import java.util.*;
import java.util.function.Consumer;
import com.google.gson.*;

/** All GUI data and actions are owned by a server-side holder, never by client item metadata. */
public final class GuiManager implements Listener {
    private final GuardianPlugin plugin;
    private final BoundedRateLimiter<UUID> clicks=new BoundedRateLimiter<>(4096,4,4);
    private static final int PAGE_SIZE=36;
    private final Map<AdvancedSetting,Integer> startupLimits=new EnumMap<>(AdvancedSetting.class);
    public GuiManager(GuardianPlugin plugin){
        this.plugin=plugin;
        for(AdvancedSetting setting:AdvancedSetting.all())startupLimits.put(setting,setting.read(plugin.settings()));
    }
    private String t(String ja,String en){return plugin.settings().text(ja,en);}
    private final class Menu implements InventoryHolder {
        final UUID owner;final Player ownerSession;final Map<Integer,Consumer<Player>> actions=new HashMap<>();final Inventory inventory;
        final SessionActionGate gate=new SessionActionGate();String confirmationToken;
        Menu(Player player,String title){owner=player.getUniqueId();ownerSession=player;inventory=Bukkit.createInventory(this,54,"§8GuardianAC §7· "+title);}
        boolean owns(Player player){return player==ownerSession&&owner.equals(player.getUniqueId());}
        @Override public Inventory getInventory(){return inventory;}
    }
    private record PendingView(Inventory source,SessionActionGate gate,long ticket) { }
    private PendingView beginLookup(Player player) {
        Inventory source=player.getOpenInventory().getTopInventory();
        if(!(source.getHolder() instanceof GuiManager.Menu menu)||!menu.owns(player))return null;
        return new PendingView(source,menu.gate,menu.gate.issue());
    }
    private boolean resumeLookup(Player player,PendingView view) {
        return view!=null&&view.gate().consume(view.ticket(),player.isOnline()&&player.hasPermission("guardianac.view")
            &&player.getOpenInventory().getTopInventory()==view.source()
            &&view.source().getHolder() instanceof GuiManager.Menu menu&&menu.owns(player));
    }
    private Menu create(Player player,String title) {
        Menu menu=new Menu(player,title);
        ItemStack filler=item(Material.BLACK_STAINED_GLASS_PANE," ",List.of());
        for(int i=0;i<54;i++)menu.inventory.setItem(i,filler);
        button(menu,49,Material.OBSIDIAN,t("メインメニュー","Main menu"),List.of(t("管理画面へ戻ります。","Return to administration.")),this::main);
        button(menu,53,Material.BARRIER,t("閉じる","Close"),List.of(),Player::closeInventory);return menu;
    }
    private void show(Player player,Menu menu) {
        if(!menu.owns(player)||!player.isOnline()||!player.hasPermission("guardianac.view"))return;player.openInventory(menu.inventory);
    }
    private static ItemStack item(Material type,String title,List<String> lore) {
        ItemStack stack=new ItemStack(type);ItemMeta meta=stack.getItemMeta();
        meta.setDisplayName("§f"+title);meta.setLore(lore.stream().map(s->"§7"+s).toList());stack.setItemMeta(meta);return stack;
    }
    private void button(Menu menu,int slot,Material type,String name,List<String> lore,Consumer<Player> action) {
        menu.inventory.setItem(slot,item(type,name,lore));if(action!=null)menu.actions.put(slot,action);
    }
    private void admin(Player player,Runnable action) {if(!player.hasPermission("guardianac.admin")){plugin.denied(player);return;}action.run();}
    private void toggle(Player player,String key,Runnable reopen) {
        admin(player,()->{try{plugin.settings().toggle(key);plugin.applySettings();reopen.run();}catch(Exception e){player.sendMessage(t("設定の保存に失敗しました。","Configuration save failed."));}});
    }
    public void main(Player player) {
        Menu m=create(player,t("管理","Administration"));
        button(m,10,Material.NETHERITE_SWORD,t("検知管理","Detection modules"),List.of(t("実判定を持つ各検知のON/OFF。","Enable or disable each observable rule."),t("誤検知防止は常に有効です。","False-positive protections always remain active.")),p->checks(p,0));
        button(m,12,Material.PLAYER_HEAD,t("プレイヤー監視","Player monitoring"),List.of(t("接続中のプレイヤーと違反状況。","Online players and current findings.")),p->players(p,0));
        button(m,14,Material.ANVIL,t("BAN / IP BAN管理","Ban management"),List.of(t("保存済みBANの範囲と期限を確認。","Inspect recorded sanctions and expiry."),t("通常履歴ではIPを非表示。審査は管理者限定。","Ordinary histories hide addresses; review is administrator-only.")),p->bans(p,0,false));
        button(m,16,Material.LIME_DYE,t("UNBAN管理","Unban management"),List.of(t("ケースを選び、確認して解除。","Select a case, then confirm removal.")),p->bans(p,0,true));
        button(m,28,Material.WRITABLE_BOOK,t("違反履歴","Violation history"),List.of(t("観測された異常と確信度。","Observed anomalies and confidence."),t("弱い証拠だけでは自動BANしません。","Heuristics alone cannot cause automatic bans.")),p->findings(p,0));
        button(m,30,Material.SPYGLASS,t("証拠・リプレイ","Evidence and replay"),List.of(t("位置・視線・イベントの記録。","Position, rotation and event records."),t("動画ではなく調査用の軌跡です。","A telemetry reconstruction, not video.")),p->evidenceList(p,0));
        button(m,32,Material.DIAMOND_ORE,t("X-Ray対策","Xray protections"),List.of(t("情報隠蔽と採掘経路の分析。","Concealment and mining-path analysis."),t("透視使用を完全には断定できません。","Client Xray cannot be identified with certainty.")),this::xray);
        button(m,34,Material.AMETHYST_SHARD,t("Discord通知","Discord notifications"),List.of(t("任意機能。初期状態はOFF。","Optional; disabled by default."),t("Webhookは秘密ファイルへ手動登録。","Register webhook manually in its secret file.")),this::discord);
        button(m,40,Material.COMPARATOR,t("システム設定","System settings"),List.of(t("感度・言語・制裁・性能を設定。","Sensitivity, language, sanctions and metrics.")),this::settings);
        show(player,m);
    }
    public void checks(Player player,int page) {
        List<Check> all=plugin.engine().checks();Menu m=create(player,t("検知管理","Detection modules"));
        for(int i=0;i<PAGE_SIZE;i++){int index=page*PAGE_SIZE+i;if(index>=all.size())break;Check c=all.get(index);boolean on=plugin.settings().checkEnabled(c.id());
            button(m,9+i,on?Material.LIME_DYE:Material.GRAY_DYE,t(c.nameJa(),c.nameEn()),List.of(c.id(),t("状態: ","State: ")+(on?"ON":"OFF"),
                t("証拠グループ: ","Evidence family: ")+c.family(),t("連続サンプル: ","Required samples: ")+c.requiredSamples(),
                c.hardInvariant()?t("厳密な不正値。独立証拠が必要。","Strict invalid-value rule; independent evidence required."):t("推測的な観測。管理者確認用です。","Heuristic observation; staff review only."),
                t("管理権限でクリックすると切替。","Administrators can click to toggle.")),p->admin(p,()->{
                    try{plugin.settings().set("checks."+c.id(),!plugin.settings().checkEnabled(c.id()));plugin.applySettings();checks(p,page);}catch(Exception e){p.sendMessage(t("保存に失敗しました。","Save failed."));}
                }));}
        pages(m,page,all.size(),p->checks(p,page-1),p->checks(p,page+1));show(player,m);
    }
    private void pages(Menu m,int page,int total,Consumer<Player> previous,Consumer<Player> next) {
        if(page>0)button(m,45,Material.ARROW,t("前へ","Previous"),List.of(),previous);
        if((page+1)*PAGE_SIZE<total)button(m,50,Material.ARROW,t("次へ","Next"),List.of(),next);
        button(m,48,Material.PAPER,(page+1)+" / "+Math.max(1,(int)Math.ceil(total/(double)PAGE_SIZE)),List.of(),null);
    }
    private void players(Player player,int page) {
        var all=Bukkit.getOnlinePlayers().stream().sorted(Comparator.comparing(Player::getName)).toList();Menu m=create(player,t("プレイヤー監視","Players"));
        for(int i=0;i<PAGE_SIZE;i++){int index=page*PAGE_SIZE+i;if(index>=all.size())break;Player target=all.get(index);
            button(m,9+i,Material.PLAYER_HEAD,target.getName(),List.of("Ping: "+target.getPing()+" ms",t("違反記録: ","Findings: ")+plugin.engine().current(target.getUniqueId()).size(),
                t("クリックで現在の検知情報。","Click for current observations.")),p->playerDetail(p,target.getUniqueId(),target.getName()));}
        pages(m,page,all.size(),p->players(p,page-1),p->players(p,page+1));show(player,m);
    }
    private void playerDetail(Player viewer,UUID id,String name) {
        Menu m=create(viewer,name);var current=plugin.engine().current(id);
        for(int i=0;i<Math.min(PAGE_SIZE,current.size());i++){Finding f=current.get(i);button(m,9+i,Material.PAPER,f.checkId(),findingLore(f),null);}
        button(m,4,Material.SPYGLASS,t("保存前リプレイ","Live replay buffer"),List.of(t("現在の記録サイズ: ","Current encoded size: ")+plugin.replay().json(id).length(),
            t("重大検知とBANで証拠DBへ保存。","Persisted on findings and bans.")),null);show(viewer,m);
    }
    private List<String> findingLore(Finding f){return List.of(t("違反レベル: ","Violation level: ")+Math.round(f.violationLevel()),
        t("観測回数: ","Occurrences: ")+f.occurrences(),t("確信度: ","Confidence: ")+f.confidence(),
        t("グループ: ","Family: ")+f.family(),Instant.ofEpochMilli(f.lastSeenMillis()).toString());}
    private void findings(Player player,int page) {
        var all=plugin.recentFindings();Menu m=create(player,t("違反履歴","Findings"));
        for(int i=0;i<PAGE_SIZE;i++){int index=page*PAGE_SIZE+i;if(index>=all.size())break;Finding f=all.get(index);
            List<String> lore=new ArrayList<>(findingLore(f));lore.add(f.sample().playerId().toString());button(m,9+i,Material.PAPER,f.checkId(),lore,null);}
        pages(m,page,all.size(),p->findings(p,page-1),p->findings(p,page+1));show(player,m);
    }
    private void bans(Player player,int page,boolean unban) {
        PendingView view=beginLookup(player);
        plugin.storage().recentBans(1000).whenComplete((records,error)->plugin.main(()->{
            if(!resumeLookup(player,view))return;
            if(error!=null){player.sendMessage(t("BAN一覧の取得に失敗しました。","Ban lookup failed."));return;}
            var all=unban?records.stream().filter(r->r.activeAt(Instant.now())).toList():records;
            Menu m=create(player,unban?t("UNBAN管理","Unban"):t("BAN履歴","Ban history"));
            for(int i=0;i<PAGE_SIZE;i++){int index=page*PAGE_SIZE+i;if(index>=all.size())break;BanRecord r=all.get(index);
                button(m,9+i,r.activeAt(Instant.now())?Material.REDSTONE_BLOCK:Material.GRAY_DYE,r.playerName(),List.of(r.caseId(),r.type().name(),
                    r.permanent()?t("永久BAN","Permanent ban"):t("解除予定: ","Expires: ")+r.expiresAt(),
                    t("IP情報: ","IP: ")+r.protectedIpLabel(),t("クリックでケース詳細。","Click for case details.")),p->caseDetail(p,r));}
            pages(m,page,all.size(),p->bans(p,page-1,unban),p->bans(p,page+1,unban));show(player,m);
        }));
    }
    private void caseDetail(Player player,BanRecord record) {
        Menu m=create(player,record.caseId());
        button(m,13,Material.PAPER,record.playerName(),List.of(record.caseId(),record.type().name(),record.reason(),record.createdAt().toString(),
            record.permanent()?t("永久","Permanent"):record.expiresAt().toString(),t("実行者: ","Actor: ")+record.actor()),null);
        button(m,28,Material.LIME_DYE,t("UUID BANだけ解除","Unban UUID only"),List.of(t("このケースのUUID制裁だけを解除。","Remove only this case's UUID restriction."),
            t("IP制裁と他のケースは残ります。","IP restrictions and other cases remain."),t("確認画面が開きます。","Opens a confirmation screen.")),p->plugin.requestUnban(p,record,"UUID"));
        button(m,30,Material.LIME_CONCRETE,t("ケース全体を解除","Unban this case"),List.of(t("このケースのUUID/IP範囲を解除。","Remove UUID/IP restrictions in this case."),
            t("他のケースは解除しません。","Other cases are unchanged.")),p->plugin.requestUnban(p,record,"ALL"));
        boolean ipActive=record.ipActiveAt(Instant.now());
        button(m,37,ipActive?Material.LIGHT_BLUE_DYE:Material.GRAY_DYE,t("IP BANだけ解除（このケース）","Unban IP only (this case)"),List.of(
            ipActive?t("このケースのIP制裁だけを解除。","Remove only this case's IP restriction."):t("このケースに有効なIP制裁はありません。","This case has no active IP restriction."),
            t("共有IPの他の利用者も接続可能になる場合があります。","Other accounts sharing this address may regain access."),
            t("有効なUUID制裁があれば残り、対象UUIDは引き続き拒否されます。","Any active UUID restriction remains; that UUID stays blocked."),
            t("同じIPの他のケースは解除しません。","Other cases on the same address are unchanged."),
            t("アドレスを表示せず確認画面へ進みます。","Confirm without displaying the address.")),ipActive?p->plugin.requestUnban(p,record,"IP"):null);
        button(m,32,Material.SPYGLASS,t("証拠リプレイを表示","Inspect replay"),List.of(t("記録された位置とイベントを表示。","Inspect saved positions and events.")),p->evidence(p,record));
        if(player.hasPermission("guardianac.admin"))button(m,39,Material.ENDER_EYE,t("永久IP候補を審査","Review permanent IP candidate"),List.of(
            t("候補・証拠・本人性・現在設定を検証します。","Revalidates candidate, evidence, trusted identity and policy."),
            t("管理者限定画面に正確なIPを表示。","The administrator-only review shows the exact address."),
            t("証拠確認と共有IPの永久制限への明示同意が必要。","Requires evidence review and explicit permanent shared-IP acknowledgement."),
            t("元UUIDケースの期限は変更しません。","The source UUID expiry is unchanged.")),p->admin(p,()->plugin.permanentIps().open(p,record.caseId())));
        if(player.hasPermission("guardianac.admin"))button(m,41,Material.BOOK,t("永久IP審査監査","Permanent IP review audit"),List.of(
            t("申請・取消・適用結果。適用後も確認できます。","Requests, cancellations and results remain visible after approval."),
            t("IPアドレスや確認トークンは表示しません。","No addresses or approval tokens are displayed.")),p->admin(p,()->plugin.permanentIps().openAudit(p,record.caseId())));
        button(m,34,Material.BOOK,t("解除監査履歴","Unban audit"),List.of(t("解除者・日時・理由。","Actor, time and reason.")),p->audit(p,record));show(player,m);
    }
    private void evidence(Player player,BanRecord record) {
        evidence(player,record.evidenceId(),record.caseId(),0);
    }
    private void evidenceList(Player player,int page) {
        PendingView view=beginLookup(player);
        plugin.storage().recentEvidence(1000).whenComplete((all,error)->plugin.main(()->{
            if(!resumeLookup(player,view))return;
            if(error!=null){player.sendMessage(t("証拠一覧の取得に失敗しました。","Evidence lookup failed."));return;}
            Menu m=create(player,t("証拠・リプレイ","Evidence and replay"));
            for(int i=0;i<PAGE_SIZE;i++){int index=page*PAGE_SIZE+i;if(index>=all.size())break;EvidenceSummary e=all.get(index);
                button(m,9+i,e.payloadAvailable()?Material.SPYGLASS:Material.GRAY_DYE,e.caseId(),List.of(e.playerId().toString(),e.recordedAt().toString(),
                    t("元データ: ","Original bytes: ")+e.originalBytes(),e.payloadAvailable()?t("クリックで時系列を表示。","Click to inspect the timeline."):t("保存期限切れ。ハッシュは保持。","Payload expired; hash retained.")),
                    p->evidence(p,e.id(),e.caseId(),0));}
            pages(m,page,all.size(),p->evidenceList(p,page-1),p->evidenceList(p,page+1));show(player,m);
        }));
    }
    private void evidence(Player player,String evidenceId,String caseId,int page) {
        PendingView view=beginLookup(player);
        plugin.storage().evidence(evidenceId).whenComplete((value,error)->plugin.main(()->{
            if(!resumeLookup(player,view))return;
            if(error!=null||value.isEmpty()||!value.get().payloadAvailable()){player.sendMessage(t("証拠がありません、または保存期限切れです。","Evidence absent or expired."));return;}
            Menu m=create(player,t("リプレイ時系列","Replay timeline"));
            try {
                ReplayView replay=replayView(value.get().telemetryJson());JsonArray frames=replay.frames();
                if(!replay.sourceBinding().isEmpty())button(m,6,Material.BOOK,t("承認元の証拠","Approval source evidence"),replay.sourceBinding(),null);
                for(int i=0;i<PAGE_SIZE;i++){int index=page*PAGE_SIZE+i;if(index>=frames.size())break;JsonObject frame=frames.get(index).getAsJsonObject();
                    JsonObject measurements=frame.getAsJsonObject("measurements");List<String> lore=new ArrayList<>();
                    lore.add(Instant.ofEpochMilli(frame.get("time").getAsLong()).toString());lore.add("Tick: "+frame.get("tick").getAsLong());
                    for(String field:List.of("x","y","z","yaw","pitch","reach","dy","horizontal","target-x","target-y","target-z"))
                        if(measurements.has(field))lore.add(field+": "+measurements.get(field).getAsString());
                    for(JsonElement flag:frame.getAsJsonArray("flags"))if(flag.getAsString().startsWith("world:"))lore.add(flag.getAsString());
                    button(m,9+i,Material.COMPASS,(index+1)+" · "+frame.get("kind").getAsString(),lore,null);
                }
                pages(m,page,frames.size(),p->evidence(p,evidenceId,caseId,page-1),p->evidence(p,evidenceId,caseId,page+1));
            }catch(RuntimeException malformed){player.sendMessage(t("証拠形式を表示できません。完全版を確認してください。","Cannot render this evidence format; inspect the export."));}
            button(m,4,Material.WRITABLE_BOOK,t("完全な証拠を書き出す","Export complete evidence"),List.of("/gac replay "+caseId,t("管理者のみ。サーバー内に保存。","Administrators only; saved locally.")),p->p.performCommand("gac replay "+caseId));show(player,m);
        }));
    }
    record ReplayView(JsonArray frames,List<String> sourceBinding) { }
    /** Only the known one-level approval envelope is unwrapped; arbitrary nested input is not followed. */
    static ReplayView replayView(String json) {
        if(json==null||json.length()>2*1024*1024)throw new IllegalArgumentException("Unsupported replay payload");
        JsonObject payload=JsonParser.parseString(json).getAsJsonObject();
        if(payload.has("replay"))return new ReplayView(payload.getAsJsonArray("replay"),List.of());
        if(!payload.has("kind")||!payload.get("kind").getAsString().equals("administrator-reviewed-permanent-ip"))
            return new ReplayView(new JsonArray(),List.of());
        if(!payload.has("schemaVersion")||payload.get("schemaVersion").getAsInt()!=1)
            throw new IllegalArgumentException("Unsupported approval evidence envelope");
        String source=payload.get("sourceCaseId").getAsString(),evidence=payload.get("sourceEvidenceId").getAsString(),
            sha=payload.get("sourceEvidenceSha256").getAsString();
        if(!source.matches("GAC-[A-Z0-9]{8,32}")||!evidence.matches("[A-Za-z0-9_-]{1,64}")||!sha.matches("[a-f0-9]{64}"))
            throw new IllegalArgumentException("Invalid source evidence binding");
        JsonObject proof=payload.getAsJsonObject("sourceProof");
        if(proof==null||!proof.has("replay")||!proof.get("replay").isJsonArray())
            throw new IllegalArgumentException("Source replay unavailable");
        return new ReplayView(proof.getAsJsonArray("replay"),List.of(source,evidence,"SHA-256:",sha));
    }
    private void audit(Player player,BanRecord record) {
        PendingView view=beginLookup(player);
        plugin.storage().unbanHistory(record.caseId()).whenComplete((records,error)->plugin.main(()->{
            if(!resumeLookup(player,view))return;
            if(error!=null){player.sendMessage(t("監査履歴の取得に失敗しました。","Audit lookup failed."));return;}
            Menu m=create(player,t("解除監査","Unban audit"));for(int i=0;i<Math.min(36,records.size());i++)
                button(m,9+i,Material.BOOK,t("解除履歴 ","Audit ")+(i+1),List.of(records.get(i).toString()),null);show(player,m);
        }));
    }
    /** The preview and confirmation use the same immutable, exact-case operation. */
    public void confirm(Player player,String token,GuardianPlugin.UnbanAction action) {
        confirmationPage(player,token,action,0,null);
    }
    private void confirmationPage(Player player,String token,GuardianPlugin.UnbanAction action,int page,Menu previous) {
        if(!player.isOnline()||!player.hasPermission("guardianac.view")||!player.hasPermission("guardianac.unban")) {
            plugin.cancelConfirmation(player.getUniqueId(),token);return;
        }
        if(previous!=null&&player.getOpenInventory().getTopInventory()!=previous.inventory)return;
        Menu m=create(player,t("解除を確認","Confirm unban"));m.confirmationToken=token;
        button(m,4,Material.PAPER,action.label(),confirmationLore(action,plugin.settings().language()),null);
        List<String> cases=confirmationCases(action,page);
        for(int i=0;i<cases.size();i++)button(m,9+i,Material.PAPER,cases.get(i),List.of(
            t("解除対象の範囲: ","Scope to remove: ")+action.scope(),t("この一覧のケースだけが対象です。","Only cases in this preview are included.")),null);
        pages(m,page,action.cases().size(),p->confirmationPage(p,token,action,page-1,m),p->confirmationPage(p,token,action,page+1,m));
        button(m,46,Material.LIME_CONCRETE,t("確認して解除","Confirm removal"),List.of(
            t("解除者・日時・理由を記録します。","Records actor, time and confirmation reason."),
            t("一覧の全ページにあるケースが対象です。","Applies to the listed cases across all preview pages.")),p->{plugin.confirm(p,token,"Administrator confirmed in GUI");p.closeInventory();});
        button(m,52,Material.RED_CONCRETE,t("キャンセル","Cancel"),List.of(),this::main);
        // Moving between pages transfers the same token; any actual close/cancel still invalidates it.
        if(previous!=null)previous.confirmationToken=null;
        show(player,m);
    }
    static List<String> confirmationCases(GuardianPlugin.UnbanAction action,int page) {
        int pages=Math.max(1,(action.cases().size()+PAGE_SIZE-1)/PAGE_SIZE);
        if(page<0||page>=pages)throw new IllegalArgumentException("Invalid confirmation page");
        int start=page*PAGE_SIZE;return List.copyOf(action.cases().subList(start,Math.min(start+PAGE_SIZE,action.cases().size())));
    }
    static List<String> confirmationLore(GuardianPlugin.UnbanAction action,String language) {
        boolean ja="ja".equals(language);List<String> lore=new ArrayList<>();
        lore.add((ja?"解除対象の範囲: ":"Scope to remove: ")+action.scope());
        lore.add((ja?"対象ケース数: ":"Cases in this preview: ")+action.cases().size());
        lore.add(action.cases().size()==1?(ja?"この1件だけを解除。他のケースは残ります。":"Only this one case; other cases remain."):
            (ja?"表示したケースだけが対象。後から追加されたケースは残ります。":"Only listed cases; later-added cases remain."));
        switch(action.scope()) {
            case "IP" -> {
                lore.add(ja?"IP制裁だけを解除します。アドレスは表示しません。":"Remove IP restrictions only; addresses stay hidden.");
                lore.add(ja?"共有IPの他の利用者も接続可能になる場合があります。":"Other accounts sharing an address may regain access.");
                lore.add(ja?"有効なUUID制裁があれば残り、対象UUIDは引き続き拒否されます。":"Active UUID restrictions remain; those UUIDs stay blocked.");
            }
            case "UUID" -> lore.add(ja?"UUID制裁だけを解除します。IP制裁は残ります。":"Remove UUID restrictions only; IP restrictions remain.");
            case "ALL" -> {
                lore.add(ja?"対象ケースのUUIDとIPの制裁を解除します。":"Remove UUID and IP restrictions in the listed cases.");
                lore.add(ja?"共有IPの他の利用者も接続可能になる場合があります。":"Other accounts sharing an address may regain access.");
            }
            default -> throw new IllegalArgumentException("Invalid unban scope");
        }
        lore.add(ja?"他のケースや他の管理者のBANは残ります。":"Other cases and other administrators' bans remain.");
        lore.add(ja?"確認の有効期限は発行から30秒です。":"Confirmation expires 30 seconds after it was issued.");
        lore.add(ja?"過去の証拠は削除しません。":"Historical evidence is preserved.");
        return List.copyOf(lore);
    }
    private void xray(Player player) {
        Menu m=create(player,t("X-Ray対策","Xray protection"));
        bool(m,11,"xray.enabled",t("Xray 保護（隠蔽・採掘分析）","Xray protection (masking + mining)"),List.of(
            t("情報隠蔽と採掘分析の一括切替。OFF時は一時記録も消去。","Master switch for masking and mining analysis. OFF clears transient records."),
            t("Paper/Purpur標準機能の二重処理を回避。","Avoids double processing with native Anti-Xray.")),this::xray);
        bool(m,15,"xray.hide-caves",t("地下情報保護","Underground protection"),List.of(t("対応経路で未接近の地下空間を隠します。","Conceals unrevealed underground spaces on supported paths.")),this::xray);
        button(m,31,Material.WRITABLE_BOOK,t("技術上の限界","Limits"),List.of(t("既に送った情報は回収できません。","Already-sent information cannot be revoked."),
            t("シード解析・描画改変の断定は不可。","Known seeds and client rendering remain limits."),t("採掘統計だけでは自動BANしません。","Mining statistics alone never auto-ban.")),null);
        List<String> status=new ArrayList<>();plugin.xray().nativeModes().forEach((id,mode)->{World world=Bukkit.getWorld(id);status.add((world==null?id.toString():world.getName())+": "+mode);});
        status.add(t("処理済チャンク: ","Concealed chunks: ")+plugin.xray().concealment().concealedChunks());
        status.add(t("対象なし: ","No eligible targets: ")+plugin.xray().concealment().noTargetChunks());
        status.add(t("処理上限による省略: ","Rewrite-budget skips: ")+plugin.xray().concealment().budgetLimitedChunks());
        status.add(t("省略/失敗: ","Skipped/failed: ")+plugin.xray().concealment().skippedChunks()+"/"+plugin.xray().concealment().failedChunks());
        status.add(t("上限超過時は一部情報を隠せません。","Budget saturation can leave some information visible."));
        button(m,40,Material.REDSTONE_TORCH,t("実際の動作状況","Effective protection status"),status,null);show(player,m);
    }
    private void discord(Player player) {
        Menu m=create(player,"Discord");
        bool(m,13,"discord-enabled",t("Discord通知","Discord notifications"),List.of(t("登録状態: ","Configured: ")+(plugin.discord().configured()?"YES":"NO"),
            t("失敗回数: ","Failed sends: ")+plugin.discord().failures(),t("URLは表示・ログ出力しません。","The URL is never displayed or logged.")),this::discord);
        button(m,31,Material.PAPER,t("手動登録","Manual setup"),List.of("plugins/GuardianAC/discord-webhook.txt",t("HTTPS Discord Webhookを1行で記入。","Put one HTTPS Discord webhook on one line."),
            t("/gac reload の後にONへ切替。","Run /gac reload, then enable."),t("管理者だけが読めるように保護。","Restrict the file to administrators.")),null);show(player,m);
    }
    private void settings(Player player) {
        Menu m=create(player,t("システム設定","Settings"));GuardianSettings s=plugin.settings();
        button(m,10,Material.COMPARATOR,t("感度: ","Sensitivity: ")+s.preset(),List.of(t("クリックでSTRICT/NORMAL/RELAXED。","Cycles STRICT/NORMAL/RELAXED."),t("確信度と安全条件は変更されません。","Evidence and safety conditions remain.")),p->admin(p,()->set(p,"preset",Preset.values()[(s.preset().ordinal()+1)%3].name())));
        button(m,12,Material.BOOK,t("言語: ","Language: ")+s.language(),List.of("日本語 / English"),p->admin(p,()->set(p,"language",s.language().equals("ja")?"en":"ja")));
        bool(m,14,"auto-ban",t("自動BAN","Automatic bans"),List.of(t("確信度の高い独立証拠が必要。","Requires independent high-confidence evidence.")),this::settings);
        button(m,16,Material.ANVIL,t("BAN方式: ","Ban type: ")+s.banType(),List.of(t("IP条件不足時はUUIDのみ。","Falls back to UUID when IP safeguards fail."),t("自動IPはUUID制裁も同時に記録。","Automatic IP restriction includes a UUID sanction.")),p->admin(p,()->set(p,"ban-type",BanType.values()[(s.banType().ordinal()+1)%3].name())));
        duration(m,28,false);duration(m,30,true);
        bool(m,32,"allow-permanent-ip",t("永久IPの許可","Allow permanent IP"),List.of(t("永久IPは管理者の証拠確認・明示承認が必要。","Permanent IP requires administrator evidence review and explicit approval."),t("自動処理はUUID制裁と保護された審査候補だけ。","Automation creates a UUID sanction and protected review candidate only."),t("共有IPから同一人物とは断定しません。","A shared IP does not establish the same person.")),this::settings);
        bool(m,34,"broadcast",t("全体BAN通知","Public ban notice"),List.of(t("プレイヤー名だけの最小限通知。","Minimal notice identifying the banned player.")),this::settings);
        bool(m,37,"automatic-tuning",t("自動チューニング","Automatic tuning"),List.of(t("低TPS時は判定を緩和する方向のみ。","Only relaxes checks when TPS is low.")),this::settings);
        bool(m,39,"native-ban-sync",t("標準BAN同期","Native ban synchronization"),List.of(t("他の管理者のBANを解除しません。","Never removes another administrator's ban.")),this::settings);
        button(m,22,Material.REPEATER,t("高度な資源上限","Advanced resource limits"),List.of(
            t("追跡人数・処理数・証拠・リプレイ容量。","Player tracking, packet work, evidence and replay budgets."),
            t("現在値・範囲・再起動の要否を確認して保存。","Inspect current values, bounds and restart requirements before saving.")),p->advanced(p,0));
        button(m,41,Material.CLOCK,t("性能監視","Performance"),performanceLore(plugin.status(),s.language()),null);
        button(m,43,Material.CHEST,t("DBバックアップ","Database backup"),List.of(t("安全なSQLiteスナップショットを保存。","Saves a consistent SQLite snapshot.")),p->admin(p,()->plugin.storage().backup().whenComplete((path,error)->plugin.main(()->p.sendMessage(error==null?t("バックアップを保存しました。","Backup saved."):t("バックアップ失敗。","Backup failed."))))));show(player,m);
    }
    /** The final status line keeps stable diagnostic fields; GUI labels are localized separately. */
    static List<String> performanceLore(String status,String language) {
        String machineLine=Objects.requireNonNull(status).lines().reduce((first,last)->last).orElse("");
        List<String> fields=Arrays.asList(machineLine.split(" \\| "));
        if(!"ja".equals(language))return List.copyOf(fields);
        List<String> localized=new ArrayList<>();
        String[] prefixes={"TPS ","engine avg ","evidence flags ","announced bans ","packet drops ","context capture failures ","queued ","replay ","denied joins "};
        String[] labels={"TPS: ","検知処理の平均時間: ","証拠検知数: ","通知済みBAN数: ","破棄サンプル数: ","状況取得失敗数: ","待機サンプル数: ","リプレイ使用量: ","接続拒否数: "};
        for(String field:fields) {
            if(field.startsWith("runtime ")){localized.add(field.contains("UNSUPPORTED:")?"対象外サーバー: 検知・自動BAN・Xrayは停止中。":"対応サーバー: Minecraft 26.3");continue;}
            if(field.endsWith(" rules")){localized.add("検知ルール数: "+field.substring(0,field.length()-6));continue;}
            if(Set.of("STRICT","NORMAL","RELAXED").contains(field)){localized.add("感度: "+field);continue;}
            String line=field;
            for(int i=0;i<prefixes.length;i++)if(field.startsWith(prefixes[i])){line=labels[i]+field.substring(prefixes[i].length());break;}
            localized.add(line);
        }
        return List.copyOf(localized);
    }
    private void advanced(Player player,int page) {
        Menu m=create(player,t("高度な資源上限","Advanced resource limits"));GuardianSettings settings=plugin.settings();
        List<AdvancedSetting> options=AdvancedSetting.page(page,PAGE_SIZE);
        for(int i=0;i<options.size();i++) {
            AdvancedSetting option=options.get(i);int saved=option.read(settings);List<String> lore=new ArrayList<>(
                option.lore(settings.language(),saved,activeLimit(option),option.valid(settings)));
            lore.add(player.hasPermission("guardianac.admin")?t("クリックで値を調整。保存前に確認できます。","Click to adjust, then review before saving."):
                t("閲覧専用。変更には管理権限が必要です。","Read only. Changes require administrator permission."));
            button(m,9+i,option.restartRequired()?Material.CHEST:Material.REPEATER,option.title(settings.language()),lore,
                p->openAdvancedSetting(p,option,page));
        }
        button(m,4,Material.PAPER,t("設定と反映時期","Settings and application"),List.of(
            t("変更を保存するまでは設定を書き換えません。","Adjustments are not written until you choose Save."),
            t("5つの資源割当はサーバー再起動で反映します。","Five resource allocations apply on server restart."),
            t("毎tickのパケット処理数は保存後すぐ反映します。","The packet-work budget applies immediately after saving."),
            t("秘密情報や固定の安全上限は編集しません。","Secrets and fixed safety caps are not editable here.")),null);
        pages(m,page,AdvancedSetting.all().size(),p->advanced(p,page-1),p->advanced(p,page+1));
        button(m,47,Material.COMPARATOR,t("システム設定へ戻る","Back to system settings"),List.of(),this::settings);show(player,m);
    }
    private int activeLimit(AdvancedSetting setting) {
        return setting.restartRequired()?startupLimits.get(setting):setting.read(plugin.settings());
    }
    private void openAdvancedSetting(Player player,AdvancedSetting setting,int page) {
        GuardianSettings settings=plugin.settings();AdvancedSetting.Draft draft;
        synchronized(settings) {int saved=setting.read(settings);draft=new AdvancedSetting.Draft(setting,saved,saved,settings.revision());}
        advancedEditor(player,draft,page);
    }
    private void advancedEditor(Player player,AdvancedSetting.Draft draft,int page) {
        if(!draft.current(plugin.settings().revision())){staleAdvanced(player,page);return;}
        AdvancedSetting option=draft.setting();String language=plugin.settings().language();
        Menu m=create(player,option.title(language));
        button(m,4,Material.PAPER,option.title(language),option.lore(language,draft.saved(),activeLimit(option),option.valid(plugin.settings())),null);
        button(m,13,Material.COMPARATOR,t("保存予定: ","Proposed value: ")+option.value(draft.proposed(),language),List.of(
            t("この画面の変更はまだ保存されていません。","Changes on this screen have not been saved."),
            t("戻る・閉じる・キャンセルで破棄します。","Back, Close and Cancel discard these adjustments.")),null);
        if(player.hasPermission("guardianac.admin")) {
            int[] slots={19,20,21,23,24,25};int[] deltas={-option.largeStep(),-option.step(),-1,1,option.step(),option.largeStep()};
            for(int i=0;i<slots.length;i++) {
                int delta=deltas[i];AdvancedSetting.Draft adjusted=draft.adjust(delta);
                button(m,slots[i],delta<0?Material.RED_DYE:Material.LIME_DYE,(delta>0?"+":"")+delta,List.of(
                    t("調整後: ","After adjustment: ")+option.value(adjusted.proposed(),language),
                    t("範囲の端ではそれ以上変更しません。","Adjustments stop at the allowed bounds.")),
                    p->admin(p,()->advancedEditor(p,adjusted,page)));
            }
            button(m,29,Material.REDSTONE,t("最小値を選ぶ","Use minimum value"),List.of(option.value(option.min(),language),
                t("保存するまでは反映しません。","Does not take effect until saved.")),p->admin(p,()->advancedEditor(p,draft.adjust(Integer.MIN_VALUE),page)));
            button(m,33,Material.GLOWSTONE_DUST,t("最大値を選ぶ","Use maximum value"),List.of(option.value(option.max(),language),
                t("保存するまでは反映しません。","Does not take effect until saved.")),p->admin(p,()->advancedEditor(p,draft.adjust(Integer.MAX_VALUE),page)));
            button(m,31,Material.SUNFLOWER,t("既定値に戻す","Use default value"),List.of(option.value(option.fallback(),language),
                t("保存するまでは反映しません。","Does not take effect until saved.")),p->admin(p,()->advancedEditor(p,draft.reset(),page)));
            button(m,46,Material.LIME_CONCRETE,t("確認して保存","Confirm and save"),List.of(
                option.value(draft.saved(),language)+" → "+option.value(draft.proposed(),language),
                option.restartRequired()?t("保存後も再起動までは現在の割当を維持します。","Current allocation remains active until server restart."):
                    t("保存後、次のtickから反映します。","Applies from the next tick after saving.")),p->saveAdvanced(p,draft,page));
        } else button(m,31,Material.GRAY_DYE,t("閲覧専用","Read only"),List.of(t("変更には管理権限が必要です。","Changes require administrator permission.")),null);
        button(m,45,Material.ARROW,t("上限一覧へ戻る","Back to resource limits"),List.of(t("未保存の変更を破棄します。","Discards unsaved changes.")),p->advanced(p,page));
        button(m,52,Material.RED_CONCRETE,t("キャンセル","Cancel"),List.of(t("未保存の変更を破棄します。","Discards unsaved changes.")),p->advanced(p,page));show(player,m);
    }
    private void staleAdvanced(Player player,int page) {
        player.sendMessage(t("別の設定変更またはreloadを検出しました。最新値からやり直してください。","Settings changed or were reloaded. Please start again from the latest values."));
        advanced(player,page);
    }
    private void saveAdvanced(Player player,AdvancedSetting.Draft draft,int page) {
        admin(player,()->{
            if(!draft.current(plugin.settings().revision())){staleAdvanced(player,page);return;}
            if(draft.proposed()==draft.saved()&&draft.setting().valid(plugin.settings())) {
                player.sendMessage(t("変更はありません。","No changes to save."));advanced(player,page);return;
            }
            try {
                if(!draft.save(plugin.settings())){staleAdvanced(player,page);return;}
            } catch(java.io.IOException failure) {
                player.sendMessage(t("設定保存に失敗しました。変更は反映されていません。","Configuration save failed; the change has not been applied."));return;
            }
            try {plugin.applySettings();}
            catch(RuntimeException failure) {
                player.sendMessage(t("設定は保存されましたが、再適用に失敗しました。サーバーログと稼働状態を確認してください。","Settings were saved, but reapplication failed. Check server logs and runtime state."));
                advanced(player,page);return;
            }
            player.sendMessage(draft.setting().restartRequired()?t("保存しました。変更はサーバー再起動後に反映します。","Saved. The change applies after server restart."):
                t("保存しました。変更は次のtickから反映します。","Saved. The change applies from the next tick."));
            advanced(player,page);
        });
    }
    private void duration(Menu m,int slot,boolean ip) {
        int value=plugin.settings().hours(ip);List<Integer> choices=List.of(1,24,168,720,0);
        button(m,slot,Material.CLOCK,t(ip?"IP BAN期間: ":"UUID BAN期間: ",ip?"IP duration: ":"UUID duration: ")+(value==0?t("永久","Permanent"):value+"h"),
            List.of(t("1時間 / 24時間 / 7日 / 30日 / 永久","1 hour / 24 hours / 7 days / 30 days / permanent")),p->admin(p,()->set(p,ip?"ip-duration-hours":"ban-duration-hours",choices.get((choices.indexOf(value)+1)%choices.size()))));
    }
    private void set(Player player,String key,Object value) {try{plugin.settings().set(key,value);plugin.applySettings();settings(player);}catch(Exception e){player.sendMessage(t("設定保存に失敗しました。","Configuration save failed."));}}
    private void bool(Menu m,int slot,String key,String title,List<String> lore,Consumer<Player> reopen) {
        boolean on=plugin.settings().enabled(key);button(m,slot,on?Material.LIME_DYE:Material.GRAY_DYE,title+" · "+(on?"ON":"OFF"),lore,p->toggle(p,key,()->reopen.accept(p)));
    }
    @EventHandler(priority=EventPriority.HIGHEST) public void click(InventoryClickEvent event) {
        if(!(event.getView().getTopInventory().getHolder() instanceof GuiManager.Menu menu))return;
        event.setCancelled(true);
        if(!(event.getWhoClicked() instanceof Player player)||!menu.owns(player)||!player.hasPermission("guardianac.view"))return;
        if(event.getRawSlot()<0||event.getRawSlot()>=54||!(event.getClick()==ClickType.LEFT||event.getClick()==ClickType.RIGHT)||!clicks.allow(player.getUniqueId(),System.currentTimeMillis()))return;
        Consumer<Player> action=menu.actions.get(event.getRawSlot());long ticket=menu.gate.issue();if(action!=null)Bukkit.getScheduler().runTask(plugin,()->{
            if(menu.gate.consume(ticket,player.isOnline()&&menu.owns(player)&&player.getOpenInventory().getTopInventory()==menu.inventory&&player.hasPermission("guardianac.view")))action.accept(player);
        });
    }
    @EventHandler(priority=EventPriority.HIGHEST) public void drag(InventoryDragEvent event) {
        if(event.getView().getTopInventory().getHolder() instanceof GuiManager.Menu)event.setCancelled(true);
    }
    @EventHandler public void close(InventoryCloseEvent event) {
        if(event.getInventory().getHolder() instanceof GuiManager.Menu menu) {
            menu.gate.close();
            if(menu.confirmationToken!=null)plugin.cancelConfirmation(menu.owner,menu.confirmationToken);
        }
    }
    public void closeAll(){for(Player p:Bukkit.getOnlinePlayers())if(p.getOpenInventory().getTopInventory().getHolder() instanceof GuiManager.Menu)p.closeInventory();clicks.clear();}
}
