// SPDX-License-Identifier: GPL-3.0-only
package dev.guardianac.bukkit;

import static org.junit.jupiter.api.Assertions.*;
import java.lang.reflect.Proxy;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import org.bukkit.entity.Player;
import org.junit.jupiter.api.Test;

class LoginAssociationTest {
    private static final UUID ID=UUID.fromString("6a7fb0f5-9e96-47be-bc00-ff5956600001");
    private static final String IP="203.0.113.47";
    private Player player() {
        return (Player)Proxy.newProxyInstance(Player.class.getClassLoader(),new Class<?>[]{Player.class},(proxy,method,args)->{
            if(method.getName().equals("getUniqueId"))return ID;
            if(method.getName().equals("equals"))return proxy==args[0];
            if(method.getName().equals("hashCode"))return System.identityHashCode(proxy);
            if(method.getName().equals("toString"))return "synthetic-player";
            throw new AssertionError("Unexpected Bukkit call: "+method.getName());
        });
    }
    @Test void pendingJoinPersistenceDoesNotConferIpAuthority() {
        Player player=player();var persisted=new CompletableFuture<Void>();
        var association=new GuardianPlugin.LoginAssociation(player,IP,true,persisted);
        assertFalse(association.eligible(player,IP));persisted.complete(null);assertTrue(association.eligible(player,IP));
    }
    @Test void failedOrCancelledJoinCannotUseStaleDatabaseTrust() {
        Player player=player();
        var failed=new GuardianPlugin.LoginAssociation(player,IP,true,CompletableFuture.failedFuture(new IllegalStateException("storage failure")));
        var cancelled=new CompletableFuture<Void>();cancelled.cancel(false);
        assertFalse(failed.eligible(player,IP));assertFalse(new GuardianPlugin.LoginAssociation(player,IP,true,cancelled).eligible(player,IP));
    }
    @Test void sameUuidReplacementCannotReuseOldSessionAuthentication() {
        Player old=player(),replacement=player();assertEquals(old.getUniqueId(),replacement.getUniqueId());
        var association=new GuardianPlugin.LoginAssociation(old,IP,true,CompletableFuture.completedFuture(null));
        assertFalse(association.eligible(replacement,IP));assertFalse(association.eligible(null,IP));
    }
    @Test void lateOldJoinCompletionCannotPromoteReplacement() {
        Player old=player(),replacement=player();var persisted=new CompletableFuture<Void>();
        var association=new GuardianPlugin.LoginAssociation(old,IP,true,persisted);
        persisted.complete(null);assertFalse(association.eligible(replacement,IP));
    }
    @Test void exactAddressAndAuthenticatedNativeIdentityRemainRequired() {
        Player player=player();var persisted=CompletableFuture.<Void>completedFuture(null);
        assertFalse(new GuardianPlugin.LoginAssociation(player,IP,false,persisted).eligible(player,IP));
        assertFalse(new GuardianPlugin.LoginAssociation(player,IP,true,persisted).eligible(player,"203.0.113.48"));
    }
}
