// SPDX-License-Identifier: GPL-3.0-only
package dev.guardianac.storage;

import static org.junit.jupiter.api.Assertions.*;
import org.junit.jupiter.api.Test;

class IpBanPolicyTest {
    private static final IpBanPolicy.Association AUTHENTICATED_TARGET=new IpBanPolicy.Association(1,1,true);
    private IpBanPolicy.Context context(IpBanPolicy.Association association,boolean nativeIdentity,boolean permanent) {
        return new IpBanPolicy.Context(association,nativeIdentity,50,50,1,true,true,permanent);
    }
    @Test void allObservedAccountsPreventAutomaticIpEvenIfNeitherIsTrusted() {
        for(var association:new IpBanPolicy.Association[]{new IpBanPolicy.Association(2,0,false),new IpBanPolicy.Association(2,1,true),new IpBanPolicy.Association(2,2,true)}) {
            var decision=IpBanPolicy.evaluate(context(association,true,false));
            assertFalse(decision.allowed());assertEquals("shared-ip-protection",decision.reason());
        }
    }
    @Test void offlineOrUnsupportedProxyIdentityCannotQualifyEvenWithSeededTrustedHistory() {
        var decision=IpBanPolicy.evaluate(context(AUTHENTICATED_TARGET,false,false));
        assertFalse(decision.allowed());assertEquals("authenticated-native-identity-required",decision.reason());
    }
    @Test void noObservationsNeverProvesExclusiveAddress() {
        var decision=IpBanPolicy.evaluate(context(IpBanPolicy.Association.unknown(),true,false));
        assertFalse(decision.allowed());assertEquals("known-ip-observation-required",decision.reason());
    }
    @Test void oneUntrustedAccountCannotEstablishTheTargetIdentity() {
        var decision=IpBanPolicy.evaluate(context(new IpBanPolicy.Association(1,0,false),true,false));
        assertFalse(decision.allowed());assertEquals("trusted-target-ip-association-required",decision.reason());
    }
    @Test void oneDifferentTrustedAccountDoesNotEstablishTheTargetAssociation() {
        assertFalse(IpBanPolicy.evaluate(context(new IpBanPolicy.Association(1,1,false),true,false)).allowed());
    }
    @Test void sameIpIsNotEvidenceOfCheatingOrEvasion() {
        assertFalse(IpBanPolicy.evaluate(new IpBanPolicy.Context(AUTHENTICATED_TARGET,true,0,10,1,true,true,false)).allowed());
        assertFalse(IpBanPolicy.evaluate(new IpBanPolicy.Context(AUTHENTICATED_TARGET,true,3,0,1,true,false,false)).allowed());
        assertFalse(IpBanPolicy.evaluate(new IpBanPolicy.Context(AUTHENTICATED_TARGET,true,3,0,1,false,true,false)).allowed());
    }
    @Test void syntheticNativeAuthenticatedTemporaryRequiresIndependentHighConfidenceEvidence() {
        // Pure policy fixture, not a real authenticated Minecraft account or online server run.
        assertTrue(IpBanPolicy.evaluate(new IpBanPolicy.Context(AUTHENTICATED_TARGET,true,2,0,.995,true,true,false)).allowed());
        assertFalse(IpBanPolicy.evaluate(new IpBanPolicy.Context(AUTHENTICATED_TARGET,true,1,9,1,true,true,false)).allowed());
        assertFalse(IpBanPolicy.evaluate(new IpBanPolicy.Context(AUTHENTICATED_TARGET,true,2,9,.9949,true,true,false)).allowed());
    }
    @Test void automaticPermanentIpIsUnavailableRegardlessOfEvidenceOrCounts() {
        var decision=IpBanPolicy.evaluate(context(AUTHENTICATED_TARGET,true,true));
        assertFalse(decision.allowed());assertEquals("automatic-permanent-ip-unavailable",decision.reason());
        assertFalse(IpBanPolicy.evaluate(context(IpBanPolicy.Association.unknown(),true,true)).allowed());
    }
    @Test void malformedPolicyContextRejected() {
        assertThrows(IllegalArgumentException.class,()->new IpBanPolicy.Association(-1,0,false));
        assertThrows(IllegalArgumentException.class,()->new IpBanPolicy.Association(1,2,true));
        assertThrows(IllegalArgumentException.class,()->new IpBanPolicy.Association(1,0,true));
        assertThrows(IllegalArgumentException.class,()->new IpBanPolicy.Context(AUTHENTICATED_TARGET,true,-1,2,1,true,true,false));
        assertThrows(IllegalArgumentException.class,()->new IpBanPolicy.Context(AUTHENTICATED_TARGET,true,2,2,Double.NaN,true,true,false));
    }
}
