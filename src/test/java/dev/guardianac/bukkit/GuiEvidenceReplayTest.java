/* SPDX-License-Identifier: GPL-3.0-only */
package dev.guardianac.bukkit;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.junit.jupiter.api.Test;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;

class GuiEvidenceReplayTest {
    private String proof(){return "{\"schemaVersion\":2,\"replay\":[{\"kind\":\"MOVE\",\"time\":123,\"tick\":10,\"measurements\":{},\"flags\":[]}]}";}
    private JsonObject wrapper(){
        JsonObject payload=new JsonObject();payload.addProperty("schemaVersion",1);
        payload.addProperty("kind","administrator-reviewed-permanent-ip");
        payload.addProperty("sourceCaseId","GAC-12345678");payload.addProperty("sourceEvidenceId","evidence-123");
        payload.addProperty("sourceEvidenceSha256","a".repeat(64));
        payload.add("sourceProof",JsonParser.parseString(proof()));return payload;
    }
    @Test void originalAndOneLevelApprovalReplayHaveSameFrames() {
        var original=GuiManager.replayView(proof());var approved=GuiManager.replayView(wrapper().toString());
        assertEquals(1,original.frames().size());assertEquals(original.frames(),approved.frames());
        assertTrue(original.sourceBinding().isEmpty());
        assertEquals(List.of("GAC-12345678","evidence-123","SHA-256:","a".repeat(64)),approved.sourceBinding());
        assertThrows(UnsupportedOperationException.class,()->approved.sourceBinding().add("changed"));
        assertTrue(GuiManager.replayView("{\"version\":1,\"replay\":[]}").frames().isEmpty());
    }
    @Test void nestedUnknownOrMalformedEnvelopesAreNotTraversed() {
        JsonObject nested=wrapper();nested.add("sourceProof",wrapper());
        assertThrows(IllegalArgumentException.class,()->GuiManager.replayView(nested.toString()));
        JsonObject unknown=wrapper();unknown.addProperty("kind","unknown");
        assertTrue(GuiManager.replayView(unknown.toString()).frames().isEmpty());
        JsonObject invalid=wrapper();invalid.addProperty("sourceCaseId","address 203.0.113.47");
        assertThrows(IllegalArgumentException.class,()->GuiManager.replayView(invalid.toString()));
        JsonObject version=wrapper();version.addProperty("schemaVersion",99);
        assertThrows(IllegalArgumentException.class,()->GuiManager.replayView(version.toString()));
        assertThrows(IllegalArgumentException.class,()->GuiManager.replayView(null));
    }
}
