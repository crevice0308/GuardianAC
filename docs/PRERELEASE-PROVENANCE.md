# Public-source provenance

This pre-release distributes the existing, unobfuscated 1.0.0-dev.1 JAR, unchanged: SHA-256 `fb56fa5d8fcca60271863f7ced20df1ff9dfc79bbbc9e0757fc68b309687a5d1`, 18,399,808 bytes. It targets Java 25 / Minecraft 26.3. It is an evaluation build, not completed all-cheat automatic enforcement.

The implementation has five hard protocol predicates eligible for evidence-gated automatic bans and 56 review-only heuristics. Fly/Speed/Aim/Xray heuristic escalation to automatic punishment is not implemented. Actual Spigot runtime, human-play accuracy and genuine native-authenticated positive admission remain unverified.

## Clean public source scope

The package contains matching first-party source, ordinary unit/fixture source, build files, licenses, embedded dependency changes and required corresponding dependency source. Production code, resources and all 155 recorded build-bound inputs are unchanged. The fixed JAR is not rebuilt or replaced for publication.

Detailed generated runtime reports, historical raw/structured execution records and individual test player/case data are excluded. [Aggregate verification](../TEST-RESULTS.md) is independently written from the retained totals and explicitly preserves their limitations. The single retained docs/reports/build-inputs.json is a nonidentifying software path/size/hash manifest required by the verifier. Static software/protocol provenance and test-input literals are ordinary source inputs, not captured player records.

Documentation is adjusted to this narrower public package. Its ZIP hash and file count are recorded in the release SHA256SUMS and SOURCE-CORRESPONDENCE.json. Do not substitute an older archive hash. Temporary transport parts and one-time publication automation are excluded from the clean source commit and source ZIP.

## Verification

Extract the published source ZIP into a new empty directory. From that matching source tree, run tools/verify_source_handoff.py against that same ZIP and the fixed release JAR. It verifies exact package membership/content and the 155 build inputs. Running it against a different older tree/archive must fail.

Source correspondence does not certify human-play precision, complete features or arbitrary-environment reproducibility. See [verification limits](../TEST-RESULTS.md), [feature gates](FEATURE-MATRIX.md), [license](../LICENSE.txt) and [dependency notices](../THIRD-PARTY-NOTICES.md).
