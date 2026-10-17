# Runtime message localization / 実行時メッセージの多言語対応

> Public package scope: detailed generated execution records are omitted. See the [aggregate verification summary](../TEST-RESULTS.md) for retained totals and limitations. Ordinary source and static software manifests remain.

## 対象と設定

`language: ja` が初期設定です。`language: en` で英語へ切り替えます。
未設定・未知の値は日本語へ戻ります。サーバー設定の言語を使用し、
プレイヤーのクライアント言語によって制裁や通知の内容を変えません。

`GuardianPlugin` のコマンド案内・成功／エラー応答・管理者通知・
Discord通知・接続保護メッセージ・BAN画面は、UTF-8の次の2ファイルを
使用します。現在のカタログは同一の60キーを両言語に含みます。

- `src/main/resources/locales/runtime_ja.properties`
- `src/main/resources/locales/runtime_en.properties`

`RuntimeMessages` は起動後に変更されないカタログを読み込み、置換引数を
文字列としてそのまま挿入します。置換引数をテンプレートとして再解釈
しません。非同期接続判定でも共有する可変フォーマッターやBukkit呼び出しを
使いません。BAN画面の本文と期限表示は1回取得した設定言語で組み立てます。

## 意図的に固定している表記

- `/gac` サブコマンド、権限・設定・検知ID、UUID/IP/DUALなどの種別、
  ケースID、ISO日時は共通の技術表記を維持します。
- BAN画面の `GUARDIANAC ULTIMATE` / `ACCESS REVOKED` は指定された
  デザインの見出しとして両言語で維持し、説明・理由・状態を翻訳します。
- `/gac status` は日本語の説明を独立した先頭行に追加します。従来の
  英語の機械読取用メトリクス行はラベル・並び順とも変更しません。
  英語設定では従来のメトリクス行をそのまま返します。
- サーバーの起動・内部エラーの技術ログ、例外文、証拠JSONの項目名、
  保存済みの監査理由は英語のままです。既存履歴の書き換えはしません。
  現在のシステムエラー17種は、Discord向けに両言語へ変換します。
  未知の診断文字列は外部通知へ転送せず、一般的な確認案内へ置き換えます。

IPアドレスやWebhook URLを新たに表示する変更は含みません。
共有IPの一致・パケット防御通知には、それだけで不正の証明にならない旨を
残しています。BAN画面や全体チャットには詳細な検知ロジックを表示しません。

この文書の対象は `GuardianPlugin` の実行時メッセージです。GUI固有の文面は
既存の `GuardianSettings.text(ja, en)` を使用します。プラグイン全体や
サーバー・他プラグインが生成する全メッセージの翻訳完了を意味しません。

## Scope and stable interfaces

Japanese is the default and the fallback for unknown language values. English uses
`language: en`. Messages follow the server setting, not each player's client locale.
The two immutable UTF-8 catalogs cover GuardianPlugin command responses, admission,
BAN screens, staff notices and Discord notifications. Both contain the same 60 keys.
Substitutions are literal and thread-safe; a composed admission BAN screen captures
one language for its explanation and duration.

Commands, permission/configuration/check IDs, ban-type tokens, case IDs, timestamps,
and the requested BAN-screen branding stay stable. Japanese status adds one
explanatory line before the unchanged English machine-readable metrics. Technical
console diagnostics, exception strings, persisted audit reasons and evidence schema
keys intentionally remain English. The 17 current system diagnostics have explicit
localized Discord equivalents. Unknown diagnostic strings are not forwarded to
Discord, preventing accidental address or secret disclosure through that path.

No additional raw addresses or webhook URLs are displayed. The packet and
shared-address notices retain their uncertainty warnings. No enforcement, sanction,
permission, confirmation or persistence policy was changed by this message work.
GUI-specific text continues to use the existing bilingual settings helper; this is
not a claim that every server-generated or third-party message is translated.

## Verification / 検証

- `RuntimeMessagesTest` covers every key in both languages, placeholder/literal
  substitution, fallback, concurrency, catalog immutability, command tokens,
  status compatibility, BAN-screen content, diagnostic call-site coverage and
  unknown-diagnostic privacy. Its 69 cases passed within the retained 796-test Java suites for d2737dec224c and corrected fb56fa5d8fcc. The later offline harness aggregate has 228 Python tests across 15 suites; the five localization tests below are included in that total.
- `python -m unittest discover -s test-support/localization -p 'test_*.py' -v`
  checks catalog/source coverage and explicit bilingual fixture patterns without a
  JVM or server. The five static checks passed during this edit.
- `python -m unittest discover -s test-support/load-limits -p 'test_observations.py' -v`
  accepts the two exact localized connection-rate messages and rejects unrelated
  disconnect reasons. All ten checks passed during this edit.
- Runtime helpers accept explicit Japanese and English variants for confirmation,
  export, reload and relevant error messages. This does not weaken success into a
  generic response check or count older English-only artifact runs as new passes.
- Artifact d2737dec224c passed Java compilation, all JUnit cases, packaged-resource
  inspection and 328 assertions on each real Paper/Purpur server. That earlier
  artifact has an embedded metrics defect and must not be deployed. Those runtime
  results do not validate corrected fb56fa5d8fcc. Its own supervised modifier/30-minute
  soak and 328/328-per-server Paper/Purpur matrices now pass, including settings,
  localized runtime paths and restart persistence. See the
  [aggregate verification summary](../TEST-RESULTS.md). Graphical text layout and messages emitted by other plugins
  remain outside that coverage.
