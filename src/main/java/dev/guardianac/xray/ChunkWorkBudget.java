/* SPDX-License-Identifier: GPL-3.0-only */
package dev.guardianac.xray;

import dev.guardianac.packet.RateWindow;
import java.util.function.BooleanSupplier;

/** Fixed-memory admission: proven-empty columns cannot consume another recipient's rewrite quota. */
final class ChunkWorkBudget {
    enum Decision { NO_TARGETS, ADMITTED, LIMITED }
    private final RateWindow window=new RateWindow(20,50);
    Decision admit(long monotonicMillis,int limit,BooleanSupplier mayContainTargets) {
        if(!mayContainTargets.getAsBoolean())return Decision.NO_TARGETS;
        synchronized(window) {
            if(window.count(monotonicMillis)>=limit)return Decision.LIMITED;
            window.add(monotonicMillis);
            return Decision.ADMITTED;
        }
    }
}
