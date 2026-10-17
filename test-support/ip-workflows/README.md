# Offline identity and shared-IP safety regression

> Public package scope: detailed generated execution records are omitted. See the [aggregate verification summary](../../TEST-RESULTS.md) for retained totals and limitations. Ordinary source and static software manifests remain.

This extension uses actual loopback TCP clients speaking Java 26.3 / protocol 777 against an exact approved replacement GuardianAC JAR. It never feeds fabricated `Sample` objects, creates sanctions, or invokes the automatic-ban method. Its separate, console-only `RealClientFixture` helper observes packet/player/storage state and builds a small stone platform. It does not seed trusted associations or change the server's native offline identity state.

## New live regression scope

Two fresh Paper 26.3 offline fixtures, requesting IP and DUAL respectively, with one-hour UUID/IP durations and permanent IP disabled. Each case includes a full stop and restart.

- A clean real TCP peer first joins from the same loopback source as the later malformed actor.
- Both actors really join. Storage must report two distinct observed UUIDs, zero trusted accounts, no trusted target association, and native online-mode false. No trust is seeded.
- A bounded invalid held-slot 15 / pitch 100 trace automatically sanctions the evidenced actor as **UUID only** for both requested IP and DUAL configurations.
- The clean same-IP peer must remain connected and unflagged during the other actor's enforcement, then be admitted again. A new same-IP UUID and a separate-IP clean actor must also be admitted.
- The banned UUID must remain denied when it switches source address. All outcomes and ownership/expiry must survive a restart.

The read-only storage report checks one automatic-policy sanction, case/evidence links, payload SHA256 and byte counts, actual raw slot/pitch observations from independent hard-evidence families, temporary expiry, no protected IP data/native IP ban, native UUID ownership/expiry, SQLite integrity and foreign keys, normal exit, no additional bans, and no restricted-IP advisory for safe sharing. Reports omit ciphertext and lookup tokens.

## Authentication limits

All identities are deterministic offline fixtures, with loopback sources 127.0.0.31 and 127.0.0.32 and destination literal `127.0.0.1` on the selected Paper fixture port (default `26731`). Optional `GUARDIAN_PAPER_PORT` and `GUARDIAN_PURPUR_PORT` overrides must be supplied together as distinct canonical decimal integers from `1024` through `65535`; the runtime preparation, validation, client connection and handshake consume the same pair. This does **not** validate Microsoft authentication. The production policy/storage unit suite's positive authenticated cases must be identified as explicitly synthetic combinations of a native-authenticated eligibility flag and a persisted trusted exact-target association. Neither alone, nor their fixture combination, proves a real authentication handshake. The live offline test does not isolate a later SQL transaction race recheck.

## Running

Require independently obtained initialized official Paper 26.3 and Purpur 26.3 baseline directories (Purpur is only a required runtime-harness environment value), JDK 25, adjacent runtime/client support, an approved replacement artifact, and explicit Minecraft EULA approval. No Minecraft binaries or EULA acceptance are distributed here.

Set the runtime variables in `../runtime/README.md`, plus:

- `GUARDIAN_IP_EXPECTED_SHA256`: exact approved replacement hash, matching `GUARDIAN_PLUGIN_SHA256`; never the historical unsafe hash
- `GUARDIAN_IP_BUILD_OUTPUT`: isolated helper compilation output
- `GUARDIAN_IP_FIXTURE_JAR`: that output's `RealClientFixture.jar`
- `GUARDIAN_IP_OUTPUT`: isolated JSON evidence output
- Optional `GUARDIAN_RUNTIME_SUPPORT` and `GUARDIAN_CLIENT_SUPPORT` overrides

Only after the replacement JAR and exclusive server/CPU window are approved:

1. `python test_ip_workflows.py` (7 lightweight offline tests, no Java)
2. `bash fixture/build.sh`
3. `python run_ip_workflows.py FRESH_RUN_NAME`

The strict runtime allowlist is unchanged. The helper retains the already permitted `RealClientFixture.jar` name and checks the exact selected offline `127.0.0.1` Paper endpoint at enable time through the shared test-only `FixturePorts` guard. The reviewed harness must supply the exact `guardian.fixture.variant=paper-26.3` launch marker; a Purpur marker, missing marker or mismatched configured port is rejected. Its build script compiles the shared guard, so previously built fixed-port helper JARs must not be reused with a custom-port plan. Fresh output names prevent overwriting old evidence. Each malformed phase is capped at 50 slot/pitch cycles, approximately 3.33 invalid packets/second, plus an isolated pair. Servers run sequentially and stop normally, including failure cleanup. The runner exits nonzero on any failed assertion or fixture error.

One-hour expiry is verified from timestamps; the test does not wait one hour. This adds no Purpur/Spigot, rendered vanilla-client, capacity, production-readiness or generic attack-resilience claim. The original real-client 79-assertion suite remains unchanged. Runtime folders contain generated IP security keys and must never be packaged as deliverables.
