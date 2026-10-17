/* SPDX-License-Identifier: GPL-3.0-only */
package dev.guardianac.core;

import dev.guardianac.checks.CheckCatalog;
import org.junit.jupiter.api.Test;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class BanEvidenceTest {
    private static final UUID PLAYER=new UUID(41,43);
    private static final List<Check> RULES=List.of(rule("a","A"),rule("b","B"));
    private static Check rule(String id,String family){return new Check(id,id,id,family,Sample.Kind.PACKET,true,.995,1,10,"fixture",s->s.has(id));}
    private static AutomaticBanGate.Policy policy(Preset preset,Set<String> checks){return new AutomaticBanGate.Policy(true,true,preset,checks,"UUID:24");}
    private static DetectionEngine qualified() {
        var engine=new DetectionEngine(RULES,1);
        for(int i=0;i<6;i++)engine.accept(Sample.builder(PLAYER,Sample.Kind.PACKET,1000+600L*i,i).flag("a").flag("b").build());
        return engine;
    }
    @Test void sixRepeatedIndependentHardFindingsAreRevalidatedWithoutLivePlayerState() {
        var engine=qualified();var captured=engine.evidence(PLAYER,4000);engine.reset(PLAYER);
        assertTrue(captured.evaluate(RULES,policy(Preset.STRICT,Set.of("a","b")),4100).banRecommended());
        assertFalse(engine.evidence(PLAYER,4100).evaluate(RULES,policy(Preset.STRICT,Set.of("a","b")),4100).banRecommended());
    }
    @Test void currentDisabledChecksAndRelaxedPresetCannotReuseStrictRecommendation() {
        var captured=qualified().evidence(PLAYER,4000);
        assertFalse(captured.evaluate(RULES,policy(Preset.STRICT,Set.of("a")),4000).banRecommended());
        assertFalse(captured.evaluate(RULES,policy(Preset.RELAXED,Set.of("a","b")),4000).banRecommended());
    }
    @Test void expiredFutureExemptAndTranslatedObservationsCannotAuthorize() {
        var captured=qualified().evidence(PLAYER,4000);var policy=policy(Preset.STRICT,Set.of("a","b"));
        assertFalse(captured.evaluate(RULES,policy,3999).banRecommended());assertFalse(captured.evaluate(RULES,policy,124001).banRecommended());
        for(String flag:List.of("bypass","exempt","protocol-translated","unsupported-protocol")) {
            var replaced=captured.findings().stream().map(f->new Finding(f.checkId(),f.family(),f.hardInvariant(),f.confidence(),f.violationLevel(),f.occurrences(),f.firstSeenMillis(),f.lastSeenMillis(),
                new Sample(PLAYER,4000,1,Sample.Kind.PACKET,Map.of(),Set.of(flag)))).toList();
            assertFalse(new BanEvidence(replaced,4000).evaluate(RULES,policy,4000).banRecommended());
        }
    }
    @Test void capturedVlUsesDecayedCellsRatherThanStaleLatestFindingVl() {
        var engine=qualified();engine.accept(Sample.builder(PLAYER,Sample.Kind.PACKET,44000,1).build());
        var captured=engine.evidence(PLAYER,44000);var policy=policy(Preset.STRICT,Set.of("a","b"));
        assertFalse(captured.evaluate(RULES,policy,44000).banRecommended());
        assertTrue(engine.current(PLAYER).stream().mapToDouble(Finding::violationLevel).sum()>100);
    }
    @Test void actualPacketFixturesRequireMoreThanSixHardValuesAndKeepIndependentFamilies() {
        var checks=CheckCatalog.create();var engine=new DetectionEngine(checks,1);
        var ids=new HashSet<String>();checks.forEach(c->ids.add(c.id()));var policy=policy(Preset.STRICT,ids);
        for(int i=0;i<12;i++) {
            long now=1000+i*600L;
            engine.accept(Sample.builder(PLAYER,Sample.Kind.PACKET,now,i).flag("held-slot-present").value("held-slot",15).build());
            engine.accept(Sample.builder(PLAYER,Sample.Kind.PACKET,now+1,i).flag("rotation-present").value("yaw",0).value("pitch",100).build());
            var decision=engine.evidence(PLAYER,now+1).evaluate(checks,policy,now+1);
            if(i==5)assertFalse(decision.banRecommended(),"Six packet observations still must meet the VL threshold");
            if(i==11){assertTrue(decision.banRecommended());assertEquals(2,decision.independentFamilies());}
        }
    }
    @Test void duplicateCheckCannotManufactureViolationLevelOrIndependence() {
        var captured=qualified().evidence(PLAYER,4000);var same=captured.findings().getFirst();
        assertFalse(new BanEvidence(List.of(same,same,same,same),4000).evaluate(RULES,policy(Preset.STRICT,Set.of("a","b")),4000).banRecommended());
    }
}
