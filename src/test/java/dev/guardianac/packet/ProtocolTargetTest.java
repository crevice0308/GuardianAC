/* SPDX-License-Identifier: GPL-3.0-only */
package dev.guardianac.packet;

import com.github.retrooper.packetevents.manager.server.ServerVersion;
import com.github.retrooper.packetevents.protocol.player.ClientVersion;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class ProtocolTargetTest {
    @Test void unobservedSessionIsNeverAssumedToBeNative() {
        var adapter=new PacketAdapter(null,ignored->{});
        assertFalse(adapter.exactProtocol(new java.util.UUID(7,9)));
    }
    @Test void actualAdapterGuardAcceptsOnlyExactNamedTargetBeforeParsing() {
        assertTrue(PacketAdapter.supportedProtocol(ServerVersion.V_26_3,ClientVersion.V_26_3));
        for(ServerVersion version:ServerVersion.values())if(version!=ServerVersion.V_26_3)
            assertFalse(PacketAdapter.supportedProtocol(version,ClientVersion.V_26_3),version.name());
        for(ClientVersion version:ClientVersion.values())if(version!=ClientVersion.V_26_3)
            assertFalse(PacketAdapter.supportedProtocol(ServerVersion.V_26_3,version),version.name());
        assertFalse(PacketAdapter.supportedProtocol(null,ClientVersion.V_26_3));
        assertFalse(PacketAdapter.supportedProtocol(ServerVersion.V_26_3,null));
    }
}
