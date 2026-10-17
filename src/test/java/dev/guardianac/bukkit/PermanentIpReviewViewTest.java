/* SPDX-License-Identifier: GPL-3.0-only */
package dev.guardianac.bukkit;

import dev.guardianac.core.AutomaticBanGate;
import dev.guardianac.core.Preset;
import dev.guardianac.storage.*;
import org.junit.jupiter.api.Test;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class PermanentIpReviewViewTest {
    private final UUID target=UUID.fromString("c7070d2f-44ae-4a9f-b447-a01cf30c5b45");
    private final Instant time=Instant.parse("2026-10-14T00:00:00Z");
    private String hash(String json) throws Exception {
        return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(json.getBytes(StandardCharsets.UTF_8)));
    }
    private PermanentIpCandidate candidate(String json) throws Exception {
        return new PermanentIpCandidate("GAC-12345678",target,"evidence-1234",hash(json),"protected-lookup","a".repeat(64),time,time.plusSeconds(3600),time.minusSeconds(1));
    }
    private EvidenceRecord evidence(String json) throws Exception {
        return new EvidenceRecord("evidence-1234",target,"GAC-12345678",time,json,hash(json),json.getBytes(StandardCharsets.UTF_8).length);
    }
    private PermanentIpReview review(String json) throws Exception {
        BanRecord source=new BanRecord(UUID.randomUUID(),target,"Fixture",BanType.UUID,"Evidence policy",time,time.plusSeconds(86400),99,
            "GAC-12345678","evidence-1234","GuardianAC",true,"native-marker",null,null,null);
        return new PermanentIpReview(candidate(json),source,"203.0.113.47");
    }
    @Test void evidenceRenderingBindsEveryMetadataFieldAndOriginalByteDigest() throws Exception {
        String json="{\"version\":2,\"replay\":[{\"time\":123,\"kind\":\"MOVE\",\"measurements\":{\"x\":1,\"y\":2},\"flags\":[\"world:fixture\"]}],\"proof\":{\"confidence\":0.999}}";
        var candidate=candidate(json);var good=evidence(json);
        List<String> lines=PermanentIpController.evidenceLines(good,candidate);
        String rendered=String.join("\n",lines);
        for(String text:List.of("version","replay","MOVE","measurements","world:fixture","proof","0.999"))assertTrue(rendered.contains(text));
        assertThrows(UnsupportedOperationException.class,()->lines.add("changed"));
        assertThrows(IllegalArgumentException.class,()->PermanentIpController.evidenceLines(new EvidenceRecord("other",target,good.caseId(),time,json,good.sha256(),good.originalBytes()),candidate));
        assertThrows(IllegalArgumentException.class,()->PermanentIpController.evidenceLines(new EvidenceRecord(good.id(),UUID.randomUUID(),good.caseId(),time,json,good.sha256(),good.originalBytes()),candidate));
        assertThrows(IllegalArgumentException.class,()->PermanentIpController.evidenceLines(new EvidenceRecord(good.id(),target,"GAC-98765432",time,json,good.sha256(),good.originalBytes()),candidate));
        assertThrows(IllegalArgumentException.class,()->PermanentIpController.evidenceLines(new EvidenceRecord(good.id(),target,good.caseId(),time,json+" ",good.sha256(),good.originalBytes()),candidate));
        assertThrows(IllegalArgumentException.class,()->PermanentIpController.evidenceLines(new EvidenceRecord(good.id(),target,good.caseId(),time,null,good.sha256(),good.originalBytes()),candidate));
    }
    @Test void allEvidenceIsPaginatedWithoutDroppingLongValuesOrAllowingFormattingInjection() throws Exception {
        String original="a".repeat(1000)+"§4danger";
        String json="{\"longField\":\""+original+"\"}";
        var lines=PermanentIpController.evidenceLines(evidence(json),candidate(json));
        assertTrue(lines.stream().allMatch(line->line.length()<=96));
        assertTrue(String.join("",lines).contains("a".repeat(1000)+"\\u00a74danger"));
        assertFalse(String.join("",lines).contains("§"));
        String unicode="{\"longField\":\""+"x".repeat(80)+"😀".repeat(120)+"\"}";
        var unicodeLines=PermanentIpController.evidenceLines(evidence(unicode),candidate(unicode));
        assertTrue(unicodeLines.stream().allMatch(line->line.isEmpty()||!Character.isHighSurrogate(line.charAt(line.length()-1))));
        assertTrue(String.join("",unicodeLines).contains("😀".repeat(120)));
        assertEquals(1,PermanentIpController.evidencePages(List.of()));
        assertEquals(1,PermanentIpController.evidencePages(Collections.nCopies(216,"line")));
        assertEquals(2,PermanentIpController.evidencePages(Collections.nCopies(217,"line")));
    }
    @Test void evidenceOver256KibAndThroughTheFullOneMibBoundaryRemainsReviewable() throws Exception {
        String prefix="{\"large\":\"",suffix="\"}";
        for(int bytes:List.of(262145,1024*1024)) {
            String json=prefix+"x".repeat(bytes-prefix.length()-suffix.length())+suffix;
            assertEquals(bytes,json.getBytes(StandardCharsets.UTF_8).length);
            List<String> lines=PermanentIpController.evidenceLines(evidence(json),candidate(json));
            String rendered=String.join("",lines);
            assertTrue(rendered.contains("x".repeat(bytes-prefix.length()-suffix.length())));
            assertTrue(PermanentIpController.evidencePages(lines)>1);
        }
    }
    @Test void oversizedOrUnsupportedEvidenceCannotBeAcknowledged() throws Exception {
        String json="{\"oversize\":\""+"x".repeat(1024*1024)+"\"}";
        assertThrows(IllegalArgumentException.class,()->PermanentIpController.evidenceLines(evidence(json),candidate(json)));
        String array="[]";
        assertThrows(IllegalArgumentException.class,()->PermanentIpController.evidenceLines(evidence(array),candidate(array)));
        String deeplyNested="{\"node\":".repeat(34)+"{}"+"}".repeat(34);
        assertThrows(IllegalArgumentException.class,()->PermanentIpController.evidenceLines(evidence(deeplyNested),candidate(deeplyNested)));
    }
    @Test void acknowledgementsExplainExactEvidencePermanentSharedImpactAndUnchangedUuidWithoutLeakingAddress() throws Exception {
        var value=review("{\"replay\":[]}");
        for(String language:List.of("ja","en")) {
            String proof=String.join("\n",PermanentIpController.evidenceAcknowledgement(value,true,false,language));
            assertTrue(proof.contains(value.candidate().evidenceId()));assertTrue(proof.contains(value.candidate().evidenceSha256()));
            String impact=String.join("\n",PermanentIpController.impactLore(value,true,language));
            assertTrue(impact.contains(value.source().expiresAt().toString()));
            assertFalse(proof.contains(value.ipAddress()));assertFalse(impact.contains(value.ipAddress()));
            assertTrue(impact.contains(language.equals("ja")?"同一人物とは限りません":"does not mean the same person"));
            assertTrue(impact.contains(language.equals("ja")?"無期限":"Indefinitely"));
            assertTrue(impact.contains(language.equals("ja")?"別の永久IP専用ケース":"separate permanent IP-only case"));
        }
        assertTrue(String.join(" ",PermanentIpController.evidenceAcknowledgement(value,false,false,"en")).contains("Open every evidence page first"));
        assertTrue(PermanentIpController.ATTESTATION.contains("exact bound evidence"));
        assertTrue(PermanentIpController.ATTESTATION.contains("shared-address"));
        assertFalse(value.toString().contains(value.ipAddress()));
    }
    @Test void loadedEvidenceCanBeReviewedPastTenMinutesButNeverPastCandidateOrKnownUuidExpiry() throws Exception {
        var value=review("{\"replay\":[]}");
        long elevenMinutes=java.time.Duration.ofMinutes(11).toNanos();
        assertFalse(PermanentIpController.reviewExpired(false,elevenMinutes,value,time.plusSeconds(660)));
        assertTrue(PermanentIpController.reviewExpired(true,elevenMinutes,value,time.plusSeconds(660)));
        assertTrue(PermanentIpController.reviewExpired(true,elevenMinutes,null,time.plusSeconds(660)));
        assertFalse(PermanentIpController.reviewExpired(true,java.time.Duration.ofMinutes(9).toNanos(),null,time.plusSeconds(540)));
        assertTrue(PermanentIpController.reviewExpired(false,java.time.Duration.ofHours(1).toNanos(),value,value.candidate().reviewExpiresAt()));
        assertTrue(PermanentIpController.reviewExpired(false,-1,value,time));
        BanRecord original=value.source();
        BanRecord earlierExpiry=new BanRecord(original.id(),original.playerId(),original.playerName(),original.type(),original.reason(),
            original.createdAt(),time.plusSeconds(600),original.violationLevel(),original.caseId(),original.evidenceId(),original.actor(),
            original.hasProtectedIp(),original.syncToken(),null,null,null);
        var shortSource=new PermanentIpReview(value.candidate(),earlierExpiry,value.ipAddress());
        assertFalse(PermanentIpController.reviewExpired(false,1,shortSource,time.plusSeconds(599)));
        assertTrue(PermanentIpController.reviewExpired(false,1,shortSource,time.plusSeconds(600)));
    }
    @Test void policyKeyIsStableAcrossCheckOrderButBindsAllSanctionSettings() {
        var first=new AutomaticBanGate.Policy(true,true,Preset.STRICT,new LinkedHashSet<>(List.of("a","b")),"IP:24:0:true");
        var otherOrder=new AutomaticBanGate.Policy(true,true,Preset.STRICT,new LinkedHashSet<>(List.of("b","a")),"IP:24:0:true");
        assertEquals(PermanentIpController.policyKey(first),PermanentIpController.policyKey(otherOrder));
        var changed=new AutomaticBanGate.Policy(true,true,Preset.STRICT,first.enabledChecks(),"IP:168:0:true");
        assertNotEquals(PermanentIpController.policyKey(first),PermanentIpController.policyKey(changed));
    }
}
