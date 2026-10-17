/* SPDX-License-Identifier: GPL-3.0-only */
package dev.guardianac.security;

import dev.guardianac.core.Sample;
import org.junit.jupiter.api.Test;
import java.net.URI;
import java.util.UUID;
import static org.junit.jupiter.api.Assertions.*;

class SecurityTests {
    @Test void confirmationCannotBeStolenOrReplayed() {
        var c=new ConfirmationManager<String>();var p=c.request("alice","case-123",100);
        assertTrue(c.consume(p.token(),"mallory",200).isEmpty());
        assertEquals("case-123",c.consume(p.token(),"alice",200).orElseThrow());
        assertTrue(c.consume(p.token(),"alice",201).isEmpty());
    }
    @Test void expiredAndSupersededConfirmationsFail() {
        var c=new ConfirmationManager<String>();var one=c.request("alice","one",0);var two=c.request("alice","two",1);
        assertTrue(c.consume(one.token(),"alice",2).isEmpty());assertTrue(c.consume(two.token(),"alice",30_002).isEmpty());
    }
    @Test void confirmationCapacityBounded() {
        var c=new ConfirmationManager<Integer>();for(int i=0;i<128;i++)c.request("a"+i,i,1);
        assertThrows(IllegalStateException.class,()->c.request("overflow",999,1));
        assertNotNull(c.request("recovered",999,30_002));
    }
    @Test void limiterBoundsKeysAndToleratesClockRollback() {
        var limiter=new BoundedRateLimiter<String>(2,2,1);
        assertTrue(limiter.allow("a",1000));assertTrue(limiter.allow("a",1000));assertFalse(limiter.allow("a",1000));
        assertFalse(limiter.allow("a",0));assertTrue(limiter.allow("b",1000));assertFalse(limiter.allow("c",1000));
        assertTrue(limiter.allow("a",2000));assertEquals(2,limiter.size());assertTrue(limiter.allow("c",200000));
    }
    @Test void discordOnlyAllowsCanonicalHttpsWebhookPaths() {
        String path="/api/webhooks/123456789012345678/abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQRSTUVWXYZ0123456789";
        assertTrue(DiscordNotifier.valid(URI.create("https://discord.com"+path)));
        for(String bad:new String[]{"http://discord.com"+path,"https://discord.com.evil.test"+path,"https://discord.com@evil.test"+path,
            "https://127.0.0.1"+path,"https://discord.com:8443"+path,"https://discord.com"+path+"?token=x","https://discord.com"+path+"#fragment",
            "https://discord.com/api/webhooks/1/tiny"})assertFalse(DiscordNotifier.valid(URI.create(bad)),bad);
    }
    @Test void replayCapsMemoryAndSeparatesPlayers() {
        var replay=new ReplayBuffer(4096,2048);UUID id=UUID.randomUUID();
        for(int i=0;i<1000;i++)replay.add(Sample.builder(id,Sample.Kind.MOVE,i*200,1).value("x",i).value("pitch",Double.NaN).build());
        assertTrue(replay.bytes()<=2048);assertTrue(replay.json(id).contains("NaN"));assertFalse(replay.json(id).contains("\"x\":0.0"));
        for(int i=0;i<1000;i++)replay.add(Sample.builder(UUID.randomUUID(),Sample.Kind.ATTACK,i*200,1).value("x",i).build());
        assertTrue(replay.bytes()<=4096);assertTrue(replay.players()<=4);replay.clear();assertEquals(0,replay.bytes());
    }
    @Test void jsonEscapingContainsNoRawControls() {
        assertEquals("\"\\\"\\\\\\n\\r\\t\\u0001\"",ReplayBuffer.quote("\"\\\n\r\t\u0001"));
    }
    @Test void queuedPacketReplayIsNotSuppressedByNewerMovementFrame() {
        var replay=new ReplayBuffer(8192,4096);UUID id=UUID.randomUUID();
        replay.add(Sample.builder(id,Sample.Kind.MOVE,1050,1).value("x",1).build());
        replay.add(Sample.builder(id,Sample.Kind.PACKET,1030,1).value("held-slot",15).build());
        String json=replay.json(id);assertTrue(json.contains("held-slot"));assertTrue(json.indexOf("PACKET")<json.indexOf("MOVE"));
    }
}
