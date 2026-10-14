# 検証記録

## ビルド・自動テスト

| 環境 | 結果 |
| --- | --- |
| Temurin JDK 17.0.20.1、Maven 3.9.9 | `clean verify`／`verify` 成功、154件成功、失敗・エラー・スキップ0 |
| Temurin JDK 21.0.12.1、Maven 3.9.9 | `verify` 成功、154件成功、失敗・エラー・スキップ0 |
| 生成クラス | Java 17形式（major 61） |
| JAR | Spigot APIを同梱せず、Gsonを専用パッケージに移動して同梱。依存ライセンスを収録 |
| 差分とハーネス | `git diff --check`、`node --check scripts/server-smoke.cjs` 成功 |

テスト内訳は、検知エンジン17、VL管理8、BAN保存16、IP検証2、期間解析32、コマンド26、設定34、Bukkit観測14、処罰ライフサイクル5です。通常ジャンプ・落下、支えのない滞空、継続的速度超過、Reach余裕、KillAuraの方向／頻度／対象数、猶予・履歴・権限、範囲外設定、永続化前の無処罰、保存失敗、期限境界、破損データ、並行更新を含みます。

クラウド内では、JDKとMavenキャッシュを `/workspace/.tools` と `/workspace/.cache` に配置し、セッションのHTTPプロキシをMavenの一時設定に反映しました。JavaにはOSのCAストアを指定しました。TLS・チェックサム検証は無効化していません。通常の開発環境では次で再現できます。

```sh
./mvnw -B clean verify
```

CIにもJava 17／21の同じビルドを登録しました。ローカルの検証結果と、GitHub Actionsの実行結果は別です。

## 実サーバー

| サーバー | Java | ハーネス結果 |
| --- | --- | --- |
| Purpur 1.20.4、build 2176 | 21.0.12.1 | 10項目成功、プロセス終了コード0 |
| Paper 1.20.4、build 499 | 21.0.12.1 | 10項目成功、プロセス終了コード0 |

公式配布元のメタデータとチェックサムを照合して取得したサーバーを使いました。ループバックだけにバインドした使い捨て環境で、オフラインのプロトコルクライアント `minecraft-protocol@1.60.0` を接続しました。オンライン認証の検証ではありません。Minecraft EULAは利用者の明示許可を受けて、このテスト環境に限り設定しています。

各サーバーで次の10項目が成功しています。

1. 起動とGuardianACストレージ正常状態。
2. 手動IPBANの保存と、処罰されたプレイヤー名を含む切断画面。
3. BAN対象UUIDと、同じIPの別UUIDの接続拒否。
4. UNBAN後に両UUIDが再接続。
5. 1秒BANの期限経過後の再接続。
6. 正常終了後の再起動とストレージ復元。
7. 永久UUID BANの保持。同じIPの別UUIDは接続可能。
8. 再起動後のUNBANと元UUIDの再接続。
9. Survivalで飛行能力のないプレイヤーが高度300で静止し、実際のFly検知→VL蓄積→自動UUID/IPBAN。`automatic:FLY`・期限・IPの保存、名前付き全体告知、両UUIDの接続拒否。
10. 自動BANをUNBANし、再接続。

最後にテスト用BANを解除し、各サーバーを正常停止しました。実際の画面描画の目視確認ではなく、プロトコル上の切断理由とサーバー出力を検証しています。切断理由の圧縮解除がTCP終了より遅れる場合を待つよう、ハーネスを修正しました。

このクラウド内の結果・サーバーログは、`/tmp/guardian-runtime-result.log`、`/tmp/guardian-paper-result.log`、`/workspace/.cache/guardian-server/smoke.log`、`/workspace/.cache/guardian-paper-server/smoke.log` にあります。テストデータや配布サーバーJARはリポジトリに含めていません。

### 実サーバー試験の再現

自分でEULAに同意した使い捨てのPaper/Purpur 1.20.4サーバーを準備し、生成したプラグインJARを `plugins/` に配置します。既存の本番サーバーを指定しないでください。ハーネスはテスト用アカウントのBANデータとワールドに変更を加えます。

必要な `server.properties` は `server-ip=127.0.0.1`、明示的な非特権ポート、`online-mode=false`、`enable-rcon=false`、`enable-query=false`、`allow-flight=true` です。最後の設定はVanilla側の浮遊切断を避けるためで、プレイヤー自身に飛行能力は与えません。ハーネスは設定やEULAファイルを自動作成せず、条件が異なれば拒否します。

```sh
npm install --prefix /tmp/guardian-smoke --no-audit --no-fund minecraft-protocol@1.60.0
NODE_PATH=/tmp/guardian-smoke/node_modules JAVA_HOME=/path/to/jdk \
  node scripts/server-smoke.cjs /path/to/disposable-server
```

プロキシ環境では、その環境が提供する信頼済みCAとJVMプロキシ設定を `JAVA_TOOL_OPTIONS` 等で引き継いでください。サーバーログには隔離オフライン試験で不要なMojang公開鍵取得の接続エラーがありましたが、GuardianACの例外や今回の接続拒否原因ではありません。オンライン認証の成功を示す結果には含めていません。

## 残る確認

- Spigot本体での起動と、1.20.4以外のサーバー・Minecraft版。
- 実際のチートクライアントによるKillAura／Speed／Reachの一連の動作。
- 通常のPvP、独自移動、特殊アイテム、変動遅延、大人数での誤検知率と負荷。
- オンライン認証、信頼するプロキシでのUUID/IP転送、過去・オフライン利用者を含む共有IPへの影響。
- 添付開発版のGUI・SQLite・パケット観測・Xray等の移植、設定／BANデータ移行。これらは本体ソース未提供で、今回の公開API実装との機能同等性はありません。

今回の結果は要求機能のビルドと指定シナリオの動作を示し、全チートの検知、誤検知ゼロ、大規模サーバーでの性能保証を示すものではありません。
