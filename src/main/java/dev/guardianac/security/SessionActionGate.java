/* SPDX-License-Identifier: GPL-3.0-only */
package dev.guardianac.security;

/** One-shot tickets for delayed GUI actions; newer navigation and closing invalidate old work. */
public final class SessionActionGate {
    private long revision;
    private boolean closed;
    public synchronized long issue() { return closed ? -1 : ++revision; }
    public synchronized boolean consume(long ticket) { return consume(ticket,true); }
    public synchronized boolean consume(long ticket,boolean stillAuthorized) {
        if (closed || ticket < 1 || ticket != revision) return false;
        revision++;
        return stillAuthorized;
    }
    public synchronized void close() { closed = true; revision++; }
}
