# Dependency source handoff

This directory supplies source and upstream build material for the eleven
pristine object-code inputs in `../dependency-inventory.json`, including
libraries already shaded inside PacketEvents. The final GuardianAC plugin also
requires the local source overlay described below; it is not an unchanged
repackaging of every upstream class. All archives are unmodified downloads from
official project tag endpoints, Maven Central, or sqlite.org. Published Maven
source JARs use the filename suffix `-sources.zip`; their bytes are unchanged.

## Verify before use

From the GuardianAC source root:

```sh
python3 third-party/sources/verify_sources.py
# Optional: also inspect a built GuardianAC plugin, without loading its code.
python3 third-party/sources/verify_sources.py --plugin-jar /path/to/GuardianAC-Ultimate.jar
```

`SHA256SUMS` covers all 21 archives. `SOURCE-INVENTORY.json` records their original
URLs, sizes, Git commit IDs from official tar PAX metadata, build files, and
embedded-component relationships. `SOURCE-VALIDATION.json` records exact-version
published-source comparisons and per-native-library byte/hash matches.
`CLASS-SOURCE-INDEX.json` maps every class in the eleven pinned object artifacts
to an included upstream source file or explicit source generator.
`RUNTIME-SOURCE-OVERLAY.json` then identifies the two locally rebuilt builder
classes and fourteen bStats classes excluded from the final output. It records
the editable replacement, exact source hashes, complete diff, and build control.
The verifier performs
local inspection only: no network, extraction, Gradle, Maven, JVM, or native
execution. Optional plugin inspection checks that every non-GuardianAC class has an
indexed upstream source or local replacement path after GuardianAC's relocation.
It rejects retained bStats classes, bStats package references or endpoints in
class bytes, and an unexpected builder class family; it also verifies all
retained SQLite native bytes. It does not prove bytecode was
independently rebuilt from source.

The source bundle contains 58,201,488 compressed archive bytes. Each archive was
checked for integrity, unique/safe member names, no links/encryption, fewer than
30,000 entries, and less than 128 MiB expanded content. The full upstream
archives intentionally retain public test fixtures and reference binaries,
including Xerial's historical demo and platform libraries. These are official
upstream content, not deployment databases, IP records, credentials, or live
GuardianAC data. This source audit performs static inspection and does not
execute upstream code; separate compilation/regression evidence for the local
replacement is maintained with the implementation. An archive is not a promise that every included historical demo should
be run.

## Required local source overlay

The pristine PacketEvents 2.14.0 Spigot builder initializes bStats regardless of
`PacketEventsSettings.bStats(false)`, because that setting is a no-op in this
release. Rebuilding or substituting the unmodified builder restores the removed
metrics behavior. Historical generated JAR source-mapping reports are excluded from this public
source package. Use the supplied source verifier on the exact fixed artifact;
an older artifact or report is not privacy-safety approval.

The patched distribution compiles this preferred editable source:

`third-party/patches/packetevents-2.14.0/src/main/java/io/github/retrooper/packetevents/factory/spigot/SpigotPacketEventsBuilder.java`

The sibling `disable-embedded-metrics.patch` records the full minimal change
against the included pristine tag: remove two imports and the metrics
construction/chart-registration block, replacing it with a dated modification
comment. Copyright and GPL-3.0-or-later headers are retained. The supplied source
verifier checks exact source hashes, applies each reviewed text transformation
exactly once in memory, and verifies the complete unified diff. It executes no
upstream code.

`gradle/embedded-packetevents.gradle`, applied by the root `build.gradle`, uses
the dedicated `compileEmbeddedPacketEvents` task. It compiles the replacement
with a signature-only `src/compileOnly/java/org/bukkit/Bukkit.java` stub under
the patch directory. The upstream builder calls Paper's `getMinecraftVersion`,
which is absent from the pinned Spigot API; the stub preserves that invocation
and its existing `NoSuchMethodError` fallback without downloading a new API.
The stub is preferred editable build input, hashed in the overlay. It must
never execute or enter the plugin. `embeddedPacketEventsClasses` copies only
the two builder class outputs into the test/Shadow inputs, keeping the stub
outside main/test/runtime classpaths. The normal sources JAR carries the
replacement source; the complete source handoff also carries the compile-only
stub and build controls.

The build removes the published Spigot
artifact from automatic Shadow inclusion, copies its contents back with the
original builder family and `io/github/retrooper/packetevents/bstats/**` excluded,
and includes the freshly compiled replacement. Normal GuardianAC namespace
relocation follows. The final builder and anonymous `$1` class therefore map to
the local editable source, not the pristine source hash. All 14 upstream bStats
class entries are forbidden in the final output. bStats source archives and MIT
notices are retained only as upstream input/reference material.

