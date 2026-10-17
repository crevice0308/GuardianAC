/* SPDX-License-Identifier: GPL-3.0-only */
package dev.guardianac.bukkit;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Pattern;
import java.util.stream.IntStream;
import static dev.guardianac.bukkit.RuntimeMessages.Key.*;
import static org.junit.jupiter.api.Assertions.*;

class RuntimeMessagesTest {
    private static final Pattern JAPANESE=Pattern.compile("[\\p{IsHiragana}\\p{IsKatakana}\\p{IsHan}]");
    private static final String CASE_ID="GAC-12345678";

    @ParameterizedTest @EnumSource(RuntimeMessages.Key.class)
    void everyRuntimeMessageHasCompleteDistinctJapaneseAndEnglishText(RuntimeMessages.Key key) {
        Object[] values=IntStream.range(0,key.arguments()).mapToObj(index->"VALUE_"+index).toArray();
        String ja=RuntimeMessages.text("ja",key,values),en=RuntimeMessages.text("en",key,values);
        assertFalse(ja.isBlank());assertFalse(en.isBlank());assertNotEquals(ja,en,key.name());
        assertTrue(JAPANESE.matcher(ja).find(),key.name());assertFalse(JAPANESE.matcher(en).find(),key.name());
        for(String text:List.of(ja,en)) {
            assertFalse(Pattern.compile("\\{[0-9]+}").matcher(text).find(),key.name());
            for(Object value:values)assertTrue(text.contains(value.toString()),key.name());
        }
        assertEquals(ja,RuntimeMessages.text(null,key,values));
        assertEquals(ja,RuntimeMessages.text("unknown",key,values));
    }
    @Test void catalogsContainExactlyOneEntryForEveryKeyAndCannotBeMutated() throws Exception {
        Set<String> expected=new HashSet<>();Arrays.stream(RuntimeMessages.Key.values()).forEach(key->expected.add(key.name()));
        for(String language:List.of("ja","en")) {
            Set<String> actual=new HashSet<>();
            try(var stream=RuntimeMessages.class.getResourceAsStream("/locales/runtime_"+language+".properties")) {
                assertNotNull(stream);
                try(var lines=new BufferedReader(new InputStreamReader(stream,StandardCharsets.UTF_8))) {
                    for(String line:lines.lines().toList())if(!line.isBlank()&&!line.startsWith("#")) {
                        String key=line.substring(0,line.indexOf('='));assertTrue(actual.add(key),"Duplicate "+key);
                    }
                }
            }
            assertEquals(expected,actual);
            assertThrows(UnsupportedOperationException.class,()->RuntimeMessages.catalog(language).put(INVALID_CASE,"changed"));
        }
    }
    @Test void valuesAreInsertedLiterallyWithoutRecursiveTemplateOrRegexExpansion() {
        String value="literal ${1} \\path {0} 100%";
        assertEquals("Invalid case ID.",RuntimeMessages.text("en",INVALID_CASE));
        assertEquals("Confirm within 30 seconds: /gac confirm "+value+" <reason>",RuntimeMessages.text("en",CONFIRM_PROMPT,value));
        assertThrows(IllegalArgumentException.class,()->RuntimeMessages.text("ja",CONFIRM_PROMPT));
        assertThrows(IllegalArgumentException.class,()->RuntimeMessages.text("en",INVALID_CASE,value));
    }
    @Test void commandTokensAndValidatedOutputLocationsStayStableInBothLanguages() {
        for(String language:List.of("ja","en")) {
            String confirmation=RuntimeMessages.text(language,CONFIRM_PROMPT,"one-time-token");
            assertTrue(confirmation.contains("/gac confirm one-time-token "));assertTrue(confirmation.contains("30"));
            String usage=RuntimeMessages.text(language,COMMAND_USAGE);
            for(String command:List.of("/gac gui","status","unban ","unbanip ","unbanall ","confirm ","replay ","reload"))assertTrue(usage.contains(command));
            assertTrue(RuntimeMessages.text(language,EVIDENCE_EXPORTED,CASE_ID+".json").endsWith("plugins/GuardianAC/exports/"+CASE_ID+".json"));
            assertTrue(RuntimeMessages.text(language,UNBAN_RECORDED,2).endsWith("Cases: 2")||RuntimeMessages.text(language,UNBAN_RECORDED,2).endsWith("対象ケース数: 2"));
        }
    }
    @Test void japaneseStatusAddsExplanationWithoutChangingOrDuplicatingMachineFields() {
        String machine="GuardianAC 1.0.0-dev.1 | runtime 26.3 target accepted | 54 rules | STRICT | TPS 20.0 | engine avg 0.005ms | evidence flags 2 | announced bans 1 | packet drops 0 | context capture failures 0 | queued 0 | replay 256 bytes | denied joins 3";
        assertEquals(machine,RuntimeMessages.status("en",machine,true));
        for(String language:Arrays.asList("ja",null,"unknown")) {
            String status=RuntimeMessages.status(language,machine,true);
            assertEquals(2,status.lines().count());assertEquals(machine,status.substring(status.indexOf('\n')+1));
            assertTrue(status.startsWith("稼働状況:"));assertEquals(1,status.split("\\| denied joins ",-1).length-1);
        }
        String unsupported=machine.replace("26.3 target accepted","UNSUPPORTED: telemetry/auto-ban/Xray OFF");
        assertTrue(RuntimeMessages.status("ja",unsupported,false).contains("観測・自動BAN・X-Ray対策は停止中"));
        assertTrue(RuntimeMessages.status("ja",unsupported,false).endsWith(unsupported));
    }
    @Test void banScreensTranslateHumanTextAndRetainBrandCaseAndExpiryWithoutDetailedSignals() {
        Instant expires=Instant.parse("2030-01-02T03:04:05Z");
        for(String language:List.of("ja","en"))for(boolean permanent:List.of(true,false)) {
            String screen=RuntimeMessages.banScreen(language,permanent,permanent?null:expires,CASE_ID);
            assertTrue(screen.contains("GUARDIANAC ULTIMATE"));assertTrue(screen.contains("ACCESS REVOKED"));assertTrue(screen.contains(CASE_ID));
            assertEquals(!permanent,screen.contains(expires.toString()));assertFalse(screen.contains("null"));
            assertEquals(language.equals("ja"),JAPANESE.matcher(screen).find());
            for(String internal:List.of("confidence","hardInvariant","203.0.113.","UUID","DUAL","VL","webhook"))assertFalse(screen.contains(internal));
        }
        assertTrue(RuntimeMessages.banScreen("ja",true,null,CASE_ID).contains("永久BAN"));
        assertTrue(RuntimeMessages.banScreen("en",false,expires,CASE_ID).contains("TEMPORARY BAN\nUNTIL: "+expires));
    }
    @Test void cautionarySecurityMessagesDoNotClaimPacketOrSharedAddressProof() {
        assertTrue(RuntimeMessages.text("ja",PACKET_DEFENSE,1,2).contains("不正行為の証明になりません"));
        assertTrue(RuntimeMessages.text("en",PACKET_DEFENSE,1,2).contains("not proof of cheating"));
        assertTrue(RuntimeMessages.text("ja",RESTRICTED_IP_ADVISORY,CASE_ID).contains("共有回線の可能性"));
        assertTrue(RuntimeMessages.text("en",RESTRICTED_IP_ADVISORY,CASE_ID).contains("Shared addresses are possible"));
        assertTrue(RuntimeMessages.text("ja",RESTRICTED_IP_ADVISORY,CASE_ID).contains("追加の制裁は行っていません"));
        assertTrue(RuntimeMessages.text("en",RESTRICTED_IP_ADVISORY,CASE_ID).contains("no additional sanction was created"));
    }
    @Test void allCurrentSystemErrorCallSitesHaveExplicitBilingualDiagnostics() throws Exception {
        String plugin=Files.readString(Path.of("src/main/java/dev/guardianac/bukkit/GuardianPlugin.java"));
        var messages=Pattern.compile("systemError\\(\"([^\"]+)\"\\)").matcher(plugin);
        Set<String> diagnostics=new HashSet<>();while(messages.find())diagnostics.add(messages.group(1));
        String reconciler=Files.readString(Path.of("src/main/java/dev/guardianac/security/NativeBanReconciler.java"));
        var reports=Pattern.compile("report\\(\"([^\"]+)\"\\)").matcher(reconciler);
        while(reports.find())diagnostics.add(reports.group(1));
        var retries=Pattern.compile("retry\\(tick,\"([^\"]+)\"\\)").matcher(reconciler);
        while(retries.find())diagnostics.add(retries.group(1)+"; committed database state is unchanged and periodic repair will retry");
        assertEquals(RuntimeMessages.diagnosticKeys().keySet(),diagnostics);
        for(String diagnostic:diagnostics) {
            assertEquals("GuardianAC system error: "+diagnostic,RuntimeMessages.systemError("en",diagnostic));
            String ja=RuntimeMessages.systemError("ja",diagnostic);
            assertTrue(JAPANESE.matcher(ja).find());assertFalse(ja.contains(diagnostic));
            assertFalse(ja.equals(RuntimeMessages.systemError("ja",null)));
        }
    }
    @Test void unknownDiagnosticNeverLeaksAnAddressOrSecretIntoDiscord() {
        String untrusted="Failure for 203.0.113.7 with https://discord.com/api/webhooks/test/TEST_SECRET";
        for(String language:List.of("ja","en")) {
            String error=RuntimeMessages.systemError(language,untrusted);
            assertEquals(RuntimeMessages.systemError(language,null),error);
            assertFalse(error.contains("203.0.113.7"));assertFalse(error.contains("TEST_SECRET"));assertFalse(error.contains("https://"));
        }
    }
    @Test void concurrentCatalogReadsDoNotShareOrMutateTheConfiguredLanguage() {
        IntStream.range(0,1000).parallel().forEach(index->{
            String language=index%2==0?"ja":"en";
            assertEquals(language.equals("ja")?"数値形式のIPアドレスが無効です。":"Invalid numeric IP address.",RuntimeMessages.text(language,INVALID_IP));
        });
    }
}
