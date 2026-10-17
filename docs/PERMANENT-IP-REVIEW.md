# Administrator-reviewed permanent IP restrictions

> Public package scope: detailed generated execution records are omitted. See the [aggregate verification summary](../TEST-RESULTS.md) for retained totals and limitations. Ordinary source and static software manifests remain.

Permanent IP restrictions require explicit administrator review. An address match does not establish that accounts belong to the same person. A shared-address restriction can affect family, housemates and unrelated people using the same network.

## Eligible starting point

With IP or DUAL mode, IP duration `0`, and `allow-permanent-ip` enabled, a qualifying automatic decision creates an ordinary UUID sanction with the configured UUID expiry plus an encrypted, provenance-bound review candidate. It does not apply an IP restriction. The address-association history is retained for at least 30 days even when evidence payload retention is shorter; pending candidates also pin necessary observations. Candidate creation and application independently require the supported native online-mode environment, a trusted target observation and exactly one observed identity on the address. Offline, proxy, unknown and shared-address cases fail closed. Existing legacy records and temporary-DUAL upgrades are not admitted by this workflow.

## GUI workflow

1. Open the source UUID case in BAN management. Administrators with both `guardianac.admin` and `guardianac.view` can select **Review permanent IP candidate** (slot 39).
2. The separate 54-slot review shows the exact address only in its administrator-only preview, the source UUID case/unchanged expiry and the exact evidence identifier and SHA-256 digest.
3. Select **Inspect all evidence pages** (slot 28). Each page contains all fields from the bound JSON, with long lines wrapped rather than omitted. Page navigation is at 45/50; return to review is 49. Every page must have been displayed before the evidence acknowledgement is enabled.
4. Explicitly acknowledge the fixed evidence (slot 30) and the indefinite shared-address impact (slot 32). The latter explains that a shared IP is not proof of the same person and that the source UUID expiry remains unchanged.
5. Continue (slot 46). The actor-bound request and audit entry must persist before the final confirmation becomes available. The final token lasts at most 30 seconds from issue. Confirm at 46 or cancel at 52.
6. The repository revalidates source, candidate, address, evidence, current policy and trusted association, then claims the exact actor/session/candidate-bound 500 ms authorization lease immediately before committing. Success creates a separate permanent IP-only case. The source UUID case and expiry are unchanged.

Closing, navigating away, loss of permission, disconnect, session replacement, source-case unban, policy change and expiry invalidate an uncommitted confirmation. The exact address is never included in ordinary histories, the review audit or approval-token text. A later close cannot undo a successfully claimed and committed restriction.

The applied IP case can later be removed with the existing **Unban IP only (this case)** action at slot 37 and its 46/52 confirmation/cancel buttons. Other cases and active UUID restrictions remain.

## Audit and evidence after approval

**Permanent IP review audit** (case slot 41) is available independently of candidate eligibility, including after approval, expiry or revocation. Both source and resulting-case IDs resolve the same bounded review chain. It shows actor, time, request/cancel/result, reason code and resulting case without address or bearer token. Each audit view shows the newest 36 events.

The resulting case links its retained source proof. Ordinary replay rendering unwraps only the known one-level approval envelope and displays the source case, evidence ID and SHA-256 context. It does not follow arbitrary nested wrappers.

## Explicit bounds and verification scope

- At most 8 review/audit sessions are retained concurrently by the controller; at most 8 confirmation tickets are active.
- Candidate admission reserves space for the later linked evidence envelope; an otherwise valid UUID sanction may therefore have no review candidate when its evidence is too large. A review renders up to the storage evidence limit of 1 MiB, with at most 131,072 wrapped display lines and bounded JSON depth. Nothing is silently omitted to enable approval. Unsupported or oversized evidence fails closed; oversized evidence reports an explicit display-limit error.
- Each evidence page has at most 36 items, each containing at most 6 lines of 96 characters. Every page must be visited. Loading and audit phases expire after 10 minutes in that phase. Loaded evidence review remains available until the candidate deadline or the known source UUID expiry, so complete review is not rushed. The final confirmation still expires after 30 seconds.
- Click throttling and exact server-owned inventory/session binding cover ordinary clicks, shift/hotbar/drop variants, drags, repeated clicks and delayed callbacks.
- The production constructor uses the actual Bukkit session lookup and native-environment check. A package-private deterministic test seam is not exposed by any command or configuration option. A synthetic native-environment test does not prove real Mojang authentication.

Historical artifact d2737dec224c passed 79 permanent-IP assertions on each tested Paper/Purpur server, including complete synthetic-trust GUI reachability and separate actual offline-client refusal. That artifact has an embedded metrics defect and must not be deployed. The corrected fb56fa5d8fcc matrices independently passed 79 permanent-IP assertions on each Paper/Purpur server, included in each 328 total, with synthetic positive provenance, actual offline production refusal, and normal restart/shutdown evidence. See [aggregate verification summary](../TEST-RESULTS.md). They also do not establish external Microsoft authentication or real-player false-positive rates. Consult TEST-RESULTS.md for the exact hashes, full scopes and remaining gates.
