/* SPDX-License-Identifier: GPL-3.0-only */
package dev.guardianac.core;

import java.util.*;

/** Immutable hard-evidence decision. Logout/world resets cannot erase evidence awaiting durable storage. */
public record BanEvidence(List<Finding> findings,long capturedAtMillis) {
    private static final long WINDOW=120_000;
    public BanEvidence { findings=List.copyOf(findings); }
    public DetectionEngine.Evaluation evaluate(List<Check> checks,AutomaticBanGate.Policy policy,long now) {
        if(now<capturedAtMillis||!policy.permits())return new DetectionEngine.Evaluation(List.of(),false,0,0);
        Map<String,Check> known=new HashMap<>();for(Check check:checks)known.put(check.id(),check);
        Set<String> families=new HashSet<>(),seen=new HashSet<>();double vl=0;
        double decay=Math.min(3600,(now-capturedAtMillis)/1000.0)*.25;
        for(Finding finding:findings) {
            Check check=known.get(finding.checkId());Sample observation=finding.sample();
            if(check==null||!seen.add(check.id())||!policy.enabledChecks().contains(check.id())
                ||!check.hardInvariant()||!finding.hardInvariant()||check.confidence()<.99||finding.confidence()<.99
                ||!check.family().equals(finding.family())||observation.has("bypass")||observation.has("exempt")
                ||observation.has("protocol-translated")||observation.has("unsupported-protocol")
                ||finding.occurrences()<Math.max(6,(int)Math.ceil(check.requiredSamples()*policy.preset().sampleMultiplier))
                ||finding.lastSeenMillis()-finding.firstSeenMillis()<3000||now<finding.lastSeenMillis()
                ||now-finding.lastSeenMillis()>WINDOW||!Double.isFinite(finding.violationLevel()))continue;
            double contribution=Math.max(0,finding.violationLevel()-decay);
            if(contribution>0){families.add(finding.family());vl+=contribution;}
        }
        return new DetectionEngine.Evaluation(List.of(),families.size()>=2&&vl>=policy.preset().banThreshold,vl,families.size());
    }
}
