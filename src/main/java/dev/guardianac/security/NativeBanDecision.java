// SPDX-License-Identifier: GPL-3.0-only
package dev.guardianac.security;

import dev.guardianac.storage.BanRecord;
import java.time.Instant;

/** Pure scope/ownership decisions shared by startup, periodic, unban and post-commit repair. */
public final class NativeBanDecision {
    private NativeBanDecision() {}
    public static boolean active(BanRecord record,boolean uuid,Instant now) {
        return uuid?record.uuidActiveAt(now):record.ipActiveAt(now);
    }
    public static boolean cleanInactive(BanRecord retained,boolean uuid,String nativeSource,Instant now) {
        return !active(retained,uuid,now)&&NativeBanOwnership.mayRemove(retained.ownershipMarker(),nativeSource);
    }
    public static boolean mayNotify(BanRecord current,Instant now) {
        return current.uuidActiveAt(now)||current.ipActiveAt(now);
    }
    /** Never disconnect an unrelated account that merely shares the address. */
    public static boolean mayDisconnect(BanRecord current,java.util.UUID onlineId,boolean sameAddress,Instant now) {
        return current.playerId().equals(onlineId)&&(current.uuidActiveAt(now)||(sameAddress&&current.ipActiveAt(now)));
    }
}
