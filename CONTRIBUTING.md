# Contributing

GPL-3.0-only original source. Contributions must include the right to distribute
them under that license. Do not copy proprietary anti-cheat checks, client code,
player data, IP addresses, credentials or server secrets into issues/tests.

Use Java 25 and the pinned Gradle Wrapper through the isolated preparation
procedure in [BUILDING.md](BUILDING.md). Keep the historical 155 inputs intact;
review any metadata supplement from its exact official artifact before pinning.
Every check requires a real observed signal, independent ID/family, positive and
legitimate boundary fixtures, missing-data/exemption tests, and documented limits.
Renaming the same predicate is not an additional detection module.

Do not introduce routine auto-kicks, heuristic-only automatic bans, arbitrary
external callbacks, mandatory plugins, license checks or paid feature gates.
Bukkit API calls stay on the server thread. Network callbacks use bounded,
immutable primitive data. Evidence must be durable before a sanction is applied.

Changes to permissions, confirmation, native-ban ownership, shared-IP policy,
packet decoding or persistence require regression tests and security review.
Use RFC5737 documentation addresses and synthetic UUIDs only.

Record exactly which server/build/JAR SHA was tested. A local synthetic fixture
does not validate real-client behavior or production false-positive rates. Keep
unverified gates visible. GitHub/Release publication requires explicit approval;
CI does not publish releases automatically.