Build the plugin from the GuardianAC source root so this overlay is included.
Its `verifyEmbeddedPacketEventsSource` task guards replacement compilation and source-JAR
creation; its final Shadow check guards the builder class set and absent bStats
class references/endpoints. Additional offline builder regression tests are
maintained separately by the implementation. None of these static checks alone
proves full runtime behavior; use the final candidate's approved runtime
verification evidence before deployment. This change does not edit bStats code
or any shared configuration used by other plugins.

## What is included

| Project | Official tag / native source version | Included build entry points |
| --- | --- | --- |
| PacketEvents | `v2.14.0` | Full Gradle tree, `buildSrc/`, `patch/`, mappings, wrapper |
| Gson | `gson-parent-2.14.0` | Root and module Maven POMs, `.mvn/`, Java version template |
| Error Prone annotations | `v2.48.0` | Root Maven POM and `annotations/pom.xml` |
| Adventure | `v4.26.1` | Full Gradle tree, build logic, version catalog, wrapper |
| Examination | `v1.3.0` | Root and `api`/`string` Gradle builds, wrapper |
| Kyori Option | `v1.1.0` | Full Gradle tree, multirelease sources, wrapper |
| bStats (reference only; excluded from patched runtime) | `v3.1.0` | Full Gradle tree, `base`/`bukkit`, wrapper |
| SQLite JDBC | `3.53.4.0` | Maven POM, JNI and extension C, Makefiles, Docker helpers, CI workflows |
| SQLite | `3.53.4` (`3530400`) | Full source, configure scripts, generators, tests, standard amalgamation |

The full tagged trees contain all their modules, but GuardianAC does not embed
all of them. Its runtime artifacts are pinned separately. The Adventure BOM is
metadata only and contributes no object code. Spigot API, Netty, and other
compile/test-only inputs do not become runtime libraries merely because their
names appear in build files.

### Dependencies hidden inside PacketEvents

PacketEvents API already contains relocated Adventure Gson/JSON/legacy
serializers and Kyori Option 1.1.0. PacketEvents Spigot already contains relocated
bStats Bukkit/base 3.1.0 in its pristine input. The local overlay excludes the
bStats code in the final GuardianAC plugin. These distinctions must not be
overlooked when reading the outer
Gradle lockfile. The exact version evidence is PacketEvents `libs.versions.toml`,
its `patch/` and `spigot/` builds, and Adventure's `gradle/libs.versions.toml`.

PacketEvents' `buildSrc/src/main/kotlin/packetevents.shadow-conventions.gradle.kts`
defines its relocations. Its patched serializer source files under `patch/`
supersede the corresponding original Adventure 4.26.1 files; preserve both the
original source archive and the patch build. Original Adventure source is MIT;
PacketEvents-authored patch files retain their own license headers, including
GPL-3.0-or-later headers where present. GuardianAC then performs its own
relocations in the top-level `build.gradle`. The two stages are both necessary
when following a final class back to its source.

## Rebuilding upstream Java components

These are documented entry points, not commands executed during the audit.
Extract each archive into a fresh build directory, read its own README and
build files, and use a compatible JDK. Build scripts can resolve plugins,
toolchains, and dependencies from remote services; review them before running.
The bundle is not an offline SDK or a cache of every build-time dependency.
Do not use publish/deploy tasks merely to build a local replacement.

- Gson: the root POM requires JDK 17 through 21 and Maven at least 3.3.1.
  From its root, `mvn -pl gson -am package` builds the library with its parent
  configuration. `gson/pom.xml` generates `GsonBuildConfig.java` by replacing
  `${project.version}` with `2.14.0`; that is the one verified generated-text
  difference between the tag template and published sources.
- Error Prone: use `mvn -pl annotations -am package` with the JDK/Maven required
  by that tag's POM. The `annotations` module is the runtime annotation library;
  building the full compiler is not necessary merely to identify its source.
- Adventure: its wrapper pins Gradle 9.0.0. The relevant direct module tasks are
  `:adventure-api:build`, `:adventure-key:build`, and `:adventure-nbt:build`.
  The serializer module sources are also required by PacketEvents' patch build.
  Preserve `build-logic/`, the version catalog, and annotation processors.
- Examination: its wrapper pins Gradle 7.2. Use its `:api:build` and
  `:string:build` tasks with a compatible Gradle/JDK combination. The published
  `META-INF/versions/9/module-info.java` entries map to the modules' `java9/`
  source directories; their source text was verified equal.
- Option: its wrapper pins Gradle 8.12.1 and the tag requests a minimum JDK 17
  toolchain. `./gradlew build` includes the Java 9 multirelease module descriptor.
