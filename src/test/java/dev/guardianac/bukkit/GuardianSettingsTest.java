/* SPDX-License-Identifier: GPL-3.0-only */
package dev.guardianac.bukkit;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import static org.junit.jupiter.api.Assertions.*;

class GuardianSettingsTest {
    @TempDir Path root;
    private GuardianSettings settings(String yaml) throws IOException {
        Files.writeString(root.resolve("config.yml"),yaml);return new GuardianSettings(root);
    }
    @Test void successfulCompareAndSetPersistsAndBacksUpBeforePublication() throws Exception {
        String original="language: ja\nlimits:\n  packets-per-tick: 1024\n";
        GuardianSettings settings=settings(original);long revision=settings.revision();
        List<String> observed=new ArrayList<>();
        settings.beforePublish(()->observed.add(settings.revision()+":"+settings.limit("packets-per-tick",1024,64,4096)));
        assertTrue(settings.setIfRevision("limits.packets-per-tick",2048,revision));
        assertEquals(List.of(revision+":1024"),observed);
        assertEquals(revision+1,settings.revision());assertEquals(2048,settings.limit("packets-per-tick",1024,64,4096));
        assertEquals(original,Files.readString(root.resolve("config.yml.backup")));
        assertEquals(2048,new GuardianSettings(root).limit("packets-per-tick",1024,64,4096));
    }
    @Test void staleRevisionDoesNotWriteRunInvalidationOrChangeRevision() throws Exception {
        GuardianSettings settings=settings("language: ja\n");long stale=settings.revision();
        settings.set("language","en");String before=Files.readString(root.resolve("config.yml"));
        String backup=Files.readString(root.resolve("config.yml.backup"));long current=settings.revision();
        AtomicInteger invalidations=new AtomicInteger();settings.beforePublish(invalidations::incrementAndGet);
        assertFalse(settings.setIfRevision("language","ja",stale));assertEquals(0,invalidations.get());
        assertEquals(current,settings.revision());assertEquals("en",settings.language());
        assertEquals(before,Files.readString(root.resolve("config.yml")));assertEquals(backup,Files.readString(root.resolve("config.yml.backup")));
    }
    @Test void concurrentAdministratorsCannotBothSaveTheSameRevision() throws Exception {
        GuardianSettings settings=settings("language: ja\n");long revision=settings.revision();
        CountDownLatch ready=new CountDownLatch(2),go=new CountDownLatch(1);
        try(var executor=Executors.newFixedThreadPool(2)) {
            var first=executor.submit(()->{ready.countDown();go.await();return settings.setIfRevision("limits.evidence-days",30,revision);});
            var second=executor.submit(()->{ready.countDown();go.await();return settings.setIfRevision("limits.evidence-days",180,revision);});
            try {
                assertTrue(ready.await(5,java.util.concurrent.TimeUnit.SECONDS));go.countDown();
                assertNotEquals(first.get(5,java.util.concurrent.TimeUnit.SECONDS),second.get(5,java.util.concurrent.TimeUnit.SECONDS));
            } finally {go.countDown();}
        }
        assertEquals(revision+1,settings.revision());
        int value=settings.limit("evidence-days",90,1,3650);assertTrue(value==30||value==180);
        assertEquals(value,new GuardianSettings(root).limit("evidence-days",90,1,3650));
    }
    @Test void reloadInvalidatesDraftsEvenWhenFileContentsAreUnchanged() throws Exception {
        GuardianSettings settings=settings("language: ja\n");long old=settings.revision();
        AtomicInteger invalidations=new AtomicInteger();settings.beforePublish(invalidations::incrementAndGet);
        settings.reload();assertEquals(old+1,settings.revision());assertEquals(1,invalidations.get());
        assertFalse(settings.setIfRevision("limits.evidence-days",30,old));
    }
    @Test void invalidReloadKeepsPreviouslyPublishedValuesAndRevision() throws Exception {
        GuardianSettings settings=settings("language: en\n");long revision=settings.revision();
        AtomicInteger invalidations=new AtomicInteger();settings.beforePublish(invalidations::incrementAndGet);
        Files.writeString(root.resolve("config.yml"),"limits: [unterminated");
        assertThrows(IOException.class,settings::reload);assertEquals("en",settings.language());
        assertEquals(revision,settings.revision());assertEquals(0,invalidations.get());
    }
    @Test void failedPrivateWriteKeepsStateAndDoesNotPublishOrInvalidate() throws Exception {
        GuardianSettings settings=settings("language: ja\n");long revision=settings.revision();
        AtomicInteger invalidations=new AtomicInteger();settings.beforePublish(invalidations::incrementAndGet);
        Path outside=root.resolve("unrelated.yml");Files.writeString(outside,"keep");
        Files.delete(root.resolve("config.yml"));Files.createSymbolicLink(root.resolve("config.yml"),outside);
        assertThrows(IOException.class,()->settings.setIfRevision("language","en",revision));
        assertEquals("ja",settings.language());assertEquals(revision,settings.revision());assertEquals(0,invalidations.get());
        assertEquals("keep",Files.readString(outside));
    }
    @Test void normalSetToggleAndReloadAllIncrementAfterTheInvalidationHook() throws Exception {
        GuardianSettings settings=settings("language: ja\nauto-ban: true\n");long first=settings.revision();
        List<Long> before=new ArrayList<>();settings.beforePublish(()->before.add(settings.revision()));
        settings.set("language","en");settings.toggle("auto-ban");settings.reload();
        assertEquals(List.of(first,first+1,first+2),before);assertEquals(first+3,settings.revision());
        assertEquals("en",settings.language());assertFalse(settings.enabled("auto-ban"));
        assertEquals("English",settings.text("日本語","English"));
    }
    @Test void missingDefaultAndIntegerBoundariesAreValid() throws Exception {
        GuardianSettings settings=settings("language: ja\n");
        assertTrue(settings.validLimit("evidence-mib",16,2048));assertEquals(256,settings.limit("evidence-mib",256,16,2048));
        for(int value:List.of(16,256,2048)) {
            settings.set("limits.evidence-mib",value);assertTrue(settings.validLimit("evidence-mib",16,2048));
        }
    }
    @Test void malformedOrOutOfRangeManualValuesAreFlaggedWithoutBeingShown() throws Exception {
        GuardianSettings settings=settings("language: ja\n");
        for(Object value:List.of(-1,2049,16.5,"private-invalid-text",List.of(16),Long.MAX_VALUE)) {
            settings.set("limits.evidence-mib",value);assertFalse(settings.validLimit("evidence-mib",16,2048));
            int bounded=settings.limit("evidence-mib",256,16,2048);assertTrue(bounded>=16&&bounded<=2048);
        }
    }
    @Test void startupConstructorStillRejectsSymlinksAndDoesNotCreateMissingConfig() throws Exception {
        assertThrows(IOException.class,()->new GuardianSettings(root));
        Path elsewhere=root.resolve("elsewhere.yml");Files.writeString(elsewhere,"language: ja\n");
        Files.createSymbolicLink(root.resolve("config.yml"),elsewhere);
        assertThrows(IOException.class,()->new GuardianSettings(root));
    }
}
