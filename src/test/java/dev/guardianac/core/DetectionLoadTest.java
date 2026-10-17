/* SPDX-License-Identifier: GPL-3.0-only */
package dev.guardianac.core;

import dev.guardianac.checks.CheckCatalog;
import dev.guardianac.security.ReplayBuffer;
import java.nio.file.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class DetectionLoadTest {
    @Test void boundedThousandPlayerSyntheticLoad() throws Exception {
        var engine=new DetectionEngine(CheckCatalog.create(),1000);var replay=new ReplayBuffer(32*1024*1024,64*1024);
        UUID[] ids=new UUID[1000];for(int i=0;i<ids.length;i++)ids[i]=new UUID(0,i+1);
        int flags=0,bans=0,operations=0;long begin=System.nanoTime();
        for(int tick=0;tick<100;tick++)for(UUID id:ids) {
            var sample=Sample.builder(id,Sample.Kind.MOVE,100000+tick*50L,tick).flag("ground")
                .value("tps",20).value("ping",40).value("horizontal",.25).value("speed-limit",.36)
                .value("dy",0).value("previous-dy",0).value("air-ticks",0).value("x",tick*.25).value("y",64).value("z",0).build();
            var result=engine.accept(sample);flags+=result.findings().size();if(result.banRecommended())bans++;
            replay.add(sample);operations++;
        }
        double elapsed=(System.nanoTime()-begin)/1_000_000.0;
        assertEquals(100000,operations);assertEquals(0,flags);assertEquals(0,bans);assertTrue(replay.bytes()<=32*1024*1024);
        Path report=Path.of("build/reports/synthetic-load.json");Files.createDirectories(report.getParent());
        Files.writeString(report,"{\"players\":1000,\"samples\":"+operations+",\"elapsed_ms\":"+elapsed+",\"samples_per_second\":"+(operations*1000/elapsed)+
            ",\"flags\":"+flags+",\"bans\":"+bans+",\"replay_bytes\":"+replay.bytes()+",\"scope\":\"pure engine and replay, not a real-player server capacity claim\"}");
    }
}
