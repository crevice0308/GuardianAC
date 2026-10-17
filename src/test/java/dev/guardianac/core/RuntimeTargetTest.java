/* SPDX-License-Identifier: GPL-3.0-only */
package dev.guardianac.core;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class RuntimeTargetTest {
    @Test void onlyExplicitTargetBukkitReleaseIsSupported() {
        assertTrue(RuntimeTarget.supportsBukkitVersion("26.3"));assertTrue(RuntimeTarget.supportsBukkitVersion("26.3-R0.1-SNAPSHOT"));
        for(String version:new String[]{null,"","26.2-R0.1-SNAPSHOT","26.3.1-R0.1-SNAPSHOT","26.30-R0.1-SNAPSHOT","26.4-R0.1-SNAPSHOT","27.1-R0.1-SNAPSHOT","26.3-pre1","26.3-rc1","26.3-RC1","26.3-Runknown","unknown"})
            assertFalse(RuntimeTarget.supportsBukkitVersion(version),"Unsupported runtime: "+version);
    }
    @Test void actualPaperAndPurpurApiBuildLabelsIdentifyTheExactMinecraftRelease() {
        assertTrue(RuntimeTarget.supportsBukkitVersion("26.3.build.179-stable"));
        assertTrue(RuntimeTarget.supportsBukkitVersion("26.3.build.2648-experimental"));
        assertTrue(RuntimeTarget.supportsBukkitVersion("26.3-R0.1"));
        for(String version:new String[]{"26.2.build.179-stable","26.3.1.build.179-stable","26.30.build.179-stable",
                "26.4.build.179-stable","26.3.build.179-unknown","26.3.build.-1-stable","26.3.build.179-stable-extra",
                "26.3.build.179-stable\n","26.3.build.179","26.3.build.179-rc1"})
            assertFalse(RuntimeTarget.supportsBukkitVersion(version),"Unsupported runtime: "+version);
    }
    @Test void unknownFutureAndTranslatedProtocolsAreNotTheTarget() {
        assertTrue(RuntimeTarget.supportsProtocol(777,777));
        for(int protocol:new int[]{-1,0,774,775,776,778,Integer.MAX_VALUE}) {
            assertFalse(RuntimeTarget.supportsProtocol(protocol,777));assertFalse(RuntimeTarget.supportsProtocol(777,protocol));
            assertFalse(RuntimeTarget.supportsProtocol(protocol,protocol));
        }
    }
}