- bStats: its wrapper pins Gradle 6.8. Use a JDK supported by that old Gradle
  release, and `./gradlew :base:build :bukkit:build`. The root `generateMetrics`
  task is for a separate standalone-source distribution, not the dependency
  artifacts used here.
- PacketEvents upstream reference build: its wrapper pins Gradle 9.5.1. Preserve
  the entire tag, including
  `buildSrc/`, `patch/`, mappings, and the selected platform build. Its Shadow
  conventions and publication configuration distinguish standalone shaded
  artifacts from published dependency artifacts. Follow those configurations
  when rebuilding the `api`, `netty-common`, and `spigot` artifacts rather than
  substituting an arbitrary standalone plugin JAR. `PEVersions.java` is created
  by `PEVersionTask.kt`, including a wall-clock build timestamp. The tag source
  and generator are present, but a fresh build is not expected to be byte-for-
  byte identical without controlling that input. No Spigot/BuildTools downloads
  or upstream PacketEvents build were performed in this source audit.

The GuardianAC build uses published, SHA-256-verified dependency inputs and the
explicit local builder overlay. Those input artifact hashes do not assert that
the final builder is unchanged upstream bytecode. A rebuilt
upstream artifact must not silently replace one with a different hash. If you
intentionally change a dependency, update and review the dependency verification,
source inventory, notices, tests, and final JAR audit together.

## SQLite native source identity and build

SQLite JDBC's `VERSION` is `3.53.4`. Every one of its 20 shipped non-Android
native libraries contains this exact SQLite source ID:

```text
2026-07-24 19:02:57 bf7c7f30031888f4e796e429ab3978879485813aaca6f641c7b33e4e09459bcc
```

That ID equals the included `sqlite-src-3530400/manifest.uuid` and the
`SQLITE_SOURCE_ID` in `sqlite-amalgamation-3530400/sqlite3.h`. Each runtime native
blob also matches the corresponding `src/main/resources/org/sqlite/native/`
file in the official SQLite JDBC tag, byte for byte. The detailed report lists
all 20 runtime paths, lengths, and SHA-256 hashes. The upstream source/tag also
retains Android libraries; the pinned default runtime JAR excludes those.

Do not replace Xerial's build with a plain compile of the standard amalgamation.
Its default `ENABLE_UPDATE_DELETE_LIMIT=1` regenerates the amalgamation from the
full SQLite source using `./configure --update-limit` and `make sqlite3.c`.
It then patches extension registration, appends `src/main/ext/*.c`, compiles
with its extensive feature/limit flags, and links `NativeDB.c` plus the generated
JNI header. `Makefile`, `Makefile.common`, `VERSION`, `amalgamation_version.sh`,
`src/main/ext/`, and `src/main/java/org/sqlite/core/NativeDB.c` are all included.

To make the already-included full SQLite source available to that normal build,
copy `sqlite-src-3530400.zip` into a freshly extracted SQLite JDBC tree as
`target/sqlite-3.53.4-src.zip` before invoking the native target. The Makefile
then consumes that local input for its update-limit-enabled generation. Keep the
standard amalgamation separately as reference; selecting it instead changes
that generation path and must not be described as the identical default build.

Upstream `CONTRIBUTING.md` documents `make native` for the current platform and
`make native-all` for its cross-platform set, followed by Maven packaging. It
lists JDK 11, Perl, Maven, make, GCC, curl, unzip, and Docker for cross-building;
SQLite's full-source generator additionally needs its documented Tcl/build
prerequisites. The included `docker/`, `.github/workflows/build-native.yml`, and
Makefiles describe platform compilers, link flags, stripping, and macOS signing.
These are instructions to review and reproduce in a suitable environment, not
evidence those toolchains were run here.

### Explicit rebuild limits

- No complete upstream Java-project or native rebuild is established by this
  source audit. The local PacketEvents builder is deliberately recompiled by
  GuardianAC's dedicated task; its implementation test/build evidence is kept
  separately, along with GuardianAC's other verification.
- Archive/version matching, source-path coverage, native-byte matching, and
  embedded source IDs are provenance evidence, not a mathematical proof of the
  binaries' complete build history.
- Vendor cross-build image references are not all immutable digest pins. Their
  images, compilers, system libraries, OS SDKs, and signing material are not
  included in this handoff. Recreating every native binary byte for byte may
  require additional upstream build-environment details. Never obtain or share
  an upstream signing credential for this purpose.
- Public reference binaries and fixtures are preserved within unmodified source
  distributions. They are not an independent rebuild result.
- This is a technical source/build handoff, not legal certification. Review the
  exact final distribution and applicable obligations before publication.
