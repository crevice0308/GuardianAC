# Installation / 導入

**自動処罰の範囲:** 61観測ルール中、自動BAN候補は5個の厳密なプロトコル不正値ルールだけです。残り56個の推測判定から自動処罰へ進む経路は未実装です。Fly/Speed等を検知しても、それだけでは自動BANしません。設定ではこの不足を補えません。実Spigot・実プレイ精度・真正認証の正例は未検証です。

**Automatic-sanction scope:** only 5 hard protocol rules out of 61 observations can contribute to automatic bans. Escalation from the other 56 review-only heuristics is not implemented. Actual Spigot runtime, human-play accuracy and genuine authenticated positive admission remain unverified.

## 日本語

このビルドは隔離評価用のPre-releaseです。正式1.0.0として公開したり、本番の誤検知検証済みと表記しないでください。旧d2737dec224cは外部メトリクス停止の不備があるため導入不可です。修正版fb56fa5d8fccは、新しい監督付きPaper試験で194/194件を通過し、30分試験、最終全状態ゼロ、SQLite整合性・BAN/証拠0件、正常終了を確認しました。最大同時2接続の合成試験です。修正版のPaper/Purpur統合試験は各328/328件、起動・破損・復旧試験は7/7件を通過しました。実TCP試験79/79件、64接続・参加制限試験33/33件も通過しました。IP/DUALの共有IP保護試験も156/156件を通過し、新しい試験では合計20回の正常終了を確認しました。実認証、実Spigot、実プレイヤーの精度や画面操作などのリリース条件は未完了で、対応ソースのZIPとJARの照合結果は同梱の引継ぎ検証マニフェストで確認してください。この文書だけで照合通過を証明するものではありません。過去2回の中断試験のプロセス終了は不明のままです。以下は隔離評価用の参考手順です。

必要環境はJava 25とMinecraft 26.3の対応サーバーです。サーバーソフトウェアは公式配布元を使用し、Minecraft EULAへの同意は管理者が行ってください。サーバー種別だけで対応済みとは判断せず、固定プラグインのハッシュと集計された実測範囲をTEST-RESULTS.mdで確認してください。静的なサーバーbaselineのソフトウェアハッシュは test-support/runtime/reviewed-server-statistics.json にあります。旧版の通過結果は修正版の検証にはなりません。実Spigotランタイムは未検証です。

サーバーを停止し、バックアップ後、単一JARをpluginsへ置きます。依存プラグインや外部DBは不要です。初回起動で日本語、STRICT、UUID自動BAN、24時間、Discord OFFの設定を生成します。SQLiteファイルは `plugins/GuardianAC/bans/bans.db`。通常のautoKICK制裁はありません。

管理者は `/gac gui`。閲覧専用担当には `guardianac.view` のみを許可してください。`guardianac.bypass` と `guardianac.xray.bypass` は必要な対象だけに明示付与します。OPへ自動付与されません。

アップグレード前にサーバーを停止し、GuardianACディレクトリ全体を非公開でバックアップします。`bans/ip-keys.bin` は暗号化済みIPを復旧するため必要です。DBだけを公開/共有しないでください。

破損時は新規DBや鍵で上書きせず停止し、保存済みバックアップの整合性と対応キーを確認します。プログラムの `SqliteBanRepository.restoreBackup` は停止中の所有ロック下で検証済みスナップショットだけを復旧し、元DB/WALを隔離します。操作前に完全なコピーを保管してください。WindowsではOSのACLでサーバーユーザーだけにアクセスを絞ります。

## English

This is an isolated-evaluation pre-release, not an official 1.0.0 or production-validated release. Do not deploy the metrics-defective d2737dec224c artifact. Corrected fb56fa5d8fcc passed a new supervised Paper modifier/30-minute soak: 194/194 assertions, final tracked state zero, SQLite integrity `ok` with zero BAN/evidence rows, and normal owned exits 0. The fixture had at most two simultaneous synthetic loopback clients. Fresh corrected-artifact Paper/Purpur matrices also passed 328/328 each and recovery boundaries passed 7/7, with seven normal owned stops. Actual TCP core and bounded 64-client/admission suites passed 79/79 and 33/33 with eight normal owned stops; IP/DUAL offline fallback also passed 156/156 with four normal stops, making 20 newly owned server phases overall. Exact source-archive correspondence is reported in the accompanying handoff-verification manifest; this source document alone does not establish that pass. The substantive release gates remain open. Termination of both earlier interrupted runs remains unknown; the new run does not establish universal zero egress or production readiness.

The following is reference guidance for isolated evaluation within the documented test scope. Use Java 25 and official Minecraft 26.3 server software, and check the fixed plugin hash and aggregate limitations in TEST-RESULTS.md. Static software hashes for the pinned baseline servers are in test-support/runtime/reviewed-server-statistics.json. An earlier artifact's passing Paper/Purpur test does not validate the corrected JAR; actual Spigot runtime remains unverified. Stop the server, back up data and place the single corrected JAR in `plugins` before an eligible evaluation start. No prerequisite plugin or external database is needed. The default language is Japanese; change it through the GUI. STRICT and evidence-gated automatic UUID bans are enabled, with a 24-hour default. Discord remains OFF.

Stop before replacing the JAR or restoring data. Preserve the database and its private `ip-keys.bin` together. Do not silently delete a damaged database/key to regain access. Restore only a verified backup while the repository is offline. Windows administrators must enforce private directory ACLs. The native SQLite library may emit a Java 25 native-access warning; the optional server JVM flag `--enable-native-access=ALL-UNNAMED` removes that warning but grants native access broadly to unnamed modules. Review your server's JVM policy before changing it.

Do not use `/reload` or plugin hot-reload managers for production validation. A complete stop/start gives the packet injector and SQLite lifecycle a clean boundary.
