/* SPDX-License-Identifier: GPL-3.0-only */
package dev.guardianac.security;

import java.security.SecureRandom;
import java.util.*;

/** One-shot, actor-bound, short-lived confirmation for exactly one immutable operation. */
public final class ConfirmationManager<T> {
    public record Pending<T>(String token,String actor,T action,long expiresAt) {}
    private final Map<String,Pending<T>> pending=new HashMap<>(); private final SecureRandom random=new SecureRandom();
    public synchronized Pending<T> request(String actor,T action,long now) {
        Objects.requireNonNull(actor); Objects.requireNonNull(action);
        if(actor.isBlank()||actor.length()>128)throw new IllegalArgumentException("Invalid confirmation actor");
        long expiry=Math.addExact(now,30_000);
        pending.values().removeIf(p->p.expiresAt()<now);
        pending.values().removeIf(p->p.actor().equals(actor));
        if(pending.size()>=128) throw new IllegalStateException("Too many pending confirmations");
        byte[] bytes=new byte[16]; random.nextBytes(bytes); String token=HexFormat.of().formatHex(bytes);
        Pending<T> p=new Pending<>(token,actor,action,expiry); pending.put(token,p); return p;
    }
    public synchronized Optional<T> consume(String token,String actor,long now) {
        if(token==null||token.length()!=32)return Optional.empty();
        Pending<T> p=pending.get(token);
        if(p==null || !p.actor().equals(actor)) return Optional.empty();
        pending.remove(token); return p.expiresAt()>=now&&now>=p.expiresAt()-30_000?Optional.of(p.action()):Optional.empty();
    }
    public synchronized void cancel(String token,String actor) {
        Pending<T> value=pending.get(token);
        if(value!=null&&value.actor().equals(actor))pending.remove(token);
    }
    public synchronized void forget(String actor) { pending.values().removeIf(p->p.actor().equals(actor)); }
    public synchronized void clear() { pending.clear(); }
}
