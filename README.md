# GuardianAC Ultimate

Minecraft Java版サーバー向けのアンチチートプラグインです。公開Spigot API 1.20.4を使い、Spigotと、そのAPIと互換性があるPaper／Purpurを対象にしています。Java 17以上でビルドでき、実行には各サーバー自身が要求するJavaバージョンを使ってください。

| 機能 | 内容 |
| --- | --- |
| 不正検知 | KillAura、Fly、Speed、Reachのサーバーイベントに基づく検知 |
| 厳しめのVL | 検知別のバッファ・加算量・閾値、時間減衰、最小検知回数と証拠期間 |
| 自動処罰 | UUID BAN、UUID＋IP BAN、期限付きBAN、永久BAN |
| 永続化 | 専用 `plugins/GuardianAC/bans.json` に保存後、BANを適用 |
| 管理 | 手動BAN／IPBAN、UNBAN、IP解除、VL、一覧、通知、設定再読込 |
| 全体通知 | プレイヤー名を含むミニマルな複数行チャット演出。色と文面を設定可能 |
| 誤検知軽減 | Ping・サーバー遅延、参加・テレポート・ノックバック猶予、特殊移動、対象位置履歴 |
| 負荷制限 | 上限付き位置履歴、ロード済みチャンクのみの周辺確認、キュー上限付き非同期保存 |

単独のKick処罰はありません。BANを保存できた後の切断は、そのBANを現在の接続へ適用するための処理です。標準の `banned-players.json`／`banned-ips.json` とは同期しません。

## ビルド

JDK 17以上と、初回の公開Maven／SpigotリポジトリへのHTTPS接続が必要です。PacketEventsやProtocolLibの導入は不要です。

```sh
./mvnw -B verify
```

生成物は `target/GuardianAC-Ultimate-1.0.0-SNAPSHOT.jar` です。`original-` が付いたJARではなく、このJARを使います。ビルド設定ではJava 17形式のクラスを生成し、Spigot APIはサーバーから提供されます。

## 導入

1. 検証用サーバーを停止し、既存のプラグイン・設定・BANデータをバックアップします。
2. 旧GuardianACがある場合は、旧JARと `plugins/GuardianAC/` を別の場所へ退避します。添付の旧JARも同じプラグイン名のため、同時導入しません。設定・SQLite等の旧データをこの版へ自動移行する機能はありません。
3. 生成したJARを `plugins/` に置き、サーバーを起動します。初期設定は `punishment.auto-ban: true`、`mode: UUID_AND_IP`、`duration: 24h` です。
4. 検証環境で `/gac status` を確認し、生成された `plugins/GuardianAC/config.yml` を調整します。処罰前の校正には `punishment.auto-ban: false` を使えます。
5. `/gac reload` で設定を反映します。保存キュー容量変更や、起動時の設定・BANデータ読み込み失敗からの復旧にはサーバー再起動が必要です。

共有IP軽減は**現在接続している別UUID**との一致だけを確認します。オフラインの同居者・学校・VPN等を含む完全な共有回線判定ではありません。プロキシ利用時はバックエンドを直接公開せず、信頼するプロキシからのUUID／IP転送をサーバー側で正しく設定してください。IPの信頼性を確認できない環境では `punishment.mode: UUID` を使います。

## コマンド

`/guardian` と `/gac` は同じコマンドです。権限・設定・復旧手順の詳細は [運用ガイド](docs/OPERATIONS.md) を参照してください。

```text
/gac status
/gac alerts
/gac vl <オンライン名>
/gac ban <オンライン名> <30m|24h|7d|permanent> [理由]
/gac ipban <オンライン名> <期間> [理由]
/gac unban <名前|UUID>
/gac unbanip <数値IP>
/gac bans [ページ]
/gac reload
```

管理権限は既定でOP、`guardianac.bypass` は既定で無効です。OPも明示的に除外権限を付けない限り検知対象になります。

## 検証と範囲

Java 17／21でビルドと自動テスト154件が成功しました。Paper 1.20.4 #499／Purpur 1.20.4 #2176では、それぞれ実サーバー検証10項目が成功しています。手動BAN、UUID/IPによる接続拒否、期限切れ、UNBAN、永久BANの再起動維持、Flyによる自動UUID/IPBANと名前付き全体告知まで確認しました。詳細と再現手順は [検証記録](docs/VALIDATION.md) にあります。

Spigot本体での起動、別Minecraft版、大人数負荷、実対戦での検知精度は未検証です。KillAura／Speed／Reachの検知条件は自動テストで検証していますが、それぞれの実クライアントによる不正操作試験は未実施です。

イベントに基づくヒューリスティックのため、すべての不正を検知できる保証はありません。低いReach、自然な照準を再現するKillAura、イベントに現れないパケット操作などには限界があります。特殊アイテム、独自移動プラグイン、通信遅延と併せて実サーバーで校正してください。Paper／Purpurや各Minecraftバージョンの実測互換性は、実際に検証した範囲で判断します。

添付JARのGUI・パケット観測・SQLite・Xray等を移植したものではありません。添付物の由来、対応範囲、今回の実装との関係は [添付資料の確認](docs/ATTACHMENT_REVIEW.md) に記録しています。
