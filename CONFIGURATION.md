# Configuration / 設定

## Pre-release scope / 自動処罰の実装範囲

**1.0.0-dev.1 は評価用のPre-releaseです。61観測ルールのうち、自動BANの候補になるのは5個の厳密なプロトコル不正値ルールだけです。残り56個は管理者確認用で、Fly・Speed・Aim・Xray等の推測を高確信度の自動処罰へ進める経路は未実装です。設定やSTRICTへの変更では追加されません。** 実Spigotランタイム、実プレイヤーの誤検知/見逃し率、真正なMicrosoft認証による正例は未検証です。

**This evaluation pre-release has 5 hard protocol rules eligible for evidence-gated automatic bans and 56 review-only heuristics. Escalation from those heuristics to high-confidence automatic sanctions is not implemented, not merely untested.** A high VL or STRICT alone does not enable it. Actual Spigot runtime, human-play accuracy and genuine Microsoft-authenticated positive admission remain unverified.

### 検知・自動BANの設定

OPまたは管理権限で `/gac gui` →「検知管理」を開き、必要なルールの「状態: ON」を確認します。初期値は全ONで、全体の検知ONスイッチはありません。「メインメニュー」→「システム設定」で「感度: STRICT」「自動BAN · ON」「BAN方式: UUID」「UUID BAN期間: 24h」を選びます。既に目的の値ならクリック不要です。基本設定はGUIから保存成功後すぐ反映します。

手動設定は `plugins/GuardianAC/config.yml`。`preset`、`auto-ban`、`ban-type`、`ban-duration-hours` はトップレベル、個別ルールは `checks.<id>` です。編集後に `/gac reload` を実行します。`auto-ban: false` は検知・証拠保存を維持して自動BANだけ停止します。既に確定したBANの解除は別のUNBAN操作です。

自動BANには、少なくとも2つの独立した厳密ルールの証拠グループ、コード上のconfidence値0.99以上、各6回以上・3秒以上の反復、直近120秒以内の証拠、STRICTでは対象VL合計100以上、証拠保存・実行直前の再検証成功などが必要です。confidenceは実プレイで測定した正解率ではありません。`guardianac.bypass` や非サバイバル等の免除、対象外ランタイムでは適用しません。`/gac status` の `UNSUPPORTED` は検知・自動BAN・Xray停止を示します。

## GUI

`/guardian` / `/gac gui` は全ページ54スロットです。黒曜石/黒色ガラスをベースに、閲覧者と管理者の権限を分けます。左/右クリック以外の操作、下側インベントリ、Shift、数字キー、ドラッグは操作対象にしません。設定は保存成功後に反映し、保存失敗では前の値に戻ります。

メインページ: 検知、プレイヤー、BAN/IP BAN、UNBAN、違反履歴、証拠/リプレイ、Xray、Discord、システム設定。

### 主な設定

| Key | Default | Behavior |
| --- | --- | --- |
| language | ja | ja/en、GUIですぐ切替 |
| preset | STRICT | STRICT / NORMAL / RELAXED。安全条件は固定 |
| auto-ban | true | 独立高確信度の反復証拠 + 保存成功後のみ |
| ban-type | UUID | UUID / IP / DUAL。自動IPはUUID制裁も含めて同時記録 |
| ban-duration-hours | 24 | UUID期間: 1 / 24 / 168 / 720 / 0(永久) |
| ip-duration-hours | 24 | IP期間: 1 / 24 / 168 / 720 / 0(永久) |
| allow-permanent-ip | false | IP/DUAL・IP期間0で、資格のあるUUIDケースに審査候補を保存。管理者が証拠と共有IP影響を確認するまでIP制限なし。修正版の限定的なサーバー試験は通過。実Microsoft認証は未検証 |
| native-ban-sync | true | 所有マーカーが一致する標準BANだけ更新/解除 |
| broadcast | true | 名前を含む最小限の全体BAN通知。レート制限あり |
| discord-enabled | false | 手動Webhook設定と明示ONの両方が必要 |
| automatic-tuning | true | 低TPS時に必要証拠数を増やす方向のみ |
| xray.enabled | true | 対応する独立隠蔽・採掘分析の管理 |
| xray.hide-caves | false | 地下空間の隠蔽は描画/性能検証後に任意ON |
| checks.<id> | true | 61個の各モジュールを個別ON/OFF |

期限付きDUALの自動制裁ではUUID/IPを1つの原子的なケースとして同じ期限で保存します。その期限はIP期間の設定を使用します。自動IPには、現在のネイティブonline-mode接続、正常に保存された接続履歴、対象UUIDとIPの信頼済み対応、認証の有無を問わず同IPで観測したUUIDが1つだけであることを必要とします。保存直前にも再確認し、不明・offline-mode・未対応proxy・共有IPでは設定済みUUID期間のUUID制裁だけに下げます。IP一致は同一人物性の証明ではありません。

