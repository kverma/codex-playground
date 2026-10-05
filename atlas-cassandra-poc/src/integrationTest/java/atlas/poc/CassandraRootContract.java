package atlas.poc;

import org.junit.jupiter.api.Test;
import java.nio.file.*;
import java.util.*;
import static atlas.poc.DraftProcessClient.save;
import static org.junit.jupiter.api.Assertions.*;

abstract class CassandraRootContract {
    abstract CassandraRootAuthority open(UUID subject,boolean peer);
    abstract String folder();
    // BEGIN ATLAS SCENARIO
    /**
     * Goal: Reject an outdated writer even when the recovery pointer returns to its old value
     * Boundary: Read a root token, publish another root and return to the original content, then submit the captured old conditional update through another client or DC
     * Expected: The old conditional write is not applied; the complete authoritative root and its current token remain unchanged.
     */
    @org.junit.jupiter.api.DisplayName("AT-086 | Reject an outdated writer even when the recovery pointer returns to its old value")
    // END ATLAS SCENARIO
    @Test void staleAuthorityWriterCannotOverwriteRootAfterItChangesBack() throws Exception {
        UUID subject=UUID.randomUUID();var first=new SplitArchiveCheckpoint.Root(1,subject,"a".repeat(64),"b".repeat(64));
        var second=new SplitArchiveCheckpoint.Root(1,subject,"c".repeat(64),"d".repeat(64));
        try(var a=open(subject,false);var peer=open(subject,true)) {
            var missing=assertThrows(IllegalStateException.class,a::read);assertTrue(missing.getMessage().contains("AUTHORITY_MISSING"));
            a.bootstrap(first);var old=a.read();assertEquals(old,peer.read());
            assertTrue(peer.compareAndSet(old,second));var changed=a.read();assertEquals(second,changed.root());assertNotEquals(old.guard(),changed.guard());
            assertTrue(a.compareAndSet(changed,first));var current=peer.read();assertEquals(first,current.root());assertNotEquals(old.guard(),current.guard());
            boolean stale=peer.compareAndSet(old,second);assertFalse(stale);assertEquals(current,a.read());
            save(Path.of("build/evidence",folder(),"root-cas.json"),Map.of("subject",subject,"old",old,"changed",changed,"current",current,"staleApplied",stale,"after",a.read()));
        }
    }
}
