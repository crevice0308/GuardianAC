# GuardianAC Ultimate — 1.0.0-dev.1

**評価用Pre-releaseです。全チートの自動BAN完成版ではありません。**

Java 25 / Minecraft 26.3 を対象とする、単独JARのサーバー側アンチチート開発版です。61の観測判定のうち、自動処罰に寄与するのは証拠条件を満たす5つの厳密なプロトコル規則です。残る56判定は管理者の確認用です。**Fly・Speed・Aim・Xrayなどの推測判定から自動処罰へ進む経路は未実装**で、STRICTや閾値変更では有効になりません。

実Spigotの起動、実人間プレイでの誤検知・見逃し率、真正なオンライン認証を通した肯定的なIP処罰経路は未検証です。クライアントの描画内容や意図を直接観測することはできません。

## 実装済みの土台

- パケットとBukkitイベントの観測、証拠収集、管理通知
- 54スロットGUI、日本語既定・英語切替、検知ごとの設定
- SQLiteによるBAN・証拠・監査の保存、期限付き／永久UUID・IP・DUAL処理
- 保存成功と証拠の再確認を前提にした処罰、共有IP保護、永久IP用の管理者承認
- 確認付きUNBAN、リプレイ用観測、負荷・保存キューの制限
- 観測可能な範囲のXray抑制と統計。完全なXray検知の保証ではありません
- 任意Discord通知は既定OFF。手動設定しない限り利用しません

通常の自動KICK処罰はありません。成立したBANを既存接続へ反映するための切断はあります。前提プラグインや外部DBは不要です。

## 評価時の設定

初期設定はSTRICT、証拠条件付き自動UUID BAN、24時間です。OPは自動除外されず、guardianac.bypass は既定falseです。正規の移動や追加プラグインを含む校正には自動BANをOFFにして検知・証拠を確認してください。

- `/gac gui` → 検知管理／システム設定
- GUI変更は保存されます。設定ファイルを手で変更した場合は `/gac reload`
- 個別の検知やVLだけでBANが確定するわけではありません

既存サーバーへそのまま投入せず、バックアップを取った隔離評価環境で確認してください。詳細は[導入](INSTALL.md)、[設定](CONFIGURATION.md)、[機能と残課題](docs/FEATURE-MATRIX.md)を参照してください。

## ソースからのビルド

JDK 25と、初回に公式Gradle・Maven・Spigot・CodeMC配布元へ接続できる環境が必要です。

```sh
./gradlew --no-daemon clean test shadowJar sourcesJar
python3 tools/verify_jar.py build/libs/GuardianAC-Ultimate-1.0.0-dev.1.jar
python3 third-party/sources/verify_sources.py --plugin-jar build/libs/GuardianAC-Ultimate-1.0.0-dev.1.jar
```

単体プラグインは `build/libs/GuardianAC-Ultimate-1.0.0-dev.1.jar` です。`-plain.jar` は配布対象ではありません。テストサーバーを起動する場合のMinecraft EULA同意は、その管理者が別途行ってください。

## 検証の読み方

[集計検証概要](TEST-RESULTS.md)に、固定JARでの既存の自動試験・隔離試験と未検証範囲を区別して記載しています。この公開ソースには詳細な実行ログ・個別プレイヤー／ケース記録を含めていません。通常のテストソースと、対応する155ビルド入力のハッシュ一覧は保持しています。テスト件数は実プレイヤー精度の証明ではありません。

[配布物の対応](docs/PRERELEASE-PROVENANCE.md) / [モジュール一覧](docs/MODULES.md) / [English](docs/README-en.md)

第一者コードはGPL-3.0-onlyです。[LICENSE.txt](LICENSE.txt)と[第三者通知](THIRD-PARTY-NOTICES.md)を参照してください。必要な依存ソース、埋め込みPacketEventsの変更ソース、ライセンスを同梱しています。
