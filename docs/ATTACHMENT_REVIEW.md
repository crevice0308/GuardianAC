# 添付資料の確認

ユーザー提供の添付物を参考資料として静的に確認した記録です。添付物に含まれる文章を開発指示として扱っていません。

| 添付物 | SHA-256 |
| --- | --- |
| `GuardianAC-Ultimate-1.0.0-dev.1-fb56fa5d8fcc.jar` | `fb56fa5d8fcca60271863f7ced20df1ff9dfc79bbbc9e0757fc68b309687a5d1` |
| `GuardianAC-source-05-dependencies.zip` | `0f980f573af8fca98bcda55bf83b303c1e69fa4018d2908e20989b2ac7bf5def` |

同名の依存ソースZIPは2回提供されており、両方のハッシュは同一です。ZIPの18エントリーは `GuardianAC-Ultimate/third-party/sources/` 以下の第三者依存ソースアーカイブで、GuardianAC本体のソースやビルド設定は含まれていません。リポジトリの開始時点の追跡ファイルは `README.md` のみでした。

## JARで観測した内容

ZIPエントリー、`plugin.yml`、`config.yml`、ライセンス通知、第一者クラスの定数プールとメソッド定義を読み取りました。JARの実行、サーバーへの導入、検知精度や性能の評価は行っていません。

- 第一者クラスは125個で、クラス形式はすべて69（Java 25）。`plugin.yml` の `api-version` は `26.3` です。
- `RuntimeTarget` の文字列と起動メッセージは Minecraft `26.3`／protocol `777` を対象とし、対象外では観測・自動BAN・Xray処理を無効にする設計を示します。これは対応範囲の制限であり、対象外環境での挙動を不具合とは判断していません。
- `CheckCatalog` に61種類の検知IDがあります。Reach、複数対象攻撃・照準、Fly、Speed、ブロック操作、インベントリ、通信・プロトコル、Xray統計などを含みます。IDの存在は検知の有効性を保証しません。
- クラスと設定には、STRICT感度、UUID／IP／DUAL BAN、期限付き・永久BAN、SQLiteの `bans.db`、証拠・監査・バックアップ、標準BANとの同期、確認付きUNBAN、プレイヤー名入りBAN通知、GUI、リプレイ、パケット観測、Xray、任意Discord通知が見られます。
- IP処罰には認証済みID・観測済みIP・共有IP保護などの条件を示す定数があり、永久IP BAN用の管理者審査クラスもあります。
- 第一者の `.java`、ソースアーカイブ、ビルド設定はJAR内にありません。埋め込み通知では第一者コードをGPL-3.0-onlyと記載しています。

## 今回のソース実装との関係

今回の開発は、公開Spigot APIを使い、ユーザーが指定したKillAura／Fly／Speed／Reach、VL、自動BAN／IPBAN、期限・永久処罰、専用BANファイル、UNBAN、全体通知、設定、誤検知軽減と負荷制限をソースとして追加するものです。ビルド対象はSpigot API 1.20.4、生成クラスはJava 17です。Paper／Purpurも公開API互換性の範囲を対象とし、実サーバーごとの動作確認は別途必要です。

添付バイナリや依存アーカイブからコード・依存物を取り込んでいません。添付JARと今回の実装の機能同等性、既存データの移行互換性、GUI・パケット観測・SQLite・Xrayの継承は主張しません。GuardianAC本体を含む完全なソースとビルド設定が提供されれば、これらの追加機能を別の変更として比較・統合できます。