永久IPでは先にUUID制裁と暗号化した審査候補だけを保存し、IP制限は行いません。管理者がケース画面から証拠の全ページを確認し、永久制限と共有回線への影響に明示同意して30秒以内に最終確認した場合のみ、別の永久IP専用ケースを記録します。元UUIDの期限は変更しません。候補は最長24時間かつ元UUID制裁が有効な間だけ対象です。既存の古い証拠や期限付きDUALケースを黙って昇格させません。設定・権限・接続変更や元ケース解除で未適用の確認は失効します。旧d2737dec224c内部版では審査・拒否・再起動経路と高度設定GUIの限定的な試験が通過しました。ただし同版は外部メトリクス停止の不備があり導入不可で、その試験は修正版fb56fa5d8fccの実サーバー検証にはなりません。修正版は新しい監督付きPaper修飾条件・30分試験で194/194件を通過し、最終状態ゼロ・DB整合性・正常終了を確認しました。修正版のPaper/Purpur統合試験も各328/328件を通過しました。各総数に永久IP審査79件、高度設定GUI46件、設定・再起動26件を含み、別加算ではありません。別の実TCP IP/DUAL試験も156/156件を通過し、未認証・共有IPでUUID制裁だけに下げて正常な同一回線の接続を保護しました。認証済みの正例を実測したものではありません。過去2回の中断試験の終了は不明のままです。正式リリースの検証条件を全て満たしたという意味ではありません。認証済み本人性の正例は合成fixtureであり、Microsoft認証を実測したという意味ではありません。詳細は [永久IP審査](docs/PERMANENT-IP-REVIEW.md)。

### 資源上限

管理GUIの「システム設定 → 高度な資源設定」で、保存値と稼働中の値を確認できます。項目を開き、±1・項目別の小/大ステップ・既定値で編集し、「確認して保存」を押します。閲覧権限だけでは変更できません。別の管理者による変更やreload後の古い画面からは保存できず、開き直しが必要です。config.ymlのlimits節でも設定可能です。

| limitsキー | 既定値 | 範囲 | 反映 |
| --- | --- | --- | --- |
| max-players | 2000 | 16–10000 | 再起動 |
| packets-per-tick | 1024 | 64–4096 | 保存/reload後 |
| evidence-mib | 256 | 16–2048 | 再起動 |
| evidence-days | 90 | 1–3650 | 再起動 |
| replay-total-mib | 32 | 1–256 | 再起動 |
| replay-per-player-kib | 64 | 4–512 | 再起動 |

パケット待ち行列8192件は変更できない安全上限です。起動時に割り当てる5項目は、保存しただけでは稼働値が変わらず、GUIに再起動が必要と表示します。手動設定が型/範囲違反の場合は警告と実際の補正値を表示し、GUI保存で範囲内の整数へ直します。自動TPS調整をOFFにすると、以前の低TPSによるヒューリスティック緩和も直ちに通常値へ戻ります。ハード不変条件の証拠要件は自動調整で緩めません。

共有IP判定は直近30日の観測を使うため、UUIDとIPの照合用履歴は証拠本文の保存期間を短くしても最低30日保持します。設定期間が30日より長ければその期間を使い、未期限切れの永久IP審査候補に必要な観測は候補終了まで保護します。この履歴はIPの鍵付き照合値で保存し、生のアドレスは保存しません。

SQLiteの本文容量とファイル全体のサイズは同じではありません。監査履歴、索引、暗号化メタデータ、WAL、バックアップには追加容量が必要です。有効なBANの根拠は削除せず、空きがなければ新しい証拠/BANを安全に失敗させます。ディスクの空きを監視してください。

IP暗号化はGuardianACのSQLite記録に適用されます。標準IP BAN同期を有効にすると、Minecraft仕様の `banned-ips.json` には平文IPが必要です。サーバー本体の接続ログも別です。OSのアクセス権限でこれらを保護し、標準形式が適さない場合は同期をOFFにしてください。

## Permissions

| Permission | Scope |
| --- | --- |
| guardianac.view | GUI/状態/証拠の閲覧 |
| guardianac.admin | 設定変更、証拠書出し、バックアップ。view/unbanを包含 |
| guardianac.unban | 確認付きでGuardianACのケースを解除 |
| guardianac.alerts | 詳細な管理者向け通知 |
| guardianac.bypass | 検知の明示的な対象外。既定false |
| guardianac.xray.bypass | 独立した鉱石/地下隠蔽の明示的な対象外。既定false |

