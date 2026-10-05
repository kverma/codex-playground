package atlas.poc;

import org.junit.jupiter.api.Test;
import java.nio.file.*;
import java.util.*;
import static atlas.poc.ArchiveRecovery.*;
import static atlas.poc.Retention.*;
import static atlas.poc.DraftProcessClient.*;
import static atlas.poc.DurableArchiveServer.*;
import static org.junit.jupiter.api.Assertions.*;

class SplitArchiveProcessTest {
    DurableArchiveProcessTest.Harness harness(String name) throws Exception { return new DurableArchiveProcessTest().new Harness(name,true); }
    Issued edit(DurableArchiveProcessTest.Harness h) throws Exception {
        Draft draft=h.run(h.message("prepare",null,null,null),"OK").reply().draft();
        return h.run(h.message("issue",draft,null,null),"OK").reply().issued();
    }
    void seal(DurableArchiveProcessTest.Harness h,Issued issued) throws Exception {
        h.cmd(Kind.CLOCK,issued.ticket().expiresAt(),"OK");h.cmd(Kind.SEAL,1,"OK");h.cmd(Kind.PUBLISH,1,"OK");
    }
    void invalid(DurableArchiveProcessTest.Harness h,Kind kind,String expectedError) throws Exception {
        Hot before=h.actual();byte[] root=Files.readAllBytes(h.state);
        Path result=h.process(h.command(kind,kind==Kind.PRUNE?1:0,null,Mode.NORMAL,null),65);
        var report=JSON.readTree(result.toFile());assertEquals("CHECKPOINT_INVALID",report.get("phase").asText());
        assertTrue(report.get("error").asText().contains(expectedError),report.toString());
        assertEquals(before,h.actual());assertArrayEquals(root,Files.readAllBytes(h.state));
    }
    // BEGIN ATLAS SCENARIO
    /**
     * Goal: Ignore unpublished recovery facts after a server crash
     * Boundary: Store archive and authority separately and halt before or after the root references the new bytes
     * Expected: Unreferenced files cannot authorize cleanup; published facts recover the exact edit and keep retired identities closed.
     */
    @org.junit.jupiter.api.DisplayName("AT-082 | Ignore unpublished recovery facts after a server crash")
    // END ATLAS SCENARIO
    @Test void splitPublicationCrashesNeverPromoteUnreferencedArchiveOrAuthority() throws Exception {
        try(var h=harness("publication")) {
            h.run(h.command(Kind.SAVE,0,"old",Mode.NORMAL,null),"OK");
            Draft draft=h.run(h.message("prepare",null,null,null),"OK").reply().draft();byte[] root=Files.readAllBytes(h.state);
            Report tentative=h.crash(h.message("issue",draft,null,"BIND_AFTER_AUTHORITY"),false);
            assertArrayEquals(root,Files.readAllBytes(h.state));assertEquals(0,SplitArchiveCheckpoint.load(h.state,h.subject).highWater());
            assertEquals(1,tentative.facts().highWater());assertEquals(0,h.actual().allocated());
            try(var files=Files.list(h.state.resolveSibling("authority"))) { assertTrue(files.count()>=3,"orphan authority bytes must exist"); }
            Report committed=h.crash(h.message("issue",draft,null,"BIND_AFTER_ROOT"),true);
            Issued issued=committed.facts().bindings().values().iterator().next();assertEquals(tentative.facts().bindings(),committed.facts().bindings());
            assertEquals(issued,h.run(h.message("issue",draft,null,null),"OK").reply().issued());
            var receipt=h.run(h.message("commit",null,issued,null),"OK").reply().receipt();seal(h,issued);
            root=Files.readAllBytes(h.state);
            Report copy=h.crash(h.command(Kind.COPY,1,null,Mode.NORMAL,"COPY_AFTER_ARCHIVE"),false);
            assertEquals(receipt,copy.facts().archive().get(1L).item().receipt());assertArrayEquals(root,Files.readAllBytes(h.state));
            assertTrue(SplitArchiveCheckpoint.load(h.state,h.subject).archive().isEmpty());
            try(var files=Files.list(h.state.resolveSibling("archive"))) { assertEquals(2,files.count(),"unreferenced complete archive must exist"); }
            h.cmd(Kind.CERTIFY,1,"UNCOVERED");h.cmd(Kind.PRUNE,1,"UNCOVERED");
            h.crash(h.command(Kind.COPY,1,null,Mode.NORMAL,"COPY_AFTER_ROOT"),true);
            assertEquals(receipt,SplitArchiveCheckpoint.load(h.state,h.subject).archive().get(1L).item().receipt());
            h.crash(h.command(Kind.CERTIFY,1,null,Mode.NORMAL,"CERTIFY_AFTER_ROOT"),true);
            h.cmd(Kind.PRUNE,1,"OK");assertTrue(h.actual().rows().isEmpty());
            h.run(h.command(Kind.RESTORE,0,"old",Mode.NORMAL,null),"OK");h.cmd(Kind.RECOVER,0,"OK");
            assertEquals(receipt.after(),h.actual().head());assertEquals(1,h.actual().floor());
            h.cmd(Kind.CLOCK,0,"OK");h.run(h.message("issue",draft,null,null),"REQUEST_TOO_OLD");h.check();
        }
    }
    // BEGIN ATLAS SCENARIO
    /**
     * Goal: Stop cleanup when either recovery store cannot supply the exact facts
     * Boundary: Independently remove, obstruct or replace authority and archive files with valid older content; remove archive during fenced recovery
     * Expected: Every failed read preserves Cassandra state and the trusted root; restoring the exact bytes allows recovery with the original receipt.
     */
    @org.junit.jupiter.api.DisplayName("AT-083 | Stop cleanup when either recovery store cannot supply the exact facts")
    // END ATLAS SCENARIO
    @Test void independentlyMissingUnreadableOrStaleFilesBlockPruningAndRecovery() throws Exception {
        try(var h=harness("independent-reads")) {
            var initial=SplitArchiveCheckpoint.root(h.state,h.subject);
            byte[] oldAuthority=SplitArchiveCheckpoint.read(h.state,"authority",initial.authority());
            byte[] oldArchive=SplitArchiveCheckpoint.read(h.state,"archive",initial.archive());
            h.run(h.command(Kind.SAVE,0,"old",Mode.NORMAL,null),"OK");Issued issued=edit(h);
            var receipt=h.run(h.message("commit",null,issued,null),"OK").reply().receipt();seal(h,issued);
            h.cmd(Kind.COPY,1,"OK");h.cmd(Kind.CERTIFY,1,"OK");
            var current=SplitArchiveCheckpoint.root(h.state,h.subject);var faults=new ArrayList<Map<String,Object>>();
            for(String domain:List.of("authority","archive")) {
                Path path=SplitArchiveCheckpoint.object(h.state,domain,domain.equals("authority")?current.authority():current.archive());
                byte[] trusted=Files.readAllBytes(path);
                for(String damage:List.of("missing","unreadable","stale")) {
                    if(damage.equals("stale"))Files.write(path,domain.equals("authority")?oldAuthority:oldArchive);
                    else { Files.delete(path);if(damage.equals("unreadable"))Files.createDirectory(path); }
                    invalid(h,Kind.PRUNE,damage.equals("missing")?"NoSuchFileException":damage.equals("unreadable")?"Is a directory":"content differs from trusted root");
                    faults.add(Map.of("step",h.step,"domain",domain,"damage",damage,"root",current,
                        "oldContentSha256",SplitArchiveCheckpoint.hash(domain.equals("authority")?oldAuthority:oldArchive),
                        "expectedContentSha256",SplitArchiveCheckpoint.hash(trusted)));
                    Files.deleteIfExists(path);Files.write(path,trusted); // Controlled repair by test, not candidate fallback.
                }
            }
            save(h.folder.resolve("read-faults.json"),faults);
            assertEquals(receipt,h.actual().rows().get(1L).receipt());h.cmd(Kind.PRUNE,1,"OK");
            h.run(h.command(Kind.RESTORE,0,"old",Mode.NORMAL,null),"OK");assertTrue(h.actual().fenced());
            var restored=SplitArchiveCheckpoint.root(h.state,h.subject);Path archive=SplitArchiveCheckpoint.object(h.state,"archive",restored.archive());
            byte[] trusted=Files.readAllBytes(archive);Files.delete(archive);invalid(h,Kind.RECOVER,"NoSuchFileException");
            assertTrue(h.actual().fenced());Files.write(archive,trusted);h.cmd(Kind.RECOVER,0,"OK");
            assertEquals(receipt.after(),h.actual().head());assertEquals(1,h.actual().floor());h.check();
        }
    }
    // BEGIN ATLAS SCENARIO
    /**
     * Goal: Expose why Atlas needs a recovery authority that cannot roll back
     * Boundary: Deliberately restore an older valid root after pruning and logical database restore
     * Expected: The broken trust assumption actually reopens a retired identity; the independent history checker rejects recovery rather than treating checksums as freshness proof.
     */
    @org.junit.jupiter.api.DisplayName("AT-084 | Expose why Atlas needs a recovery authority that cannot roll back")
    // END ATLAS SCENARIO
    @Test void rolledBackTrustedRootIsDetectedAsUnsafeByIndependentHistory() throws Exception {
        try(var h=harness("mutant-root-rollback")) {
            h.run(h.command(Kind.SAVE,0,"old",Mode.NORMAL,null),"OK");byte[] oldRoot=Files.readAllBytes(h.state);
            Issued issued=edit(h);var receipt=h.run(h.message("commit",null,issued,null),"OK").reply().receipt();
            seal(h,issued);h.cmd(Kind.COPY,1,"OK");h.cmd(Kind.CERTIFY,1,"OK");h.cmd(Kind.PRUNE,1,"OK");
            h.run(h.command(Kind.RESTORE,0,"old",Mode.NORMAL,null),"OK");assertTrue(h.actual().fenced());
            save(h.folder.resolve("rollback-witness.json"),Map.of("oldRoot",JSON.readTree(oldRoot),"currentRoot",JSON.readTree(h.state.toFile()),"before",h.actual(),"originalReceipt",receipt));
            Files.write(h.state,oldRoot); // Deliberately violate the trusted-root freshness assumption.
            assertEquals(0,SplitArchiveCheckpoint.load(h.state,h.subject).highWater(),"valid old root is not detectable by checksums alone");
            h.cmd(Kind.RECOVER,0,"OK");assertFalse(h.actual().fenced());assertEquals(0,h.actual().floor());
            Issued reused=edit(h);assertEquals(issued.ticket().operation(),reused.ticket().operation());assertEquals(1,reused.ticket().sequence());
            var replay=h.run(h.message("commit",null,reused,null),"OK").reply().receipt();assertNotEquals(receipt,replay);
            var result=new ArchiveRecoveryChecker().check(h.genesis,h.frames);
            assertEquals(ArchiveRecoveryChecker.Verdict.INVALID,result.verdict());assertTrue(result.reason().contains("RECOVER"),result.reason());
            save(h.folder.resolve("history.json"),new ArchiveRecoveryChecker.Evidence(0,h.genesis,h.frames,result));
            var saved=JSON.readValue(h.folder.resolve("history.json").toFile(),ArchiveRecoveryChecker.Evidence.class);
            assertEquals(result,new ArchiveRecoveryChecker().check(saved.genesis(),saved.frames()));
        }
    }
}
