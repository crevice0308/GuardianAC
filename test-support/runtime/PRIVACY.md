# Privacy gates for disposable runtime fixtures

This is current source/harness guidance. It is not a new runtime result. Detailed historical recordings, player/case records, live configurations and machine-specific copies are excluded from this public source package. Aggregate results and remaining uncertainty are described in [TEST-RESULTS.md](../../TEST-RESULTS.md).

## Separate the plugin and server statistics paths

The corrected fixed GuardianAC JAR uses the reviewed embedded PacketEvents source change that removes its optional statistics bootstrap. Replacing that builder with unmodified upstream source can restore the behavior. The plugin-source audit and exact artifact hash are therefore required independently of server configuration.

Official Paper/Purpur server statistics are a separate sender. For the pinned software, the server reads plugins/bStats/config.yml and upstream defaults can enable the collector when the file is missing. A quiet log or short smoke test does not establish that it is disabled. The source fixture requires explicit disabled settings and independently reviewed server bytes.

The optional sender's public schema includes a persistent metrics identifier, OS/Java/server version information and aggregate player counts. No captured metrics identifier or payload is distributed here. Standard Minecraft service discovery/public-key requests and bootstrap downloads are distinct from optional statistics. No universal zero-egress or network-isolation claim is made.

## Static official software manifests

[reviewed-server-statistics.json](reviewed-server-statistics.json) pins official wrapper/runtime JAR hashes, class hashes and the reviewed branch facts. [server-statistics-static-audit.json](server-statistics-static-audit.json) records static software facts used by the regression tests. These are software identities and public implementation schema, not individual runtime records.

The pinned baseline versions are Paper 26.3 build 179 and Purpur 26.3 build 2648. audit_server_statistics.py parses class bytes without executing them. It checks that the disabled bStats branch skips collector construction and scheduler submission; the spark disabled branch skips module enable; and the relevant startup update/command gates skip their optional actions. Spark construction and all other possible code paths are not certified absent by an enable-gate check.

Changed or missing server bytes fail closed and require a new review. These software facts do not generalize automatically to other versions and do not substitute for runtime observations.

## Fresh fixture preparation

fixture_privacy.initialize_privacy() is for a new disposable copy only. It sets explicit false statistics/logging flags, a fixed synthetic fixture input, disabled spark and startup checks, and empty copied startup commands. Existing enabled or incomplete fixtures are rejected rather than rewritten in place.

Strict YAML parsing rejects duplicate keys, aliases, non-mappings and string values in place of booleans. Paths are checked for symlinks. Parser errors do not echo configuration contents. Missing parser dependencies fail closed before Java starts.

The server_harness.Server constructor validates fixture safety and privacy before even its bounded Java-version probe, and checks again immediately before launching its owned server child. Injected JVM-option environment variables are rejected. Reviewed plugin allowlists and the exact GuardianAC artifact hash remain required. The generated descriptor records flags and software/configuration hashes without exposing configuration contents or a metrics identifier.

Keep generated logs, worlds, databases, security keys and evidence outside the distributable source tree. Do not upload raw output merely because the accounts were synthetic; publish only the separately reviewed aggregate information needed for the purpose.

## Check the actual import plan

```sh
python -B test-support/runtime/verify_harness_binding.py /absolute/path/to/planned/runner.py
```

The checker reads the selected runner without importing or executing it, resolves its known support paths and requires byte-identical safety/privacy/lifecycle helpers, client support and reviewed server manifest. It also validates the selected distinct loopback port pair. A stale copied helper or shadow import is rejected.

This verifies the supplied environment and bytes, not a later modified environment. Repeat the check after changing any runner, helper, manifest or environment. The gate is a defensive harness check, not an OS sandbox or permission to run a server.

## Offline checks and owned lifecycle

Use the pinned fixture requirements with Python 3.10+.

```sh
python -B test-support/runtime/test_fixture_safety.py
python -B test-support/runtime/test_fixture_privacy.py
python -B test-support/runtime/test_server_statistics.py
```

The regression tests use synthetic files and mocked server operations. They do not claim to inspect absent official server JARs in CI. A local explicit bytecode audit additionally needs the exact reviewed initialized baselines; it does not start those servers.

[PROCESS-LIFECYCLE.md](PROCESS-LIFECYCLE.md) describes supervision of newly owned children, bounded probes/lifetimes, parent-loss handling, redacted lifecycle output and normal-stop checks. No unrelated or unknown process is a permitted cleanup target. Historical unknown termination is not repaired by a new passing fixture.

Any live test requires the operator's Minecraft EULA acceptance and a separate disposable loopback fixture with synthetic identities only, no real credentials/player data, preserved preflight checks and tracked normal shutdown. Optional external statistics are not authorized by preparing a fixture. Report actual observed runtime scope separately; quiet logs, a brief wait and absence of a successful HTTP response are insufficient to establish no transmission.
