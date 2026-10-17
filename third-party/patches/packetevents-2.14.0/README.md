# GuardianAC's embedded PacketEvents 2.14.0 metrics removal

This is a local GPL-compatible source modification, dated 2026-10-14, to the
PacketEvents copy privately relocated inside GuardianAC. It is not an upstream
PacketEvents release. The original copyright and GPL header remain intact.

## Why a patch is necessary

The pristine 2.14.0 source makes `PacketEventsSettings.bStats(boolean)` a
deprecated no-op and `isbStatsEnabled()` always returns true. Its
`SpigotPacketEventsBuilder` unconditionally creates `new Metrics(plugin, 11327)`
and registers a `packetevents_version` chart in `init()`. Consequently, the old
GuardianAC call to `bStats(false)` did **not** disable reporting. That ineffective
call is removed from `PacketAdapter`.

## Preferred source and reproducible assembly

- `../../sources/packetevents-v2.14.0.tar.gz` is retained byte-for-byte. SHA-256:
  `0474b4a507699a9eef60d6676a84a5f086858786b0c155b2aed6addb2edb6e49`.
- `src/main/java/io/github/retrooper/packetevents/factory/spigot/SpigotPacketEventsBuilder.java`
  is the complete preferred editable source actually compiled for this variant.
- `disable-embedded-metrics.patch` is the complete unified diff against that file
  under the pristine archive's `spigot/src/main/java` tree. It removes two imports
  and replaces the metrics creation/chart registration with a dated comment.
- `../../../gradle/embedded-packetevents.gradle`, applied by the root build,
  verifies the pristine archive hash and the exact three transformations before
  compilation. It fails if the source differs from this reviewed minimal patch.
- The builder retains the upstream direct call to Paper's
  `Bukkit.getMinecraftVersion()` and its existing `NoSuchMethodError` fallback.
  Because the pinned Spigot API lacks this Paper method, the builder is compiled
  in a separate Java compilation against the small signature-only
  `src/compileOnly/java/org/bukkit/Bukkit.java`. This is compiler scaffolding,
  not a server implementation. A separate filtered output contains only the
  builder and its `$1` class; the stub is never in the main/test runtime classpath
  or final JAR. The server's actual Bukkit implementation supplies those calls.
- The original pinned, checksum-verified Maven JAR remains the compilation
  dependency. Shadow excludes that artifact from automatic inclusion, then copies
  its content back except the original builder class family and every member of
  `io/github/retrooper/packetevents/bstats/`. The two freshly compiled builder
  classes replace the originals. Existing relocation moves them into GuardianAC's
  private namespace. This avoids ambiguous duplicate-class precedence.
- Unit tests use the same filtered replacement builder output. The lightweight
  `sourcesJar` includes the modified builder source. The complete project source
  archive additionally includes this diff, compiler stub, Gradle build logic,
  all pristine upstream source archives, and the source provenance overlay.

Build with JDK 25 and the pinned Gradle Wrapper from the project root:

    ./gradlew --offline clean test shadowJar sourcesJar
    python3 tools/verify_jar.py build/libs/GuardianAC-Ultimate-1.0.0-dev.1.jar
    python3 tools/test_embedded_telemetry.py
    python3 third-party/sources/verify_sources.py --plugin-jar build/libs/GuardianAC-Ultimate-1.0.0-dev.1.jar

`--offline` requires the verified dependencies already in the local Gradle cache.
No server starts, test-player connections, or telemetry requests are involved in
these checks. A timestamped Maven snapshot can require a local Maven mirror even
when Gradle's normalized snapshot file cache holds its bytes; reuse only the
checksum-verified API JAR/POM without changing the production version or checksums.

To independently reconstruct the modified source, extract the pristine archive
into a temporary directory and apply the diff with `patch -p1` from its
`packetevents-2.14.0` root. Its resulting Java file must equal the checked-in
preferred source. The Python regression test also verifies the complete diff.

## Offline enforcement and regression evidence

The Shadow build fails if final class bytes contain a bStats class reference or
report endpoint, or if the expected builder class family differs. The separate
`tools/verify_embedded_telemetry.py` parses the **actual final shaded classfiles**:

- all class constant pools must lack bStats types and reporting endpoints;
- the two builder classfiles must exist and their real method instructions must
  contain no bStats constructor/chart calls or scheduled-executor metric calls;
- `init()` must retain its functional listener-registration lifecycle;
- the actual `PacketAdapter.load()` instructions must call the private builder,
  must not invoke the ineffective bStats setting, and must pass literal `false`
  to the real `checkForUpdates(boolean)` method before the API's `load()` call.

Mutation tests reject a restored bStats class, an isolated endpoint string, a
metrics reference in otherwise dead code, a scheduler invocation inside the
builder, an ineffective bStats setting, a true update-check argument, an omitted
update-setting call, and a missing functional `init()`. These are static tests:
classfiles are read as bytes and are never JVM-loaded by the audit scripts.

The source provenance overlay in `../../sources/RUNTIME-SOURCE-OVERLAY.json`
maps the two modified class paths and records the 14 omitted bStats classes.
The unchanged upstream class-source index describes input artifacts rather than
claiming that the patched output still uses the pristine builder source.

## Scope and limits

This removes embedded PacketEvents bStats reporting from GuardianAC without
reading/writing `plugins/bStats/config.yml`, affecting other plugins, or changing
server-wide metrics settings. There is no runtime opt-in switch in this variant.
The preserved bStats sources/licenses are original-dependency provenance only;
its implementation is absent from the final plugin JAR.

PacketEvents startup update checks are separately disabled by the real
`checkForUpdates(false)` setting. Other upstream helpers, including the update
checker implementation and Mojang URL helpers, remain bundled; optional
GuardianAC owner-configured features can also use networking. Therefore this
work does not claim that all possible outbound code is absent from the JAR, or
that the hosting server/other plugins have no independent telemetry. No final
server soak was performed as part of this offline correction.

## What the original bStats sender would collect and schedule

The bundled bStats 3.1.0 preferred sources establish the original behavior:

- destination: gzip JSON POST to `https://bStats.org/api/v2/data/bukkit`;
- service ID `11327`, GuardianAC plugin version, `packetevents_version` custom
  chart, persistent random `serverUUID`, and bStats `metricsVersion`;
- current total player count, online-mode flag, Bukkit version/name;
- Java version, OS name/architecture/version, and available processor count;
- first submission at a randomized 3–6 minutes after initialization;
- a second periodic schedule beginning at that initial delay plus 0–30 minutes,
  then repeating every 30 minutes, while the service remains enabled.

These fields do not include player names or player UUIDs in the cited sender.
A short startup check alone would not establish that it stayed silent. This is
why the correction verifies the removed constructor, classes, references, and
endpoint directly, instead of relying on the obsolete setting or a short run.

Source locations: pristine PacketEvents builder and settings files;
`bstats-bukkit-3.1.0-sources.zip` → `org/bstats/bukkit/Metrics.java`;
`bstats-base-3.1.0-sources.zip` → `org/bstats/MetricsBase.java`.
