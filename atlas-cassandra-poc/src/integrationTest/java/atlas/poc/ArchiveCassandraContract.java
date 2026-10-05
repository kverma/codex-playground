package atlas.poc;

import org.junit.jupiter.api.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.file.*;
import java.util.*;
import static atlas.poc.ArchiveRecovery.*;
import static atlas.poc.Transactions.Group.*;
import static org.junit.jupiter.api.Assertions.*;

/** Same archive scenarios run against one-node and cross-coordinator Cassandra fixtures. */
abstract class ArchiveCassandraContract {
    abstract ArchiveCassandraFixture open(UUID subject,Storage external,Broken broken,boolean peer);
    abstract String evidenceFolder();
    final class Scenario implements AutoCloseable {
        final UUID subject;
        final Transactions.Snapshot genesis;
        final Storage external;
        java.util.function.Consumer<Frame> observer=f->{};
        final List<Frame> frames=new ArrayList<>();
        final List<Map<String,Object>> witnesses=new ArrayList<>();
        final String name;
        ArchiveCassandraFixture store;
        Scenario(String name) { this(name,Broken.NONE); }
        Scenario(String name,Broken broken) { this(name,UUID.randomUUID(),new Storage(Transactions.initial()),broken); }
        Scenario(String name,UUID subject,Storage external,Broken broken) {
            this.name=name;this.subject=subject;this.external=external;genesis=external.genesis;
            store=open(subject,external,broken,false);
        }
        Command cmd(Kind k,long n) { return new Command(k,n,null,null,Mode.NORMAL,true); }
        Frame run(Command c) { Frame f=store.execute(c);frames.add(f);observer.accept(f);return f; }
        Frame run(Kind k,long n) { return run(cmd(k,n)); }
        Frame ok(Command c) { Frame f=run(c);assertEquals("OK",f.outcome().code(),c.kind().name());return f; }
        Frame ok(Kind k,long n) { return ok(cmd(k,n)); }
        void code(String code,Kind k,long n) { assertEquals(code,run(k,n).outcome().code()); }
        void named(Kind k,String name) { ok(new Command(k,0,null,name,Mode.NORMAL,true)); }
        void reserve(long n,Map<Transactions.Group,String> changes) {
            var r=Transactions.edit(new UUID(subject.getMostSignificantBits(),n),store.read().hot().head(),changes);
            ok(new Command(Kind.RESERVE,n,new Grant(n,10,r),null,Mode.NORMAL,true));ok(Kind.INSTALL,n);
        }
        void base() {
            named(Kind.SAVE,"old");reserve(1,Map.of(ECONOMICS,"400"));reserve(2,Map.of(ROYALTY,"2000"));
            ok(Kind.ACCEPT,2);ok(Kind.ACCEPT,1);reserve(3,Map.of(ROYALTY,"2500"));
        }
        void seal() { ok(Kind.CLOCK,10);ok(Kind.SEAL,3);for(long n=1;n<=3;n++)ok(Kind.PUBLISH,n); }
        void archive() { for(long n=1;n<=3;n++)ok(Kind.COPY,n);ok(Kind.CERTIFY,3); }
        void peer() { store.close();store=open(subject,external,Broken.NONE,true); }
        void check(ArchiveRecoveryChecker.Verdict expected) { assertEquals(expected,new ArchiveRecoveryChecker().check(genesis,frames).verdict()); }
        public void close() throws Exception {
            try {
                Path folder=Path.of("build/evidence",evidenceFolder(),name);Files.createDirectories(folder);
                var mapper=new ObjectMapper();var verdict=new ArchiveRecoveryChecker().check(genesis,frames);
                var evidence=new ArchiveRecoveryChecker.Evidence(0,genesis,frames,verdict);
                Path file=folder.resolve("history.json");mapper.writerWithDefaultPrettyPrinter().writeValue(file.toFile(),evidence);
                mapper.writerWithDefaultPrettyPrinter().writeValue(folder.resolve("witnesses.json").toFile(),witnesses);
                var replay=mapper.readValue(file.toFile(),ArchiveRecoveryChecker.Evidence.class);
                assertEquals(verdict.verdict(),new ArchiveRecoveryChecker().check(replay.genesis(),replay.frames()).verdict());
            } finally { store.close(); }
        }
    }
    // BEGIN ATLAS SCENARIO
    /**
     * Goal: Keep Cassandra retry records until audit history is complete
     * Boundary: Try pruning without archive coverage and with a partial archive object, then complete and certify the archive
     * Expected: Blocked attempts preserve all slot rows; verified coverage permits actual row deletion while preserving the offer.
     */
    @org.junit.jupiter.api.DisplayName("AT-062 | Keep Cassandra retry records until audit history is complete")
    // END ATLAS SCENARIO
    @Test void archiveCoverageMustPrecedeActualRowDeletion() throws Exception {
        try(var h=new Scenario("archive-before-delete")) {
            h.base();h.seal();h.code("UNCOVERED",Kind.PRUNE,3);
            h.ok(new Command(Kind.COPY,1,null,null,Mode.PARTIAL,true));h.code("UNCOVERED",Kind.CERTIFY,1);
            assertEquals(Set.of(1L,2L,3L),h.store.read().hot().rows().keySet());
            h.archive();h.peer();h.ok(Kind.PRUNE,3);
            Hot actual=h.store.read().hot();assertEquals(3,actual.floor());assertTrue(actual.rows().isEmpty());
            assertEquals("400",actual.head().value(ECONOMICS));assertEquals("2000",actual.head().value(ROYALTY));
            h.check(ArchiveRecoveryChecker.Verdict.VALID);
        }
    }
    // BEGIN ATLAS SCENARIO
    /**
     * Goal: Recover an offer from an old logical Cassandra snapshot
     * Boundary: Restore old hot-state rows while the simulated external archive and authority survive; reopen through another coordinator
     * Expected: Keep edits fenced until complete history is reconstructed; old IDs stay closed and a new edit may proceed.
     */
    @org.junit.jupiter.api.DisplayName("AT-063 | Recover an offer from an old logical Cassandra snapshot")
    // END ATLAS SCENARIO
    @Test void restoredOfferRecoversHistoryBeforeAllowingNewEdits() throws Exception {
        try(var h=new Scenario("logical-restore")) {
            h.base();var expected=h.store.read().hot().head();h.seal();h.archive();h.ok(Kind.PRUNE,3);
            h.peer();h.named(Kind.RESTORE,"old");h.code("FENCED",Kind.ACCEPT,1);h.ok(Kind.RECOVER,0);
            assertEquals(expected,h.store.read().hot().head());assertTrue(h.store.read().hot().rows().isEmpty());
            h.ok(Kind.CLOCK,0);h.code("CLOSED",Kind.ACCEPT,1);h.code("CLOSED",Kind.ACCEPT,3);
            h.reserve(4,Map.of(ROYALTY,"3000"));h.ok(Kind.ACCEPT,4);h.check(ArchiveRecoveryChecker.Verdict.VALID);
        }
    }
    // BEGIN ATLAS SCENARIO
    /**
     * Goal: Reject an old writer after cleanup or recovery changes the offer guard
     * Boundary: Capture a valid Cassandra mutation, then deliver it after sealing, after logical restore and after recovery
     * Expected: All three stale conditional writes must fail; no retired edit becomes accepted.
     */
    @org.junit.jupiter.api.DisplayName("AT-064 | Reject an old writer after cleanup or recovery changes the offer guard")
    // END ATLAS SCENARIO
    @Test void delayedAcceptanceCannotCrossSealingOrRestore() throws Exception {
        try(var h=new Scenario("delayed-writer")) {
            h.base();var pending=h.store.prepareAcceptance(3);
            try(var oldWriter=open(h.subject,h.external,Broken.NONE,true)) {
                h.seal();boolean sealed=oldWriter.deliver(pending);h.witnesses.add(Map.of("stage","after-seal","applied",sealed));assertFalse(sealed);
                h.archive();h.ok(Kind.PRUNE,3);h.named(Kind.RESTORE,"old");
                boolean restored=oldWriter.deliver(pending);h.witnesses.add(Map.of("stage","after-restore","applied",restored));assertFalse(restored);
                h.code("FENCED",Kind.ACCEPT,3);h.ok(Kind.RECOVER,0);
                boolean recovered=oldWriter.deliver(pending);h.witnesses.add(Map.of("stage","after-recovery","applied",recovered));assertFalse(recovered);
                h.ok(Kind.VIEW,0);h.check(ArchiveRecoveryChecker.Verdict.VALID);
            }
        }
    }
    // BEGIN ATLAS SCENARIO
    /**
     * Goal: Block edits when Cassandra recovery lacks trusted facts
     * Boundary: Delete an archive fixture record, leave a newer accepted edit unarchived or take the authority fixture offline
     * Expected: Keep the restored offer fenced and retain an explicit unresolved outcome.
     */
    @org.junit.jupiter.api.DisplayName("AT-065 | Block edits when Cassandra recovery lacks trusted facts")
    // END ATLAS SCENARIO
    @Test void incompleteRecoveryFactsKeepRestoredOfferReadOnly() throws Exception {
        for(String boundary:List.of("missing-record","unarchived-edit","authority-down"))try(var h=new Scenario(boundary)) {
            h.base();h.seal();h.archive();h.ok(Kind.PRUNE,3);
            if(boundary.equals("unarchived-edit")) { h.ok(Kind.CLOCK,0);h.reserve(4,Map.of(ROYALTY,"3000"));h.ok(Kind.ACCEPT,4); }
            if(boundary.equals("missing-record"))h.ok(new Command(Kind.DAMAGE,1,null,"remove",Mode.NORMAL,true));
            h.named(Kind.RESTORE,"old");
            if(boundary.equals("authority-down"))h.ok(new Command(Kind.AUTHORITY,0,null,null,Mode.NORMAL,false));
            h.code(boundary.equals("authority-down")?"UNKNOWN":"UNCOVERED",Kind.RECOVER,0);h.code("FENCED",Kind.ACCEPT,1);
            assertTrue(h.store.read().hot().fenced());h.check(ArchiveRecoveryChecker.Verdict.VALID);
        }
    }
    // BEGIN ATLAS SCENARIO
    /**
     * Goal: Ensure Cassandra-backed archive tests detect the same unsafe decisions
     * Boundary: Run nine broken transition variants through the real conditional-write adapter and read back its state
     * Expected: The independent checker rejects every actual observation sequence; external-service facts are still simulated.
     */
    @org.junit.jupiter.api.DisplayName("AT-066 | Ensure Cassandra-backed archive tests detect the same unsafe decisions")
    // END ATLAS SCENARIO
    @Test void unsafeArchiveImplementationsAreRejectedFromRealDatabaseObservations() throws Exception {
        for(Broken broken:List.of(Broken.PRUNE_WITHOUT_COVERAGE,Broken.TRUST_ACK,Broken.TRUST_PARTIAL,Broken.SPLIT_DELETE,
                Broken.RESTORE_ACTIVE,Broken.REWIND_AUTHORITY,Broken.ACCEPT_CLOSED,Broken.IGNORE_CORRUPTION,Broken.OMIT_TAIL))
            try(var h=new Scenario("mutant-"+broken,broken)) {
                h.base();h.seal();
                switch(broken) {
                    case PRUNE_WITHOUT_COVERAGE -> h.run(Kind.PRUNE,3);
                    case TRUST_ACK,TRUST_PARTIAL -> {
                        for(long n=1;n<=3;n++)h.ok(new Command(Kind.COPY,n,null,null,broken==Broken.TRUST_ACK?Mode.VOLATILE:Mode.PARTIAL,true));
                        h.run(Kind.CERTIFY,3);h.run(Kind.PRUNE,3);
                    }
                    case SPLIT_DELETE -> { h.archive();h.run(Kind.PRUNE,3); }
                    case RESTORE_ACTIVE,REWIND_AUTHORITY -> { h.archive();h.ok(Kind.PRUNE,3);h.named(Kind.RESTORE,"old"); }
                    case ACCEPT_CLOSED -> { h.archive();h.ok(Kind.PRUNE,3);h.run(Kind.ACCEPT,1); }
                    case IGNORE_CORRUPTION -> { h.archive();h.ok(Kind.PRUNE,3);h.ok(new Command(Kind.DAMAGE,1,null,"corrupt",Mode.NORMAL,true));h.named(Kind.RESTORE,"old");h.run(Kind.RECOVER,0); }
                    case OMIT_TAIL -> {
                        h.archive();h.ok(Kind.PRUNE,3);h.ok(Kind.CLOCK,0);h.reserve(4,Map.of(ROYALTY,"3000"));h.ok(Kind.ACCEPT,4);
                        h.named(Kind.RESTORE,"old");h.run(Kind.RECOVER,0);
                    }
                    default -> throw new AssertionError(broken);
                }
                h.check(ArchiveRecoveryChecker.Verdict.INVALID);
            }
    }
    // BEGIN ATLAS SCENARIO
    /**
     * Goal: Prove the checker notices a writer that ignores its stale guard
     * Boundary: Deliberately replace an old request's guard with the current guard and write its outdated state to Cassandra
     * Expected: The unsafe write actually applies and the independent checker rejects the resulting history.
     */
    @org.junit.jupiter.api.DisplayName("AT-067 | Prove the checker notices a writer that ignores its stale guard")
    // END ATLAS SCENARIO
    @Test void bypassedWriterGuardIsDetectedAsAResurrectedEdit() throws Exception {
        try(var h=new Scenario("mutant-stale-writer")) {
            h.base();var pending=h.store.prepareAcceptance(3);h.seal();h.archive();h.ok(Kind.PRUNE,3);
            assertTrue(h.store.deliverIgnoringGuard(pending));h.witnesses.add(Map.of("brokenGuardApplied",true));
            h.ok(Kind.VIEW,0);h.check(ArchiveRecoveryChecker.Verdict.INVALID);
        }
    }
}
