# Distribution obfuscation decision

Obfuscation was considered and is **not enabled** for this build. Namespace
relocation of embedded dependencies prevents plugin classloader collisions; it
is not claimed to hide source or provide security.

Reasons: the project is GPL-3.0-only with corresponding source, reviewable checks
and evidence policy. Obfuscation adds reflection/packet-library/native-resource
risk, makes incident traces harder to audit, and does not prevent modified
clients or source-based bypass analysis. A transparent reproducible JAR is the
safer initial distribution choice.

The build uses stable file order, fixed 1980-01-01 archive timestamps, pinned dependencies,
dependency locks and a checksum-pinned Gradle Wrapper. A final JDK ZIP pass
canonicalizes shaded dependency member order, compression and timestamps;
Shadow's dependency traversal alone was not deterministic in fresh directories.
This step is fully contained in build.gradle and adds no separate tool dependency.
A repeat-build checksum
test is recorded separately. There is no obfuscated artifact and no claim of
obfuscated-runtime validation.

If an opt-in obfuscated distribution is added later, publish its exact tool,
configuration, mappings where needed for meaningful debugging, corresponding
source and build commands. Repeat the entire server/client/security matrix on
the obfuscated JAR before making it an official release.
