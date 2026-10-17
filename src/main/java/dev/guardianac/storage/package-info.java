/*
 * SPDX-License-Identifier: GPL-3.0-only
 *
 * Storage integration contract:
 * - Open/close are lifecycle operations; all repository methods return bounded-worker futures.
 * - A successful evidence write followed by a successful ban write is required before sanctions.
 * - createBan validates persistence/identity/integrity, not cheat confidence. The detection engine
 *   must validate independent evidence; automatic IP/DUAL sanctions additionally use IpBanPolicy.
 * - Use UUID restrictions for player identity. matchIp returns explicit existing IP restrictions;
 *   a shared address on a UUID-only ban must never establish guilt or create another sanction.
 * - Callbacks run on the storage worker unless explicitly moved to another executor. Never invoke
 *   Bukkit APIs or close the repository from such callbacks. Restore only while storage is closed.
 * - Sync the native ban source with BanRecord.ownershipMarker(). Only remove native entries whose
 *   current source exactly matches that marker, checking again on the server thread immediately
 *   before removal. External administrator/plugin replacements must be preserved.
 * - Revoke methods return pre-change records for synchronization; refetch records for GUI display.
 *   DUAL UUID/IP components revoke independently. Confirmation dialogs must snapshot case IDs and
 *   use revokeCase/revokeCaseUuid/revokeCaseIp; broader player/address methods would also include
 *   bans issued after the preview. Reset the affected in-memory VL in the adapter.
 * - recentEvidence provides metadata-only navigation for all saved anomalies, even without a ban.
 *   evidenceForCase retrieves the latest payload/metadata for an exact case; evidence(id) selects
 *   a particular historical recording. Verify payloadAvailable before offering replay.
 * - IP encryption is AES-256-GCM; lookup uses an independent HMAC-SHA-256 key. Keep ip-keys.bin and
 *   the database private, together in off-site backups. Database snapshots alone cannot recover IPs.
 * - Evidence JSON contains replay telemetry, not IPs, secrets, or webhook URLs. Hash/identity metadata
 *   stays with ban history after payload expiration. Active-ban payloads are pinned. At the byte cap,
 *   unpinned old payloads can be evicted before TTL; if active payloads fill it, new writes fail safely.
 *   The payload cap is logical, not a total database-file cap: preserved ban/audit metadata and SQLite
 *   page/WAL overhead also consume disk. Secure deletion and incremental vacuum reclaim free pages.
 * - POSIX files use owner-only modes; Windows requires the server operator's private data-directory ACL.
 * - Backups use SQLite VACUUM INTO, fsync and verification. No online filesystem copying of WAL state.
 *   Automatic recovery never overwrites corrupt state. Explicit restore quarantines original files.
 */
package dev.guardianac.storage;
