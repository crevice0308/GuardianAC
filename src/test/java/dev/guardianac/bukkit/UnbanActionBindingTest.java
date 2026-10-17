// SPDX-License-Identifier: GPL-3.0-only
package dev.guardianac.bukkit;
import dev.guardianac.core.*;
import dev.guardianac.storage.*;
import org.junit.jupiter.api.Test;
import java.time.Instant;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
class UnbanActionBindingTest {
    private static final UUID TARGET=new UUID(0,1),UNRELATED=new UUID(0,2);
    private static BanRecord record(String caseId,UUID target) {
        return new BanRecord(UUID.randomUUID(),target,"Fixture",BanType.DUAL,"Fixture",Instant.EPOCH,null,100,caseId,"evidence","actor",true,"token",null,null,null);
    }
    private static AutomaticBanGate gate() {
        var gate=new AutomaticBanGate(8,()->1L);
        gate.publish(new AutomaticBanGate.Policy(true,true,Preset.NORMAL,Set.of(),"fixture"));return gate;
    }
    @Test void trustedPreviewBindsExactCasesAndDistinctImmutableActors() {
        var records=new ArrayList<>(List.of(record("GAC-00000001",TARGET),record("GAC-00000002",TARGET),record("GAC-00000003",UNRELATED)));
        var action=GuardianPlugin.UnbanAction.preview(records,"IP","Protected IP bans");records.clear();
        assertEquals(List.of("GAC-00000001","GAC-00000002","GAC-00000003"),action.cases());assertEquals(Set.of(TARGET,UNRELATED),action.affectedPlayers());assertEquals("IP",action.scope());assertNull(action.protectedIp());
        assertThrows(UnsupportedOperationException.class,()->action.cases().clear());assertThrows(UnsupportedOperationException.class,()->action.affectedPlayers().clear());
    }
    @Test void confirmationCancelsHeldUnclaimedTicketBeforeAnyAsyncRevocationButNotUnrelatedActor() {
        var gate=gate();var held=gate.issue(TARGET,false).orElseThrow();var unrelated=gate.issue(UNRELATED,false).orElseThrow();
        assertTrue(gate.authorize(held,true));assertTrue(gate.authorize(unrelated,true));Set<UUID> resets=new HashSet<>();
        var action=GuardianPlugin.UnbanAction.preview(List.of(record("GAC-00000001",TARGET)),"UUID","Target");
        action.cancelUnclaimed(gate,resets::add);
        assertFalse(held.tryCommit());assertTrue(unrelated.tryCommit());assertEquals(Set.of(TARGET),resets);
        var fresh=gate.issue(TARGET,false).orElseThrow();assertTrue(gate.authorize(fresh,true));assertTrue(fresh.tryCommit());
        assertEquals(List.of("GAC-00000001"),action.cases(),"A later independent case never enters the confirmed SQL scope");
    }
    @Test void alreadyClaimedDecisionIsNotRetroactivelyRevokedByOldCaseReset() {
        var gate=gate();var claimed=gate.issue(TARGET,false).orElseThrow();assertTrue(gate.authorize(claimed,true));assertTrue(claimed.tryCommit());
        GuardianPlugin.UnbanAction.preview(List.of(record("GAC-00000001",TARGET)),"ALL","Target").cancelUnclaimed(gate,id->{});assertTrue(claimed.claimed());
    }
}
