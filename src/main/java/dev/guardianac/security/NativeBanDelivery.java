// SPDX-License-Identifier: GPL-3.0-only
package dev.guardianac.security;
import java.lang.ref.WeakReference;
import java.util.Objects;
/** Ephemeral exact-session claim: UUID-keyed server metadata must not retain a disconnected Player. */
public record NativeBanDelivery(WeakReference<Object> playerSession,String caseId) {
    public NativeBanDelivery { Objects.requireNonNull(playerSession);Objects.requireNonNull(caseId); }
    public NativeBanDelivery(Object playerSession,String caseId) {this(new WeakReference<>(Objects.requireNonNull(playerSession)),caseId);}
    public boolean belongsTo(Object currentSession){return currentSession!=null&&playerSession.get()==currentSession;}
    @Override public String toString(){return "NativeBanDelivery[caseId="+caseId+"]";}
}
