// SPDX-License-Identifier: GPL-3.0-only
package dev.guardianac.security;

import dev.guardianac.storage.BanReconciliationSnapshot;
import java.util.ArrayDeque;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CancellationException;
import java.util.function.Consumer;
import java.util.function.Predicate;

/**
 * Bounded, tick-driven bridge between the serialized database and server-owned native lists.
 * The owner calls request/tick/close on its main thread. Storage completions only publish one
 * immutable result slot; they never schedule Bukkit tasks or mutate server objects. A revision
 * fence detects intervening commits, but is deliberately NOT a cross-system transaction.
 */
public final class NativeBanReconciler implements AutoCloseable {
    public interface Store {
        long revision();
        CompletableFuture<BanReconciliationSnapshot> read(String caseId);
        CompletableFuture<List<String>> page(String after, int limit);
    }
    public static final int CAPACITY=128, PAGE_SIZE=32, MAX_ATTEMPTS=3;
    public static final long SWEEP_PAUSE_TICKS=1200, RETRY_DELAY_TICKS=5;
    private final Store store;
    private final Consumer<BanReconciliationSnapshot> nativeEffect;
    private final Consumer<String> failure;
    private final Predicate<BanReconciliationSnapshot> sweepEffect;
    private final ArrayDeque<Job> urgent=new ArrayDeque<>();
    private final ArrayDeque<String> sweep=new ArrayDeque<>();
    private Job current;
    private volatile ReadResult readResult;
    private volatile PageResult pageResult;
    private volatile boolean closed;
    private boolean reading, paging;
    private long nextAttempt, nextSweep;
    private String cursor;
    private int urgentStreak;

    public NativeBanReconciler(Store store,Consumer<BanReconciliationSnapshot> nativeEffect,Consumer<String> failure) {
        this(store,nativeEffect,snapshot->true,failure);
    }
    /** sweepEffect must be session-idempotent: every retained case is revisited after bounded job failures. */
    public NativeBanReconciler(Store store,Consumer<BanReconciliationSnapshot> nativeEffect,
                               Predicate<BanReconciliationSnapshot> sweepEffect,Consumer<String> failure) {
        this.store=Objects.requireNonNull(store);this.nativeEffect=Objects.requireNonNull(nativeEffect);
        this.sweepEffect=Objects.requireNonNull(sweepEffect);this.failure=Objects.requireNonNull(failure);
    }
    /** The optional final effect executes once, only after a clean current read/native-effect fence. */
    public CompletableFuture<Void> request(String caseId,Predicate<BanReconciliationSnapshot> finalEffect) {
        Objects.requireNonNull(caseId);
        if(closed)return CompletableFuture.failedFuture(new CancellationException("Native reconciliation is disabled"));
        if(urgent.size()+(current==null?0:1)>=CAPACITY) {
            report("Native ban reconciliation queue is full; committed database state is unchanged and periodic repair will retry");
            return CompletableFuture.failedFuture(new IllegalStateException("Native reconciliation queue is full"));
        }
        Job job=new Job(caseId,finalEffect);urgent.addLast(job);return job.result;
    }
    public int pendingWork() { return urgent.size()+sweep.size()+(current==null?0:1)+(paging?1:0); }
    public void requestSweep() { if(!closed)nextSweep=0; }
    /** At most one case read/effect and one <=32-case page are outstanding, irrespective of ban count. */
    public void tick(long tick) {
        if(closed)return;
        PageResult page=pageResult;
        if(page!=null) {
            pageResult=null;paging=false;
            if(page.error!=null) { report("Native ban reconciliation scan failed; periodic repair will retry");nextSweep=tick+SWEEP_PAUSE_TICKS; }
            else if(page.cases.isEmpty()) { cursor=null;nextSweep=tick+SWEEP_PAUSE_TICKS; }
            else { sweep.addAll(page.cases);cursor=page.cases.getLast(); }
        }
        ReadResult read=readResult;
        if(read!=null) {
            readResult=null;reading=false;
            if(read.error!=null)retry(tick,"Native ban reconciliation storage read failed");
            else if(store.revision()!=read.snapshot.revision())retry(tick,"Native ban reconciliation remained busy");
            else {
                try {
                    nativeEffect.accept(read.snapshot);
                    if(store.revision()!=read.snapshot.revision())retry(tick,"Native ban reconciliation changed during synchronization");
                    else {
                        Job completed=current;
                        // A false return means the final fence changed BEFORE any irreversible action.
                        // A thrown callback is never replayed: it may have partially delivered effects.
                        try {
                            Predicate<BanReconciliationSnapshot> followThrough=completed.finalEffect==null?sweepEffect:completed.finalEffect;
                            if(followThrough.test(read.snapshot)) { completed.result.complete(null);current=null; }
                            else retry(tick,"Native ban reconciliation changed before follow-through");
                        } catch(RuntimeException error) {
                            current=null;completed.result.completeExceptionally(new IllegalStateException("Committed ban follow-through failed"));
                            report("Committed ban follow-through failed; review the case and native lists");
                        }
                    }
                } catch(RuntimeException error) { retry(tick,"Native ban operation failed"); }
            }
        }
        if(current==null) {
            if(!sweep.isEmpty()&&(urgent.isEmpty()||urgentStreak>=4)) { current=new Job(sweep.removeFirst(),null);urgentStreak=0; }
            else if(!urgent.isEmpty()) { current=urgent.removeFirst();urgentStreak=Math.min(4,urgentStreak+1); }
            nextAttempt=tick;
        }
        if(current!=null&&!reading&&tick>=nextAttempt) {
            reading=true;current.attempts++;
            try { store.read(current.caseId).whenComplete((snapshot,error)->{if(!closed)readResult=new ReadResult(snapshot,error);}); }
            catch(RuntimeException error) { readResult=new ReadResult(null,error); }
        }
        if(!paging&&sweep.isEmpty()&&tick>=nextSweep) {
            paging=true;
            try { store.page(cursor,PAGE_SIZE).whenComplete((cases,error)->{if(!closed)pageResult=new PageResult(cases,error);}); }
            catch(RuntimeException error) { pageResult=new PageResult(null,error); }
        }
    }
    private void retry(long tick,String reason) {
        if(current.attempts>=MAX_ATTEMPTS) {
            current.result.completeExceptionally(new IllegalStateException(reason));current=null;
            report(reason+"; committed database state is unchanged and periodic repair will retry");
        } else nextAttempt=tick+RETRY_DELAY_TICKS;
    }
    private void report(String text) { failure.accept(text); }
    @Override public void close() {
        closed=true;CancellationException disabled=new CancellationException("Native reconciliation is disabled");
        if(current!=null)current.result.completeExceptionally(disabled);
        for(Job job:urgent)job.result.completeExceptionally(disabled);
        current=null;urgent.clear();sweep.clear();readResult=null;pageResult=null;reading=false;paging=false;
    }
    private static final class Job {
        final String caseId;final Predicate<BanReconciliationSnapshot> finalEffect;final CompletableFuture<Void> result=new CompletableFuture<>();int attempts;
        Job(String caseId,Predicate<BanReconciliationSnapshot> finalEffect) {this.caseId=caseId;this.finalEffect=finalEffect;}
    }
    private record ReadResult(BanReconciliationSnapshot snapshot,Throwable error) {}
    private record PageResult(List<String> cases,Throwable error) {}
}