OPが暗黙にbypassになる構成ではありません。閲覧権限はプレイヤーの行動証拠を閲覧できるため、信頼する担当者だけに付与します。

## Unban

- `/gac unban <player>` はそのプレビューに含まれるUUID制裁部分を解除
- `/gac unban <case-id>` はそのケース全体を解除
- `/gac unbanip <numeric-ip>` は一致するケースのIP部分をプレビュー
- `/gac unbanall <player>` はそのプレビューのUUID/IP部分を解除
- プレイヤーには確認GUI、コンソールには30秒のactor-boundワンタイムトークン
- `/gac confirm <token> <reason>` で理由を記録
- ケースGUIの「IP BANだけ解除」はそのケースのIP部分だけを対象にし、UUID部分と他ケースは保持
- 解除確認は範囲と全対象ケースIDをページ表示。表示後の新ケースは対象外

プレビュー後に新しく作られた別ケースを巻き込まないよう、確認対象はケースIDに固定します。通常のMinecraft標準BANリストや他プラグインのBANは、GuardianAC所有マーカーが一致しない限り解除しません。

## Discord

Webhook URLを `plugins/GuardianAC/discord-webhook.txt` に1行だけ手動登録し、ファイル権限を管理者専用にします。`/gac reload` 後、GUIでONにします。URLをコマンド引数に入力しないでください。サーバーのコマンドログに残る恐れがあります。

対応先は正規のHTTPS `discord.com/api[/vN]/webhooks/...` のみ。リダイレクト、別ホスト、任意ポートやクエリを許可しません。送信は上限付きで、メンション解析はOFF。機密URLや生IPを通知へ含めません。Discordなしで本体機能は動作します。

## English quick reference

Settings above are persisted by the GUI, with Japanese/English language selection. Advanced resource limits are available through System settings → Advanced resource limits and config.yml. The GUI shows saved versus startup-active values, bounded edits, stale-revision refusal and restart requirements. Only packets-per-tick changes live; the other five require a full restart. The fixed packet queue cap is not editable. Review-only heuristics cannot become automatic-ban evidence simply by selecting STRICT. Temporary DUAL automatic bans share one atomic case and one expiry. Native authenticated session/target association and all-observed-account shared-address checks are repeated at commit; unknown/offline/proxy identity falls back to UUID. Permanent mode creates only a protected review candidate beside the original UUID sanction. Explicit administrator review and a short-lived confirmation are required before a separate permanent IP-only case is committed; the source UUID expiry is unchanged. The earlier d2737dec224c artifact passed bounded review/refusal/restart fixtures, but has a metrics defect and must not be deployed. Corrected fb56fa5d8fcc passed its new supervised Paper modifier/30-minute soak with 194/194 assertions, final tracked state zero, SQLite integrity `ok` and normal owned exits 0. Its corrected Paper/Purpur matrices also passed 328/328 each, including 79 permanent-IP, 46 advanced-GUI and 26 settings/runtime assertions per server. These are included subsets, and positive identity is synthetic, not actual Microsoft authentication. The separate corrected IP/DUAL TCP suite passed 156/156 offline-fallback assertions, preserving clean shared-address peers; it does not validate genuine authenticated IP-positive admission. Both earlier interrupted runs retain unknown termination.

Unban previews are actor-bound and case-scoped; they must not remove later cases or foreign native entries. Retained replay exports contain private player activity, so restrict access. Discord is an administrator-configured optional external destination and is never required.

## 非同期制裁の安全境界 / asynchronous authorization

A pending automatic sanction carries immutable observed evidence and a bounded,
one-use authorization ticket. Its decision lifetime is 5 seconds; after the
main-thread policy/evidence recheck, the SQL commit claim has a 500 ms lease.
An expired/invalidated claim rolls the transaction back and causes no punishment.
Reloaded policy is invalidated before the new configuration is published. A
broken optional Discord secret cannot leave the old enforcement policy active.

A confirmed UNBAN resets old VL and cancels the player's still-unclaimed
automatic decisions. It does not revoke a separately committed later case.
An ordinary logout or world change does not erase an already valid captured
protocol decision. Known bypass grants cancel pending authorization; permissions
are read on main-thread revalidation and tick/quit snapshots, never from SQL or
network workers. Third-party plugins have no universal Bukkit permission-change
notification, so that observation boundary remains explicit. A successful final
claim is the authorization linearization point immediately before SQL commit;
a later toggle does not silently remove the resulting committed sanction.

Only Minecraft 26.3 / protocol 777 is eligible for this build's decoding and
detection. Unknown, translated or unsupported client versions remain exempt from
affected event heuristics as well as packet decoding. An unsupported server
runtime retains existing-ban enforcement and administration where the server
allows the plugin to load, but disables detection, packet parsing and Xray.
