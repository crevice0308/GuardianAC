/* SPDX-License-Identifier: GPL-3.0-only */
package dev.guardianac.bukkit;

import dev.guardianac.security.SessionActionGate;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;
import org.bukkit.configuration.file.YamlConfiguration;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import static org.junit.jupiter.api.Assertions.*;

class AdvancedSettingsTest {
    @TempDir Path root;
    private GuardianSettings settings() throws Exception {
        Files.writeString(root.resolve("config.yml"),Files.readString(Path.of("src/main/resources/config.yml")));
        return new GuardianSettings(root);
    }
    @Test void catalogCoversEveryConfiguredResourceLimitAndNoSecretDestination() throws Exception {
        YamlConfiguration config=new YamlConfiguration();config.loadFromString(Files.readString(Path.of("src/main/resources/config.yml")));
        Set<String> configured=config.getConfigurationSection("limits").getKeys(false);
        assertEquals(configured,AdvancedSetting.all().stream().map(AdvancedSetting::id).collect(Collectors.toSet()));
        assertEquals(6,AdvancedSetting.all().size());assertEquals(6,new HashSet<>(AdvancedSetting.all()).size());
        for(AdvancedSetting setting:AdvancedSetting.all()) {
            assertEquals(config.getInt(setting.key()),setting.fallback());
            assertTrue(setting.key().matches("limits\\.[a-z-]+"));
            assertFalse(setting.key().contains("webhook"));assertFalse(setting.key().contains("ip"));
        }
        assertThrows(UnsupportedOperationException.class,()->AdvancedSetting.all().clear());
    }
    @Test void catalogMatchesTheExistingRuntimeClampRanges() throws Exception {
        String runtime=Files.readString(Path.of("src/main/java/dev/guardianac/bukkit/GuardianPlugin.java"));
        for(AdvancedSetting setting:AdvancedSetting.all()) {
            String call="settings.limit(\""+setting.id()+"\","+setting.fallback()+","+setting.min()+","+setting.max()+")";
            assertTrue(runtime.contains(call),call);
        }
        assertEquals(List.of(AdvancedSetting.PACKETS_PER_TICK),AdvancedSetting.all().stream().filter(s->!s.restartRequired()).toList());
        assertTrue(AdvancedSetting.REPLAY_TOTAL_MIB.min()*1024>=AdvancedSetting.REPLAY_PER_PLAYER_KIB.max());
    }
    @Test void everyLimitHasLocalizedDescriptionCurrentValuesBoundsAndEffectTiming() {
        for(AdvancedSetting setting:AdvancedSetting.all()) {
            assertNotEquals(setting.title("en"),setting.title("ja"));
            String en=String.join("\n",setting.lore("en",setting.fallback(),setting.fallback(),true));
            String ja=String.join("\n",setting.lore("ja",setting.fallback(),setting.fallback(),true));
            for(String label:List.of("Saved setting (effective value): ","Active this session: ","Allowed range: ","Default: "))assertTrue(en.contains(label),en);
            for(String label:List.of("保存設定（有効値）: ","稼働中の値: ","設定範囲: ","既定値: "))assertTrue(ja.contains(label),ja);
            assertTrue(en.contains(setting.value(setting.min(),"en")));assertTrue(en.contains(setting.value(setting.max(),"en")));
            assertTrue(en.contains(setting.restartRequired()?"server restart":"next tick"));
            assertTrue(ja.contains(setting.restartRequired()?"再起動":"次のtick"));
            assertFalse(en.contains("https://"));assertFalse(en.contains("Manual config is invalid"));
        }
    }
    @Test void pendingRestartAndInvalidManualConfigAreExplicitInBothLanguages() {
        for(String language:List.of("ja","en")) {
            String lore=String.join("\n",AdvancedSetting.EVIDENCE_MIB.lore(language,128,256,false));
            assertTrue(lore.contains(language.equals("ja")?"再起動待ち":"waiting for restart"));
            assertTrue(lore.contains(language.equals("ja")?"手動設定が不正":"Manual config is invalid"));
            String live=String.join("\n",AdvancedSetting.PACKETS_PER_TICK.lore(language,512,512,true));
            assertFalse(live.contains(language.equals("ja")?"再起動待ち":"waiting for restart"));
        }
    }
    @Test void numericDraftsClampExtremesWithoutOverflowOrChangingOriginalRevision() {
        for(AdvancedSetting setting:AdvancedSetting.all()) {
            var original=new AdvancedSetting.Draft(setting,setting.fallback(),setting.fallback(),17);
            assertEquals(setting.max(),original.adjust(Integer.MAX_VALUE).proposed());
            assertEquals(setting.min(),original.adjust(Integer.MIN_VALUE).proposed());
            assertEquals(setting.fallback(),original.proposed());assertEquals(17,original.adjust(setting.step()).revision());
            assertEquals(setting.fallback(),original.adjust(setting.step()).reset().proposed());
            assertEquals(setting.fallback(),original.adjust(setting.step()).saved());
            assertTrue(original.current(17));assertFalse(original.current(18));
            assertThrows(IllegalArgumentException.class,()->new AdvancedSetting.Draft(setting,setting.min()-1,setting.fallback(),17));
            assertThrows(IllegalArgumentException.class,()->new AdvancedSetting.Draft(setting,setting.fallback(),setting.max()+1,17));
            assertThrows(IllegalArgumentException.class,()->new AdvancedSetting.Draft(setting,setting.fallback(),setting.fallback(),-1));
        }
    }
    @Test void unitControlsCanReachEveryAllowedIntegerAndStopAtBothBounds() {
        for(AdvancedSetting setting:AdvancedSetting.all()) {
            var draft=new AdvancedSetting.Draft(setting,setting.min(),setting.min(),1);
            assertEquals(setting.min(),draft.adjust(-1).proposed());
            for(int expected=setting.min()+1;expected<=setting.max();expected++) {
                draft=draft.adjust(1);assertEquals(expected,draft.proposed());
            }
            assertEquals(setting.max(),draft.adjust(1).proposed());
        }
    }
    @Test void stagedAdjustmentsAndCancelDoNotPersistOrInvalidateThePolicy() throws Exception {
        GuardianSettings settings=settings();AdvancedSetting setting=AdvancedSetting.EVIDENCE_MIB;long revision=settings.revision();
        String before=Files.readString(root.resolve("config.yml"));
        var draft=new AdvancedSetting.Draft(setting,setting.read(settings),setting.read(settings),revision);
        var changed=draft.adjust(-setting.step());assertNotEquals(draft.proposed(),changed.proposed());
        assertEquals(before,Files.readString(root.resolve("config.yml")));assertEquals(revision,settings.revision());
        assertEquals(setting.fallback(),setting.read(settings));
    }
    @Test void draftSaveIsDurableAndCannotBeReplayedOrOverwriteAnotherChange() throws Exception {
        GuardianSettings settings=settings();AdvancedSetting option=AdvancedSetting.EVIDENCE_MIB;
        var original=new AdvancedSetting.Draft(option,option.read(settings),option.read(settings),settings.revision());
        var changed=original.adjust(-option.step());assertTrue(changed.save(settings));assertFalse(changed.save(settings));
        assertEquals(changed.proposed(),option.read(new GuardianSettings(root)));
        assertFalse(original.adjust(option.largeStep()).save(settings));assertEquals(changed.proposed(),option.read(settings));
    }
    @Test void anyOtherSettingChangeOrReloadRejectsAStagedDraft() throws Exception {
        GuardianSettings settings=settings();AdvancedSetting option=AdvancedSetting.EVIDENCE_DAYS;
        var draft=new AdvancedSetting.Draft(option,option.read(settings),30,settings.revision());
        settings.set("language","en");assertFalse(draft.save(settings));assertEquals(90,option.read(settings));
        var replacement=new AdvancedSetting.Draft(option,option.read(settings),30,settings.revision());
        settings.reload();assertFalse(replacement.save(settings));assertEquals(90,option.read(settings));
    }
    @Test void savingTheBoundedValueNormalizesInvalidManualConfiguration() throws Exception {
        GuardianSettings settings=settings();AdvancedSetting option=AdvancedSetting.EVIDENCE_MIB;
        settings.set(option.key(),"secret-should-not-be-displayed");assertFalse(option.valid(settings));
        int current=option.read(settings);var draft=new AdvancedSetting.Draft(option,current,current,settings.revision());
        String displayed=String.join("\n",option.lore("en",current,current,option.valid(settings)));
        assertFalse(displayed.contains("secret-should-not-be-displayed"));assertTrue(displayed.contains("Manual config is invalid"));
        assertTrue(draft.save(settings));assertTrue(option.valid(settings));assertEquals(option.fallback(),option.read(settings));
    }
    @Test void listPaginationHasNoDuplicatesAndRejectsInvalidPagesOrSizes() {
        for(int size:List.of(1,2,4,6,36)) {
            List<AdvancedSetting> collected=new ArrayList<>();int pages=(AdvancedSetting.all().size()+size-1)/size;
            for(int page=0;page<pages;page++) {
                var values=AdvancedSetting.page(page,size);assertTrue(values.size()<=size);collected.addAll(values);
                assertThrows(UnsupportedOperationException.class,()->values.clear());
            }
            assertEquals(AdvancedSetting.all(),collected);
            assertThrows(IllegalArgumentException.class,()->AdvancedSetting.page(pages,size));
        }
        for(int size:List.of(-1,0,37))assertThrows(IllegalArgumentException.class,()->AdvancedSetting.page(0,size));
        assertThrows(IllegalArgumentException.class,()->AdvancedSetting.page(-1,36));
        assertThrows(IllegalArgumentException.class,()->AdvancedSetting.page(Integer.MAX_VALUE,36));
    }
    @Test void queuedSaveCannotRunAfterNewNavigationCloseOrPermissionLoss() throws Exception {
        GuardianSettings settings=settings();AdvancedSetting option=AdvancedSetting.EVIDENCE_MIB;
        var draft=new AdvancedSetting.Draft(option,option.read(settings),128,settings.revision());
        SessionActionGate gate=new SessionActionGate();long older=gate.issue(),newer=gate.issue();
        assertFalse(gate.consume(older));assertFalse(gate.consume(newer,false));assertFalse(gate.consume(newer,true));
        long closing=gate.issue();gate.close();assertFalse(gate.consume(closing));
        assertEquals(256,option.read(settings));assertTrue(draft.current(settings.revision()));
    }
    @Test void performanceLoreSeparatesChatExplanationAndLocalizesStableFields() {
        String machine="GuardianAC 1.0.0 | runtime 26.3 target accepted | 61 rules | STRICT | TPS 19.9 | engine avg 0.003ms | evidence flags 12 | announced bans 2 | packet drops 3 | context capture failures 4 | queued 5 | replay 8192 bytes | denied joins 7";
        List<String> english=GuiManager.performanceLore(machine,"en");assertEquals(13,english.size());
        assertEquals("engine avg 0.003ms",english.get(5));
        List<String> japanese=GuiManager.performanceLore("長いチャット用説明\n"+machine,"ja");
        assertEquals(13,japanese.size());assertEquals("対応サーバー: Minecraft 26.3",japanese.get(1));
        assertEquals("検知ルール数: 61",japanese.get(2));assertEquals("感度: STRICT",japanese.get(3));
        assertEquals("検知処理の平均時間: 0.003ms",japanese.get(5));assertEquals("状況取得失敗数: 4",japanese.get(9));
        assertFalse(japanese.stream().anyMatch(line->line.contains("\n")||line.contains("長いチャット")));
        assertTrue(GuiManager.performanceLore(machine.replace("runtime 26.3 target accepted","runtime UNSUPPORTED: telemetry/auto-ban/Xray OFF"),"ja").get(1).contains("停止中"));
        assertThrows(UnsupportedOperationException.class,()->japanese.clear());
    }
    @Test void guiWiringRetainsActorSessionPermissionMetadataAndPersistenceBoundaries() throws Exception {
        String source=Files.readString(Path.of("src/main/java/dev/guardianac/bukkit/GuiManager.java"));
        assertTrue(source.contains("player==ownerSession&&owner.equals(player.getUniqueId())"));
        assertTrue(source.contains("menu.gate.consume(ticket,player.isOnline()&&menu.owns(player)&&player.getOpenInventory().getTopInventory()==menu.inventory&&player.hasPermission(\"guardianac.view\"))"));
        assertTrue(source.contains("menu.actions.get(event.getRawSlot())"));assertFalse(source.contains("getCurrentItem()"));
        assertFalse(source.contains("getPersistentDataContainer"));
        String save=source.substring(source.indexOf("    private void saveAdvanced("),source.indexOf("    private void duration("));
        assertTrue(save.contains("admin(player,()->{"));assertTrue(save.contains("draft.current(plugin.settings().revision())"));
        assertTrue(save.contains("if(!draft.save(plugin.settings()))"));assertTrue(save.indexOf("draft.save(plugin.settings())")<save.indexOf("plugin.applySettings()"));
        String editor=source.substring(source.indexOf("    private void advancedEditor("),source.indexOf("    private void staleAdvanced("));
        assertTrue(editor.contains("if(player.hasPermission(\"guardianac.admin\"))"));
        assertTrue(editor.contains("p->admin(p,()->advancedEditor(p,adjusted,page))"));
        assertTrue(editor.contains("p->admin(p,()->advancedEditor(p,draft.reset(),page))"));
        assertTrue(editor.contains("p->admin(p,()->advancedEditor(p,draft.adjust(Integer.MIN_VALUE),page))"));
        assertTrue(editor.contains("p->admin(p,()->advancedEditor(p,draft.adjust(Integer.MAX_VALUE),page))"));
        assertTrue(source.contains("setting.restartRequired()?startupLimits.get(setting):setting.read(plugin.settings())"));
        assertTrue(source.contains("event.setCancelled(true)"));assertTrue(source.contains("menu.gate.close()"));
    }
}
