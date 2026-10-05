package atlas.poc;

import org.junit.jupiter.api.Test;
import java.nio.file.*;
import java.util.*;
import static atlas.poc.ArchiveRecovery.*;
import static atlas.poc.Retention.*;
import static atlas.poc.DraftProcessClient.*;
import static org.junit.jupiter.api.Assertions.*;

class CassandraRootProcessTest {
    // BEGIN ATLAS SCENARIO
    /**
     * Goal: Keep retired offer edits closed when a local recovery pointer is restored
     * Boundary: Commit the root in a separate Cassandra table, halt before updating its local cache, then roll back or remove that cache during logical offer recovery
     * Expected: Restart reads the authoritative root, recovers exact identities and receipts, and never reopens retired edits from an old local pointer.
     */
    @org.junit.jupiter.api.DisplayName("AT-085 | Keep retired offer edits closed when a local recovery pointer is restored")
    // END ATLAS SCENARIO
    @Test void authoritativeRootSurvivesLocalRollbackAndLostPublicationReply() throws Exception {
        try(var h=new DurableArchiveProcessTest().new Harness("logical-restore","authority")) {
            h.run(h.command(Kind.SAVE,0,"old",Mode.NORMAL,null),"OK");byte[] oldCache=Files.readAllBytes(h.state);
            Draft draft=h.run(h.message("prepare",null,null,null),"OK").reply().draft();
            var bound=h.crash(h.message("issue",draft,null,"BIND_AFTER_ROOT_CAS"),true);
            assertArrayEquals(oldCache,Files.readAllBytes(h.state));assertEquals(0,h.actual().allocated());
            Issued issued=bound.facts().bindings().values().iterator().next();
            try(var authority=new CassandraRootAuthority(h.subject)) {
                var version=authority.read();save(h.folder.resolve("binding-authority.json"),version);
                assertEquals(1,SplitArchiveCheckpoint.load(h.state,h.subject,version.root()).highWater());
                assertNotEquals(SplitArchiveCheckpoint.root(h.state,h.subject),version.root());
            }
            assertEquals(issued,h.run(h.message("issue",draft,null,null),"OK").reply().issued());
            var receipt=h.run(h.message("commit",null,issued,null),"OK").reply().receipt();
            h.cmd(Kind.CLOCK,issued.ticket().expiresAt(),"OK");h.cmd(Kind.SEAL,1,"OK");h.cmd(Kind.PUBLISH,1,"OK");h.cmd(Kind.COPY,1,"OK");
            byte[] beforeCert=Files.readAllBytes(h.state);
            h.crash(h.command(Kind.CERTIFY,1,null,Mode.NORMAL,"CERTIFY_AFTER_ROOT_CAS"),true);
            assertArrayEquals(beforeCert,Files.readAllBytes(h.state));
            try(var authority=new CassandraRootAuthority(h.subject)) {
                assertEquals(1,SplitArchiveCheckpoint.load(h.state,h.subject,authority.read().root()).certifiedFloor());
            }
            h.cmd(Kind.PRUNE,1,"OK");assertEquals(1,h.actual().floor());
            h.run(h.command(Kind.RESTORE,0,"old",Mode.NORMAL,null),"OK");assertTrue(h.actual().fenced());
            try(var authority=new CassandraRootAuthority(h.subject)) {
                var current=authority.read();Files.write(h.state,oldCache);
                save(h.folder.resolve("cache-rollback.json"),Map.of("cache",JSON.readTree(oldCache),"authority",current,"hot",h.actual()));
                assertEquals(current,authority.read());
                h.cmd(Kind.RECOVER,0,"OK");assertEquals(receipt.after(),h.actual().head());assertEquals(1,h.actual().floor());
            }
            h.cmd(Kind.CLOCK,0,"OK");h.run(h.message("issue",draft,null,null),"REQUEST_TOO_OLD");
            h.run(h.message("commit",null,issued,null),"REQUEST_TOO_OLD");
            // A missing cache also cannot force a bootstrap or authority rewind.
            Files.delete(h.state);h.cmd(Kind.VIEW,0,"OK");assertEquals(receipt.after(),h.actual().head());h.check();
        }
    }
}
