/* SPDX-License-Identifier: GPL-3.0-only */
package dev.guardianac.security;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.io.IOException;
import java.nio.file.*;
import java.nio.file.attribute.PosixFilePermissions;
import static org.junit.jupiter.api.Assertions.*;

class IntegrationSafetyTest {
    @TempDir Path directory;
    @Test void closeInvalidatesQueuedClickAndAsyncLookup() {
        var gate=new SessionActionGate();long ticket=gate.issue();gate.close();
        assertFalse(gate.consume(ticket));assertEquals(-1,gate.issue());assertFalse(gate.consume(-1));
    }
    @Test void newNavigationInvalidatesPreviousLookup() {
        var gate=new SessionActionGate();long old=gate.issue(),current=gate.issue();
        assertFalse(gate.consume(old));assertTrue(gate.consume(current));assertFalse(gate.consume(current));
    }
    @Test void permissionLossAtDispatchRejectsAndConsumesDelayedAction() {
        var gate=new SessionActionGate();long ticket=gate.issue();
        assertFalse(gate.consume(ticket,false));assertFalse(gate.consume(ticket,true));
        assertTrue(gate.consume(gate.issue(),true));
    }
    @Test void rejectedOlderTicketCannotInvalidateNewerNavigation() {
        var gate=new SessionActionGate();long old=gate.issue(),current=gate.issue();
        assertFalse(gate.consume(old,false));assertTrue(gate.consume(current));
    }
    @Test void cancellationCannotCancelAnotherActorOrReplacementConfirmation() {
        var confirmations=new ConfirmationManager<String>();
        var old=confirmations.request("alice","old",100);
        var current=confirmations.request("alice","current",101);
        confirmations.cancel(old.token(),"alice");confirmations.cancel(current.token(),"mallory");
        assertEquals("current",confirmations.consume(current.token(),"alice",102).orElseThrow());
    }
    @Test void cancellationPreventsCommandReplayAfterGuiClose() {
        var confirmations=new ConfirmationManager<String>();var pending=confirmations.request("alice","case",100);
        confirmations.cancel(pending.token(),"alice");assertTrue(confirmations.consume(pending.token(),"alice",101).isEmpty());
    }
    @Test void backwardsClockFailsClosedForConfirmation() {
        var confirmations=new ConfirmationManager<String>();var pending=confirmations.request("alice","case",1000);
        assertTrue(confirmations.consume(pending.token(),"alice",999).isEmpty());
        assertTrue(confirmations.consume(pending.token(),"alice",1001).isEmpty());
        assertThrows(ArithmeticException.class,()->confirmations.request("alice","case",Long.MAX_VALUE));
    }
    @Test void nativeBanOwnershipNeverAdoptsExternalOrAnotherCaseSource() {
        String marker="GuardianAC:GAC-12345678:random-token";
        assertTrue(NativeBanOwnership.mayReplace(marker,false,null));
        assertTrue(NativeBanOwnership.mayReplace(marker,true,marker));
        for(String external:new String[]{"CONSOLE","GuardianAC","GuardianAC:GAC-12345678:other-token",marker+"-modified"}) {
            assertFalse(NativeBanOwnership.mayReplace(marker,true,external));assertFalse(NativeBanOwnership.mayRemove(marker,external));
        }
        assertFalse(NativeBanOwnership.mayReplace(marker,true,null));assertFalse(NativeBanOwnership.mayRemove(marker,null));
        assertFalse(NativeBanOwnership.mayRemove(null,null));
    }
    @Test void exportsAreBoundedAndReplaceExistingCaseAtomically() throws Exception {
        Path export=PrivateFiles.replace(directory,"GAC-12345678.json",new byte[]{1,2},8,1);
        assertArrayEquals(new byte[]{1,2},Files.readAllBytes(export));
        assertThrows(IOException.class,()->PrivateFiles.replace(directory,"GAC-99999999.json",new byte[1],8,1));
        assertThrows(IOException.class,()->PrivateFiles.replace(directory,"GAC-12345678.json",new byte[9],8,1));
        PrivateFiles.replace(directory,"GAC-12345678.json",new byte[]{3},8,1);
        assertArrayEquals(new byte[]{3},Files.readAllBytes(export));
        try(var children=Files.list(directory)){assertEquals(1,children.count());}
    }
    @Test void protectedWritesRejectTraversalAndSymlinkedTargets() throws Exception {
        Path outside=directory.resolve("outside.txt");Files.writeString(outside,"keep");Path root=directory.resolve("exports");Files.createDirectory(root);
        assertThrows(IOException.class,()->PrivateFiles.replace(root,"../outside.txt",new byte[]{1},8,8));
        Files.createSymbolicLink(root.resolve("GAC-12345678.json"),outside);
        assertThrows(IOException.class,()->PrivateFiles.replace(root,"GAC-12345678.json",new byte[]{1},8,8));
        assertEquals("keep",Files.readString(outside));
        assertThrows(IOException.class,()->PrivateFiles.read(root.resolve("GAC-12345678.json"),8));
    }
    @Test void protectedWritesRejectSymlinkedParentDirectories() throws Exception {
        Path target=directory.resolve("real");Files.createDirectory(target);Path link=directory.resolve("link");Files.createSymbolicLink(link,target);
        assertThrows(IOException.class,()->PrivateFiles.replace(link.resolve("exports"),"evidence.json",new byte[]{1},8,8));
        assertFalse(Files.exists(target.resolve("exports")));
    }
    @Test void replacingHardlinkDoesNotOverwriteOutsideFile() throws Exception {
        Path outside=directory.resolve("outside.txt");Files.writeString(outside,"keep");Path root=directory.resolve("exports");Files.createDirectory(root);
        Files.createLink(root.resolve("case.json"),outside);
        PrivateFiles.replace(root,"case.json",new byte[]{1,2},8,8);
        assertEquals("keep",Files.readString(outside));
    }
    @Test void exportsAndParentArePrivateOnPosix() throws Exception {
        Path root=directory.resolve("exports");Path file=PrivateFiles.replace(root,"case.json",new byte[]{1},8,8);
        if(Files.getFileStore(file).supportsFileAttributeView("posix")) {
            assertEquals(PosixFilePermissions.fromString("rw-------"),Files.getPosixFilePermissions(file));
            assertEquals(PosixFilePermissions.fromString("rwx------"),Files.getPosixFilePermissions(root));
        }
    }
    @Test void protectedReadsAreSizeBounded() throws Exception {
        Path file=directory.resolve("input.json");Files.writeString(file,"123456789");
        assertThrows(IOException.class,()->PrivateFiles.read(file,8));assertEquals(9,PrivateFiles.read(file,9).length);
    }
    @Test void limiterRejectsNonFiniteConfiguration() {
        assertThrows(IllegalArgumentException.class,()->new BoundedRateLimiter<>(1,Double.NaN,1));
        assertThrows(IllegalArgumentException.class,()->new BoundedRateLimiter<>(1,1,Double.POSITIVE_INFINITY));
    }
}
