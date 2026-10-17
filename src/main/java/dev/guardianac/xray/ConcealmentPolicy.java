/* SPDX-License-Identifier: GPL-3.0-only */
package dev.guardianac.xray;

import java.util.Objects;

/** Serializes configuration changes and packet commits without deleting pending restoration records. */
final class ConcealmentPolicy {
    record Snapshot(XraySettings settings) { }
    private volatile Snapshot current=new Snapshot(XraySettings.defaults());
    private volatile boolean stopped;
    Snapshot snapshot(){return current;}
    boolean stopped(){return stopped;}
    synchronized void configure(XraySettings next){current=new Snapshot(Objects.requireNonNull(next));}
    synchronized void stop(){stopped=true;}
    synchronized boolean commit(Snapshot expected,Runnable action){
        if(stopped||current!=expected)return false;
        action.run();return true;
    }
}
