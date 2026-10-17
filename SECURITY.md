# Security / セキュリティ

## Supported security scope

This internal build is under active validation and has no official production
support claim. Do not publicly post live player records, IP keys, webhook URLs,
database files, undisclosed exploit payloads or detailed evidence containing
private activity. No public security contact/repository has been created yet;
report issues privately to the project operator.

### Historical metrics defect and corrected-artifact scope

Artifact `d2737dec224c` must not be deployed. Its embedded PacketEvents
`bStats(false)` call does not reliably disable the dependency's metrics path.
Unexpected external metrics activity interrupted that artifact's isolated soak;
actual sender and delivery remain unconfirmed. Its process termination is unknown,
so continued activity from the old enabled collector cannot be excluded. A
local-only claim is withdrawn for this artifact and earlier builds containing
the same dependency setting.

The corrected fb56fa5d8fcc build removes that embedded path without changing
other plugins' preferences and passes offline source/classfile regressions. Its
new supervised Paper modifier/30-minute soak passed 194/194 assertions, with
final tracked state zero, SQLite integrity `ok`, zero BAN/evidence rows, and
normal owned JVM/supervisor/runner/log-collector exits 0. Optional bStats, Spark
and startup-version checks were disabled in the run's before/during/after
configuration snapshots. Fresh corrected Paper/Purpur matrices also passed 328/328 each, and recovery
boundaries passed 7/7 with seven normal owned stops. Actual TCP core and bounded
64-client/admission suites passed 79/79 and 33/33 with eight normal owned stops.
The separate IP/DUAL offline-fallback suite passed 156/156 with four normal
owned stops, for 20 newly owned server phases overall. Exact source-archive correspondence is reported in the accompanying
handoff-verification manifest; this document alone does not establish that pass.
Formal release gates remain open.

The earlier corrected-artifact attempt remains interrupted PARTIAL, as does the
older d2737dec224c soak; termination of both is unknown. Possible older statistics
activity and resource contention cannot be excluded. No bStats send is identified
in retained corrected-run observations, which does not prove no attempted sends
or universal zero egress. Standard Mojang game-service paths are distinct from
optional metrics. See [TEST-RESULTS.md](TEST-RESULTS.md) for the completed run,
historical evidence and remaining gates.

### Policy and trust boundaries

- All packet/event input is untrusted. Distinct checks in one correlated family
  cannot count as independent proof. Heuristics cannot by themselves auto-ban.
- Automatic sanctions require repeated hard invariants, confidence, independent
  families, durable matching evidence, committed DB state and current policy.
- No ordinary auto-kick penalty. Persisted bans may disconnect an online player;
  malformed input may be discarded, and new abusive connections may be refused.
- IP equality is not identity proof. Additional shared-address/evidence gates
  apply; both trusted provenance and all observed identities are rechecked in
  the committing transaction. Address observations are retained for at least
  30 days even when evidence payload retention is shorter.
- Native bans with another source marker are never overwritten or pardoned.
- Confirmations are actor-bound, one-use, short-lived and bind exact case IDs.
  A later case is not included in an earlier preview.
- GUI actions are server-side holder actions, not client item metadata. Delayed
  operations recheck permissions/navigation; clicks, drags and transfers cancel.
- Bukkit world/entity/inventory access stays on the main thread. Packet data and
  SQL work have explicit queue, memory and work limits.
- IPs at rest use AES-256-GCM and keyed HMAC lookup. Keys, databases, backups and
  evidence exports remain private. Webhook secrets are never echoed.

This encryption applies to GuardianAC's SQLite records. Minecraft's native IP
ban synchronization necessarily writes the address in the standard plaintext
`banned-ips.json` format, and the server itself may log connecting addresses.
Protect those server files/logs with operating-system access controls, or disable
native synchronization if that format is inappropriate. GuardianAC does not
silently change permissions of server files owned by other components.

Native synchronization is revision-fenced and eventually reconciled, not atomic
with SQLite. Its bounded incremental sweep can take longer with a large history.
An online disconnect is an attempt: a trusted third-party plugin can cancel
`PlayerKickEvent` or throw during the kick. The exact-session delivery marker
suppresses repeated attempts for that session, so such a player may remain
online until another lifecycle change. Stored/native bans and later login
checks remain separate. This build does not override another plugin's veto;
operators must test that interaction before deployment.

### Recovery and retention

SQLite WAL, integrity checks, evidence hashes, transactions, an exclusive owner
lock and verified snapshot backups protect storage consistency. Corruption or
missing/mismatched keys does not cause silent recreation or sanction removal.
Existing native bans remain owned by their original source. A failed storage
initialization disables the plugin; operators must resolve it rather than
assuming full protection remains available.

Active-ban evidence remains pinned. Retention/capacity prunes old unpinned
payloads and retains relevant metadata/audit. A full pinned evidence budget
prevents new automatic sanctions rather than sanctioning without saved proof.
The payload budget is not a hard cap on total SQLite/WAL/history/backup disk.

### Audit coverage and limits

Automated tests cover SQL injection, traversal/symlink hazards, encrypted IP
tampering, corrupt storage, missing keys, backups, restart/expiry, partial DUAL
unban, case/ownership races, queue capacity, evidence policy, GUI/confirmation
state, private exports and packet/masking bounds. Earlier hash-qualified
real-server fixtures exercised their assembled JARs and native ban lists; their
results do not validate the corrected artifact. The new fb56fa5d8fcc supervised
modifier/soak validates bounded movement contexts, lifecycle cleanup and final
database integrity. Its separate 328/328-per-server matrices and 7/7 recovery
boundaries validate bounded server-side administration, synthetic-provenance
permanent-IP paths, native ownership and recovery. The 156/156 actual-TCP IP/DUAL suite verifies offline UUID fallback and
shared-address peer protection; actual authenticated positive admission remains
a distinct gap. Actual TCP malformed-input sanctions,
restart enforcement, ore/cave wire paths, same-UUID overlap and bounded
64-client/admission behavior now have their own corrected-artifact passes. Exact scopes and build hashes are recorded in
the aggregate TEST-RESULTS.md; detailed runtime reports are excluded from this public package.

Tests are not a proof of absence of vulnerabilities. Packet decoder issues
before plugin callbacks and volumetric DDoS require server/network controls.
Arbitrary third-party plugins can alter mechanics or access server data; they
are outside a complete isolation boundary. Observed unknown or non-native
protocol versions are conservatively exempt from affected rules/masking. A proxy
that presents protocol 777 cannot be identified as Bedrock merely from that
number; Geyser/Floodgate are not certified or automatically identified by this
build. Operators must isolate unsupported clients or explicitly configure the
documented bypass permissions before evaluation. Windows filesystem security
needs operator-managed ACLs; Linux-only tests do not prove Windows behavior.

## 日本語

この内部版は本番検証済みではありません。IP鍵、Webhook、DB、実プレイヤーの
行動証拠を公開しないでください。誤BAN防止を優先し、推測だけで自動制裁しません。
保存失敗、容量不足、整合性不明のときに処理を強行しない設計です。

バックアップには対応する秘密鍵が必要です。破損を見つけたら元データを保持し、
検証済みスナップショットから停止中に復旧してください。外部の管理者/プラグインが
所有するBANは解除しません。詳しい実測範囲と未検証点はテスト結果を確認してください。
