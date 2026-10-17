// SPDX-License-Identifier: GPL-3.0-only
package dev.guardianac.security;
import dev.guardianac.storage.*;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
class NativeBanDecisionTest {
    private static final Instant NOW=Instant.parse("2026-10-14T00:00:00Z");
    private static BanRecord record(BanType type,Instant uuidRevoked,Instant ipRevoked,Instant expiry) {
        return new BanRecord(UUID.randomUUID(),new UUID(0,1),"Target",type,"Fixture",NOW.minusSeconds(60),expiry,100,"GAC-0000000000000001","evidence","actor",true,"exact-token",null,uuidRevoked,ipRevoked);
    }
    @Test void restartOrphanCleanupRequiresExactFullMarkerAndInactiveScope() {
        BanRecord r=record(BanType.DUAL,NOW,NOW,null);
        assertTrue(NativeBanDecision.cleanInactive(r,true,r.ownershipMarker(),NOW));assertTrue(NativeBanDecision.cleanInactive(r,false,r.ownershipMarker(),NOW));
        for(String source:new String[]{null,"GuardianAC","GuardianAC:","GuardianAC:GAC-0000000000000001:other-token","AnotherPlugin", "GuardianAC:GAC-0000000000000002:exact-token"}) {
            assertFalse(NativeBanDecision.cleanInactive(r,true,source,NOW));assertFalse(NativeBanDecision.cleanInactive(r,false,source,NOW));
        }
    }
    @Test void partialUnbanPreservesOtherScopeAndActiveNativeOwner() {
        BanRecord r=record(BanType.DUAL,null,NOW,null);
        assertFalse(NativeBanDecision.cleanInactive(r,true,r.ownershipMarker(),NOW));assertTrue(NativeBanDecision.cleanInactive(r,false,r.ownershipMarker(),NOW));assertTrue(NativeBanDecision.mayNotify(r,NOW));
    }
    @Test void expiredScopeIsInactiveWithoutDatabaseMutation() {
        BanRecord r=record(BanType.DUAL,null,null,NOW);
        assertTrue(NativeBanDecision.cleanInactive(r,true,r.ownershipMarker(),NOW));assertFalse(NativeBanDecision.mayNotify(r,NOW));
    }
    @Test void ipEnforcementIsOnlyCurrentMatchingSourceTargetNeverSharedBystander() {
        BanRecord r=record(BanType.IP,null,null,null);
        assertTrue(NativeBanDecision.mayDisconnect(r,r.playerId(),true,NOW));assertFalse(NativeBanDecision.mayDisconnect(r,r.playerId(),false,NOW));assertFalse(NativeBanDecision.mayDisconnect(r,new UUID(0,2),true,NOW));
        BanRecord revoked=record(BanType.IP,null,NOW,null);assertFalse(NativeBanDecision.mayDisconnect(revoked,revoked.playerId(),true,NOW));assertFalse(NativeBanDecision.mayNotify(revoked,NOW));
    }
    @Test void ownedInactiveEntryCanBeRemovedThenSurvivorAddedWithoutReplacingForeignEntry() {
        BanRecord old=record(BanType.DUAL,NOW,NOW,null);String survivor="GuardianAC:GAC-0000000000000002:surviving-token";
        assertTrue(NativeBanDecision.cleanInactive(old,false,old.ownershipMarker(),NOW));
        assertTrue(NativeBanOwnership.mayReplace(survivor,false,null));
        assertFalse(NativeBanOwnership.mayReplace(survivor,true,"Foreign"));
        assertFalse(NativeBanOwnership.mayReplace(survivor,true,old.ownershipMarker()));
    }
}
