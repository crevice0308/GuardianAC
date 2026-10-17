/* SPDX-License-Identifier: GPL-3.0-only */
package dev.guardianac.packet;

import dev.guardianac.core.Sample;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class PacketSessionTest {
    private final UUID id=UUID.randomUUID(),world=UUID.randomUUID();
    private final Object player=new Object(),user=new Object();
    private PacketContext context(UUID world) {
        return new PacketContext(world,1000,20,20,30,Set.of(),false,0,-64,320);
    }
    private PacketSession session() { return new PacketSession(player,user,context(world)); }
    private Sample sample(UUID world,long time) {
        return Sample.builder(id,Sample.Kind.PACKET,time,20).flag("world:"+world).build();
    }
    @Test void connectionUsesBothOpaqueObjectIdentities() {
        PacketSession state=session();
        assertTrue(state.sameConnection(player,user)); assertTrue(state.acceptsUser(user));
        assertFalse(state.sameConnection(new Object(),user));
        assertFalse(state.sameConnection(player,new Object())); assertFalse(state.acceptsUser(new Object()));
    }
    @Test void equalButDistinctTokensCannotImpersonateTheConnection() {
        Object firstPlayer=new String("player"),secondPlayer=new String("player");
        Object firstUser=new String("user"),secondUser=new String("user");
        PacketSession state=new PacketSession(firstPlayer,firstUser,context(world));
        assertFalse(state.sameConnection(secondPlayer,secondUser));
        assertFalse(state.acceptsUser(secondUser)); assertFalse(state.retireUser(secondUser));
        assertTrue(state.acceptsUser(firstUser));
    }
    @Test void freshSameWorldSampleRequiresItsExactOwner() {
        PacketSession old=session(),replacement=new PacketSession(new Object(),new Object(),context(world));
        Sample sample=sample(world,1000);
        assertTrue(old.acceptsSample(old,sample,1001));
        assertFalse(replacement.acceptsSample(old,sample,1001));
        assertTrue(replacement.acceptsSample(replacement,sample,1001));
    }
    @Test void worldRoundTripDoesNotResurrectFirstGenerationQueue() {
        PacketSession first=session(),otherWorld=new PacketSession(player,user,context(UUID.randomUUID()));
        PacketSession returned=session(); Sample sample=sample(world,1000);
        assertFalse(otherWorld.acceptsSample(first,sample,1001));
        assertFalse(returned.acceptsSample(first,sample,1001));
    }
    @Test void retirementRejectsInFlightObservationsAndErasesEligibility() {
        PacketSession state=session(); state.exactProtocol=true;
        state.latest=new PacketMovement(1000,0,0,0,0,0,true,true,true,false);
        assertTrue(state.retireUser(user));
        assertTrue(state.retired); assertFalse(state.exactProtocol); assertNull(state.latest);
        assertFalse(state.acceptsUser(user)); assertFalse(state.acceptsSample(state,sample(world,1000),1000));
        assertTrue(state.retireUser(user)); assertFalse(state.acceptsUser(user));
    }
    @Test void oldDisconnectDoesNotRetireReplacementOrChangeItsEligibility() {
        Object replacementUser=new Object();
        PacketSession replacement=new PacketSession(new Object(),replacementUser,context(world));
        replacement.exactProtocol=true;
        assertFalse(replacement.retireUser(user));
        assertFalse(replacement.retired); assertTrue(replacement.exactProtocol);
        assertTrue(replacement.acceptsUser(replacementUser));
    }
    @Test void replacementDoesNotInheritCountersOrProtocolObservation() {
        PacketSession old=session(); old.exactProtocol=true;
        for(int i=0;i<2001;i++)old.telemetry.record(1000,false,false,false);
        PacketSession replacement=new PacketSession(new Object(),new Object(),context(world));
        assertFalse(replacement.exactProtocol); assertNull(replacement.latest);
        assertFalse(replacement.telemetry.record(1000,false,false,false));
        assertEquals(1,replacement.telemetry.sample(id,context(world),1000,1000,false).build().v("packets-second"));
    }
    @Test void admissionStillChecksWorldAndAgeIncludingBoundaries() {
        PacketSession state=session(); Sample sample=sample(world,1000);
        assertTrue(state.acceptsSample(state,sample,1000)); assertTrue(state.acceptsSample(state,sample,1500));
        assertFalse(state.acceptsSample(state,sample,999)); assertFalse(state.acceptsSample(state,sample,1501));
        assertFalse(state.acceptsSample(state,sample(UUID.randomUUID(),1000),1000));
    }
    @Test void contextRefreshRetainsSameGenerationQueueEligibility() {
        PacketSession state=session(); Sample sample=sample(world,1000);
        state.context=new PacketContext(world,1100,22,20,30,Set.of("teleport-grace"),false,0,-64,320);
        assertTrue(state.acceptsSample(state,sample,1100));
    }
}
