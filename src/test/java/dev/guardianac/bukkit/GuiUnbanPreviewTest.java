/* SPDX-License-Identifier: GPL-3.0-only */
package dev.guardianac.bukkit;

import dev.guardianac.security.ConfirmationManager;
import org.junit.jupiter.api.Test;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.IntStream;
import static org.junit.jupiter.api.Assertions.*;

class GuiUnbanPreviewTest {
    private GuardianPlugin.UnbanAction action(List<String> cases,String scope) {
        // An address must never be rendered even if a future caller supplies this reserved field.
        return new GuardianPlugin.UnbanAction(cases,scope,"203.0.113.47","Protected case preview");
    }
    @Test void singleCaseIpPreviewExplainsSharedAddressAndRemainingUuidInBothLanguages() {
        var action=action(List.of("GAC-12345678"),"IP");
        String english=String.join("\n",GuiManager.confirmationLore(action,"en"));
        assertTrue(english.contains("Scope to remove: IP"));
        assertTrue(english.contains("Cases in this preview: 1"));
        assertTrue(english.contains("Only this one case; other cases remain."));
        assertTrue(english.contains("Other accounts sharing an address may regain access."));
        assertTrue(english.contains("Active UUID restrictions remain; those UUIDs stay blocked."));
        assertTrue(english.contains("Other cases and other administrators' bans remain."));
        String japanese=String.join("\n",GuiManager.confirmationLore(action,"ja"));
        for(String text:List.of("解除対象の範囲: IP","対象ケース数: 1","この1件だけ","共有IP","UUID制裁があれば残り","30秒"))assertTrue(japanese.contains(text),text);
        for(String rendered:List.of(english,japanese))assertFalse(rendered.contains(action.protectedIp()));
    }
    @Test void uuidAndAllPreviewsDescribeTheirDifferentRemainingScopes() {
        String uuid=String.join("\n",GuiManager.confirmationLore(action(List.of("GAC-12345678"),"UUID"),"en"));
        assertTrue(uuid.contains("Remove UUID restrictions only; IP restrictions remain."));
        String all=String.join("\n",GuiManager.confirmationLore(action(List.of("GAC-12345678"),"ALL"),"en"));
        assertTrue(all.contains("Remove UUID and IP restrictions in the listed cases."));
        assertTrue(all.contains("other cases remain"));
        assertThrows(IllegalArgumentException.class,()->GuiManager.confirmationLore(action(List.of("GAC-12345678"),"UNRECOGNIZED"),"en"));
    }
    @Test void commandMultiCasePreviewIsBoundedPaginatedAndDoesNotIncludeLaterCases() {
        List<String> original=new ArrayList<>(IntStream.range(0,73).mapToObj(i->"GAC-%08X".formatted(i)).toList());
        var preview=action(original,"IP");original.add("GAC-99999999");
        List<String> rendered=new ArrayList<>();
        for(int page=0;page<3;page++) {
            List<String> cases=GuiManager.confirmationCases(preview,page);assertTrue(cases.size()<=36);rendered.addAll(cases);
            assertThrows(UnsupportedOperationException.class,()->cases.add("GAC-FFFFFFFF"));
        }
        assertEquals(preview.cases(),rendered);assertEquals(73,rendered.size());assertFalse(rendered.contains("GAC-99999999"));
        String lore=String.join("\n",GuiManager.confirmationLore(preview,"en"));
        assertTrue(lore.contains("Cases in this preview: 73"));assertTrue(lore.contains("Only listed cases; later-added cases remain."));
        assertFalse(lore.contains("Only this one case"));
        assertThrows(IllegalArgumentException.class,()->GuiManager.confirmationCases(preview,-1));
        assertThrows(IllegalArgumentException.class,()->GuiManager.confirmationCases(preview,3));
        assertEquals(36,GuiManager.confirmationCases(action(original.subList(0,72),"IP"),1).size());
        assertThrows(IllegalArgumentException.class,()->GuiManager.confirmationCases(action(original.subList(0,72),"IP"),2));
    }
    @Test void previewedIpCaseAndScopeRemainActorBoundOneShotAndExpiring() {
        var confirmations=new ConfirmationManager<GuardianPlugin.UnbanAction>();
        var operation=action(List.of("GAC-12345678"),"IP");
        var pending=confirmations.request("owner",operation,100);
        assertTrue(confirmations.consume(pending.token(),"other",101).isEmpty());
        var confirmed=confirmations.consume(pending.token(),"owner",102).orElseThrow();
        assertSame(operation,confirmed);assertEquals(List.of("GAC-12345678"),confirmed.cases());assertEquals("IP",confirmed.scope());
        assertTrue(confirmations.consume(pending.token(),"owner",103).isEmpty());
        var expired=confirmations.request("owner",operation,1000);
        assertTrue(confirmations.consume(expired.token(),"owner",31001).isEmpty());
        var closed=confirmations.request("owner",operation,40000);confirmations.cancel(closed.token(),"owner");
        assertTrue(confirmations.consume(closed.token(),"owner",40001).isEmpty());
    }
}
