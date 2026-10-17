/* SPDX-License-Identifier: GPL-3.0-only */
package dev.guardianac.bukkit;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/** Closed, non-secret allowlist of the resource options exposed in the GUI. */
enum AdvancedSetting {
    MAX_PLAYERS("max-players",2000,16,10000,100,1000,true,
        "追跡プレイヤー上限","Tracked-player limit","人","players",
        "検知とイベント観測セッションの追跡容量。","Capacity for detection and event-observation sessions.",
        "大きな値ほどメモリを多く使用します。","Larger limits use more memory."),
    PACKETS_PER_TICK("packets-per-tick",1024,64,4096,64,512,false,
        "毎tickのパケット処理数","Packets processed per tick","件/tick","packets/tick",
        "メインスレッドで処理する待機サンプル数。","Queued samples processed on the main thread.",
        "増やすとCPU負荷が増加。待機列8192件は固定。","Higher values increase CPU work; queue capacity is fixed at 8192."),
    EVIDENCE_MIB("evidence-mib",256,16,2048,16,128,true,
        "証拠本文の保存容量","Evidence payload capacity","MiB","MiB",
        "証拠本文に使う上限。DB全体の容量ではありません。","Payload budget, not a limit on the whole database file.",
        "有効BANの根拠は保持。WAL・監査・バックアップは別枠。","Active-ban evidence is retained; WAL, audit and backups use extra space."),
    EVIDENCE_DAYS("evidence-days",90,1,3650,7,30,true,
        "証拠本文の保存日数","Evidence payload retention","日","days",
        "期限切れ本文を整理。ハッシュと監査記録は保持。","Expired payloads are pruned; hashes and audit history remain.",
        "共有IPの照合履歴は最低30日。短縮時は証拠消去に注意。","Shared-IP history keeps at least 30 days; shorter retention can erase evidence."),
    REPLAY_TOTAL_MIB("replay-total-mib",32,1,256,8,32,true,
        "リプレイ合計メモリ","Total replay memory","MiB","MiB",
        "全プレイヤーのメモリ内リプレイ保存上限。","Combined in-memory replay budget across players.",
        "上限到達時は古いバッファから破棄します。","Oldest buffers are evicted when the budget is reached."),
    REPLAY_PER_PLAYER_KIB("replay-per-player-kib",64,4,512,4,64,true,
        "1人のリプレイメモリ","Replay memory per player","KiB","KiB",
        "プレイヤーごとの符号化済みリプレイ上限。","Encoded replay budget for each player.",
        "上限到達時は古いフレームから破棄。最大120件は固定。","Old frames are evicted; the 120-frame cap is fixed.");

    private static final List<AdvancedSetting> ALL=List.of(values());
    private final String id,titleJa,titleEn,unitJa,unitEn,descriptionJa,descriptionEn,cautionJa,cautionEn;
    private final int fallback,min,max,step,largeStep;
    private final boolean restartRequired;
    AdvancedSetting(String id,int fallback,int min,int max,int step,int largeStep,boolean restartRequired,
                    String titleJa,String titleEn,String unitJa,String unitEn,String descriptionJa,String descriptionEn,
                    String cautionJa,String cautionEn) {
        this.id=id;this.fallback=fallback;this.min=min;this.max=max;this.step=step;this.largeStep=largeStep;
        this.restartRequired=restartRequired;this.titleJa=titleJa;this.titleEn=titleEn;this.unitJa=unitJa;this.unitEn=unitEn;
        this.descriptionJa=descriptionJa;this.descriptionEn=descriptionEn;this.cautionJa=cautionJa;this.cautionEn=cautionEn;
    }
    String id(){return id;}
    String key(){return "limits."+id;}
    int fallback(){return fallback;}
    int min(){return min;}
    int max(){return max;}
    int step(){return step;}
    int largeStep(){return largeStep;}
    boolean restartRequired(){return restartRequired;}
    String title(String language){return "ja".equals(language)?titleJa:titleEn;}
    String value(int value,String language){return value+" "+("ja".equals(language)?unitJa:unitEn);}
    int read(GuardianSettings settings){return settings.limit(id,fallback,min,max);}
    boolean valid(GuardianSettings settings){return settings.validLimit(id,min,max);}
    int clamp(long value){return (int)Math.max(min,Math.min(max,value));}
    static List<AdvancedSetting> all(){return ALL;}
    static List<AdvancedSetting> page(int page,int size) {
        if(size<1||size>36||page<0||page>=(ALL.size()+size-1)/size)throw new IllegalArgumentException("Invalid advanced settings page");
        int first=page*size;return ALL.subList(first,Math.min(first+size,ALL.size()));
    }
    List<String> lore(String language,int saved,int active,boolean valid) {
        boolean ja="ja".equals(language);List<String> lore=new ArrayList<>();
        lore.add(key());lore.add(ja?descriptionJa:descriptionEn);
        lore.add((ja?"保存設定（有効値）: ":"Saved setting (effective value): ")+value(saved,language));
        lore.add((ja?"稼働中の値: ":"Active this session: ")+value(active,language));
        lore.add((ja?"設定範囲: ":"Allowed range: ")+value(min,language)+" – "+value(max,language));
        lore.add((ja?"既定値: ":"Default: ")+value(fallback,language));
        lore.add(restartRequired?(ja?"保存後、サーバー再起動で反映。reloadでは未反映。":"Saved changes require a server restart; reload does not apply them."):
            (ja?"保存後、次のtickから反映します。":"Saved changes apply from the next tick."));
        if(restartRequired&&saved!=active)lore.add(ja?"再起動待ちの変更があります。":"A saved change is waiting for restart.");
        if(!valid)lore.add(ja?"手動設定が不正です。有効値を表示。保存で正規化します。":"Manual config is invalid; the bounded value is shown. Save normalizes it.");
        lore.add(ja?cautionJa:cautionEn);return List.copyOf(lore);
    }
    /** Unsaved, immutable server-owned draft. Values and destinations never come from item metadata. */
    record Draft(AdvancedSetting setting,int saved,int proposed,long revision) {
        Draft {
            Objects.requireNonNull(setting);
            if(saved<setting.min||saved>setting.max||proposed<setting.min||proposed>setting.max||revision<0)
                throw new IllegalArgumentException("Invalid advanced settings draft");
        }
        Draft adjust(int delta){return new Draft(setting,saved,setting.clamp((long)proposed+delta),revision);}
        Draft reset(){return new Draft(setting,saved,setting.fallback,revision);}
        boolean current(long actualRevision){return revision==actualRevision;}
        boolean save(GuardianSettings settings) throws java.io.IOException {
            return settings.setIfRevision(setting.key(),proposed,revision);
        }
    }
}
