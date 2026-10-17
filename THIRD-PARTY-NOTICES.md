# Third-party notices

Original GuardianAC code is GPL-3.0-only. The patched single JAR embeds the
runtime components below, with the local PacketEvents change described here. Retain their original copyright notices and licenses when conveying the
binary or source. Exact directly resolved artifact hashes and POM metadata are
in `third-party/dependency-inventory.json` and `third-party/licenses/`.

| Component | Version | License / official source |
| --- | --- | --- |
| PacketEvents API, Netty-common, locally modified Spigot implementation | 2.14.0 with local metrics removal | GPL-3.0-or-later per upstream source headers; https://github.com/retrooper/packetevents/tree/v2.14.0 |
| Adventure API, key, NBT and serializers embedded by PacketEvents | 4.26.1 | MIT; https://github.com/PaperMC/adventure/tree/v4.26.1 |
| Examination API and string | 1.3.0 | MIT; https://github.com/KyoriPowered/examination/tree/v1.3.0 |
| Kyori Option, embedded by PacketEvents | 1.1.0 | MIT; https://github.com/KyoriPowered/option/tree/v1.1.0 |
| Gson | 2.14.0 | Apache-2.0; https://github.com/google/gson/tree/gson-parent-2.14.0 |
| Error Prone annotations | 2.48.0 | Apache-2.0; https://github.com/google/error-prone/tree/v2.48.0 |
| Xerial SQLite JDBC, including JNI and extension sources | 3.53.4.0 | Apache-2.0 and retained Zentus notice; https://github.com/xerial/sqlite-jdbc/tree/3.53.4.0 |
| SQLite native engine bundled by SQLite JDBC | 3.53.4 | Public-domain dedication; https://sqlite.org/copyright.html |

## Included source and build material

`third-party/sources/` includes the full official tagged source trees for every
project above, exact-version published source artifacts where listed, SQLite's
full source distribution, and its standard amalgamation. It also contains
`SOURCE-INVENTORY.json`, `SOURCE-VALIDATION.json`, `RUNTIME-SOURCE-OVERLAY.json`,
`SHA256SUMS`, and build/source instructions in `README.md`. The inventory records official URLs, hashes, tag
commit IDs, build files, embedded components, and verification limits.

PacketEvents' unchanged full tagged source remains at
`third-party/sources/packetevents-v2.14.0.tar.gz`, SHA-256
`0474b4a507699a9eef60d6676a84a5f086858786b0c155b2aed6addb2edb6e49`.
Its `patch/` and `buildSrc/` directories contain its Adventure serializer changes,
source-generation tasks, and upstream shading instructions. PacketEvents already
embeds relocated Adventure serializer, Option, and bStats classes; those do not
appear as separate artifacts in GuardianAC's Gradle dependency list. Their
pristine sources and notices are included. The patched GuardianAC output removes
the bStats classes as described below.

## Local PacketEvents modification (2026-10-14)

GuardianAC compiles an editable replacement for PacketEvents' Spigot builder
from `third-party/patches/packetevents-2.14.0/src/main/java/`.
`disable-embedded-metrics.patch` in that patch directory records the complete
change against the pristine tag: remove the two bStats imports and the metrics
construction/chart-registration block, replacing it with a dated modification
comment. The upstream copyright and GPL-3.0-or-later header are retained.

`gradle/embedded-packetevents.gradle`, applied by `build.gradle`, compiles that
replacement and excludes the original builder and its nested classes from the
upstream artifact. It also excludes all pre-shaded bStats classes. GuardianAC's
normal namespace relocation then applies to the rebuilt classes. This is a
locally modified PacketEvents distribution; the original artifact hashes and
pristine tag do not describe unchanged final builder bytecode. Both the preferred
editable replacement and its build/exclusion instructions are part of the source
handoff. `RUNTIME-SOURCE-OVERLAY.json` records their exact source correspondence.

The change does not rely on `PacketEventsSettings.bStats(false)`, which is a
no-op in this upstream release. It does not modify bStats code or other plugins'
shared bStats configuration. bStats Bukkit/base 3.1.0 source and MIT notices
remain as reference material for the pristine input, but its object code is
excluded from the patched plugin. Official reference:
https://github.com/Bastian/bStats-Metrics/tree/v3.1.0.

Pristine source archives remain unmodified. GuardianAC's additional relocation,
resource merging, and canonical archive instructions are in `build.gradle`.
The local replacement must remain part of any rebuild of this distribution;
substituting the pristine builder restores the removed metrics initialization.

## SQLite source provenance

All 20 native SQLite libraries in the pinned SQLite JDBC runtime artifact were
statically verified byte-for-byte against the Xerial tag. Their embedded SQLite
source ID agrees with both the included full source manifest and amalgamation.
The Xerial Makefiles include its JNI/extension integration and compilation flags;
its default configuration regenerates the amalgamation with update/delete-limit
support. This is source/provenance verification, not an independent native
rebuild or a claim of bit-identical compiler output. See the source instructions
for toolchain, container, signing, and build-time limitations.

Unmodified upstream archives retain vendor-supplied reference binaries and
public test fixtures. These are not GuardianAC deployment files, and their
presence does not add those fixtures or extra platform binaries to the plugin.

## Retained notices

Original license texts are in `third-party/licenses/` and inside the upstream
source archives. In particular, `option-license.txt` retains KyoriPowered's
2023 MIT notice, and `bstats-LICENSE.txt` retains Bastian Oppermann's 2021 MIT
notice for the supplied upstream reference sources, although bStats object code
is excluded from the patched runtime. `sqlite-jdbc-NOTICE.txt` is an exact copy of Xerial's tagged root `NOTICE`.
That upstream file includes David Crawshaw's attribution and a historical,
truncated NestedVM statement; retaining that statement is not an assertion that
NestedVM classes are present in this plugin. Xerial's `LICENSE.zentus` is also
retained under its component license directory.

## Server-provided and build-only components

Spigot API and Netty are compile-only, server-provided dependencies and are not
bundled. The pinned Spigot API is 26.3-R0.1-20261004.024323-9. The runtime server
supplies Bukkit/Spigot and Netty. No prerequisite PacketEvents plugin is needed.

Build/test-only tools include Gradle 9.8.1 (Apache-2.0), Gradle Shadow 9.3.1
(Apache-2.0), JaCoCo 0.8.14 (EPL-2.0), and JUnit Jupiter 5.13.4 (EPL-2.0).
They are not part of the plugin runtime JAR. The Gradle Wrapper is distributed
with its Apache-2.0 notice in `third-party/licenses/Apache-2.0.txt`.

Before publication, audit the exact final JAR and refresh these records if any
input changes. The inventory is a technical source handoff, not legal
certification. Removing package names or obfuscating code does not remove
license or corresponding-source obligations.
