# 1.0.0-dev.1 — evaluation pre-release

- Java 25 / Minecraft 26.3。既存の未難読化JARを変更せず公開する評価用dev版です。
- 61観測判定のうち自動処罰対象は証拠条件付きの5つの厳密規則。残る56は確認用で、Fly・Speed・Aim・Xray等の自動処罰経路は未実装です。
- パケット／イベント観測、GUI、SQLite、共有IP保護などの土台を含みます。全チート自動BAN完成版ではありません。
- 実Spigot、実人間プレイの精度、真正認証の肯定経路は未検証です。
- GPL-3.0-onlyの対応ソース・必要な依存ソースを同梱。公開パッケージから詳細な試験記録を除き、[集計概要](https://github.com/crevice0308/GuardianAC/blob/v1.0.0-dev.1/TEST-RESULTS.md)を添えています。

既定はSTRICT・条件付きUUID自動BAN・24時間です。検証用環境で校正し、必要なら自動BANをOFFにしてください。コード変更や追加機能の完成を意味するリリースではありません。
