package atlas.poc;
import org.junit.jupiter.api.*;
import java.util.*;
import java.util.concurrent.TimeUnit;
import static atlas.poc.ArchiveRecovery.*;
import static atlas.poc.TraceAssertions.*;
@Tag("fault")
class ArchiveCassandraFaultTest {
    // BEGIN ATLAS SCENARIO
    /**
     * Goal: Resume archive-backed cleanup after a real Cassandra process crash
     * Boundary: Keep verified archive facts in the external fixture, kill Cassandra before pruning and reconnect after restart
     * Expected: Accepted terms and slot rows survive; repeated pruning removes the rows safely. Remote archive and power-loss durability remain unproven.
     */
    @org.junit.jupiter.api.DisplayName("AT-068 | Resume archive-backed cleanup after a real Cassandra process crash")
    // END ATLAS SCENARIO
    @Test void serverCrashBetweenArchiveAndPruningPreservesTheOffer() throws Exception {
        var contract=new ArchiveCassandraTest();
        try(var h=contract.new Scenario("server-crash-before-prune")) {
            h.base();var expected=h.store.read().hot().head();h.seal();h.archive();h.store.close();
            try { VerifiedKill.kill(List.of("docker","compose"),"cassandra"); }
            finally {
                Process p=new ProcessBuilder("docker","compose","up","-d","--wait","--wait-timeout","300").inheritIO().start();
                if(!p.waitFor(330,TimeUnit.SECONDS)) { p.destroyForcibly();fail("Cassandra restart timeout"); }
                assertEquals(0,p.exitValue());
            }
            h.store=contract.open(h.subject,h.external,Broken.NONE,false);
            assertEquals(expected,h.store.read().hot().head());assertEquals(Set.of(1L,2L,3L),h.store.read().hot().rows().keySet());
            h.ok(Kind.PRUNE,3);h.ok(Kind.PRUNE,3);assertTrue(h.store.read().hot().rows().isEmpty());
            h.check(ArchiveRecoveryChecker.Verdict.VALID);
        }
    }
    // BEGIN ATLAS SCENARIO
    /**
     * Goal: Resolve uncertain sealing without losing accepted offer terms
     * Boundary: Drop the exact seal batch before forwarding or drop its real server reply; reconnect through a fresh session
     * Expected: Full offer and receipt state matches the witnessed cut; retries converge, archive recovery preserves terms and retired edits stay closed.
     */
    @org.junit.jupiter.api.DisplayName("AT-069 | Resolve uncertain sealing without losing accepted offer terms")
    // END ATLAS SCENARIO
    @Test void sealingSurvivesLostRequestsAndReplies() throws Exception {
        for(var fault:List.of(FrameProxy.Fault.BEFORE_SEND,FrameProxy.Fault.AFTER_RESPONSE))
            wireBoundary(Kind.SEAL,fault,Broken.NONE);
    }
    // BEGIN ATLAS SCENARIO
    /**
     * Goal: Resolve uncertain cleanup without separating the floor from receipt deletion
     * Boundary: Drop the exact prune batch before forwarding or drop its real server reply after complete archive coverage
     * Expected: The floor and all receipt rows change together; exact retries are no-ops and logical restore recovers the accepted offer.
     */
    @org.junit.jupiter.api.DisplayName("AT-070 | Resolve uncertain cleanup without separating the floor from receipt deletion")
    // END ATLAS SCENARIO
    @Test void pruningSurvivesLostRequestsAndReplies() throws Exception {
        for(var fault:List.of(FrameProxy.Fault.BEFORE_SEND,FrameProxy.Fault.AFTER_RESPONSE))
            wireBoundary(Kind.PRUNE,fault,Broken.NONE);
    }
    // BEGIN ATLAS SCENARIO
    /**
     * Goal: Ensure a lost reply cannot hide unsafe partial cleanup
     * Boundary: Run a broken batch that advances the cleanup floor but retains receipt rows, then drop its server reply
     * Expected: The write really applies and the independent checker rejects that exact observed prune boundary.
     */
    @org.junit.jupiter.api.DisplayName("AT-071 | Ensure a lost reply cannot hide unsafe partial cleanup")
    // END ATLAS SCENARIO
    @Test void lostReplyCannotHidePartialPruning() throws Exception {
        wireBoundary(Kind.PRUNE,FrameProxy.Fault.AFTER_RESPONSE,Broken.SPLIT_DELETE);
    }
    private void wireBoundary(Kind kind,FrameProxy.Fault fault,Broken broken) throws Exception {
        var contract=new ArchiveCassandraTest();
        try(var h=contract.new Scenario("wire-"+kind+"-"+fault+"-"+broken,broken)) {
            h.base();
            if(kind==Kind.PRUNE) { h.seal();h.archive(); }
            else h.ok(Kind.CLOCK,10);
            var before=h.store.read();Hot old=before.hot();
            assertEquals(Set.of(1L,2L,3L),old.rows().keySet());
            assertNotNull(old.rows().get(1L).receipt());assertNotNull(old.rows().get(2L).receipt());
            assertNull(old.rows().get(3L).receipt());
            h.check(ArchiveRecoveryChecker.Verdict.VALID);
            h.store.close();
            FrameProxy.Witness witness;
            try(var proxy=new FrameProxy()) {
                h.store=new ArchiveCassandraFixture(h.subject,h.external,"127.0.0.1",proxy.port(),
                    "dc1","single",broken,java.time.Duration.ofSeconds(3));
                assertEquals(before,h.store.read());
                String marker=ArchiveCassandraFixture.marker(h.subject,kind);
                proxy.arm(fault,marker);
                var timeout=assertThrows(com.datastax.oss.driver.api.core.DriverTimeoutException.class,
                    ()->h.store.execute(h.cmd(kind,3)),"only an actual driver timeout satisfies this boundary");
                assertTrue(proxy.injected.await(1,TimeUnit.SECONDS),"the intended frame must be intercepted");
                witness=proxy.witness();
                h.witnesses.add(Map.of("stage","wire","kind",kind,"subject",h.subject,"witness",witness,
                    "matchingRequests",proxy.matchingRequests.get(),"exception",timeout.toString(),"before",before));
                assertEquals(1,proxy.matchingRequests.get(),"no duplicate or bypassed target batch");
                assertTrue(witness.query().contains(marker));assertTrue(witness.query().contains("IF guard=?"));
                assertEquals(fault,witness.fault());
                if(fault==FrameProxy.Fault.BEFORE_SEND) {
                    assertFalse(witness.forwarded());assertEquals(-1,witness.responseOpcode());
                } else {
                    assertTrue(witness.forwarded());assertEquals(8,witness.responseOpcode(),"RESULT, never ERROR");
                    byte[] response=Base64.getDecoder().decode(witness.responseBody());
                    assertEquals(2,java.nio.ByteBuffer.wrap(response).getInt(),"conditional-write ROWS result");
                }
                h.store.close();
            }
            // Discard the timed-out session and read real HEAD plus every actual slot row.
            // Never use the transition model's predicted hot state to resolve ambiguity.
            h.store=contract.open(h.subject,h.external,Broken.NONE,false);
            var observed=h.store.read();
            h.witnesses.add(Map.of("stage","authoritative-read","observed",observed));
            boolean sent=fault==FrameProxy.Fault.AFTER_RESPONSE;
            Hot cleaned=kind==Kind.SEAL
                ?new Hot(old.allocated(),old.floor(),old.head(),old.rows(),Set.of(1L,2L,3L),old.fenced())
                :new Hot(old.allocated(),3,old.head(),Map.of(),Set.of(),old.fenced());
            if(!sent)assertEquals(before,observed,"dropped request changed neither guard nor any row");
            else {
                assertNotEquals(before.guard(),observed.guard(),"the intercepted write must actually apply");
                Hot expected=broken==Broken.SPLIT_DELETE
                    ?new Hot(old.allocated(),3,old.head(),old.rows(),Set.of(),old.fenced()):cleaned;
                assertEquals(expected,observed.hot(),"compare full terms, receipts, floor, seal and fence");
            }
            h.external.hot=observed.hot();
            Mode cut=sent?Mode.AFTER:Mode.BEFORE;
            var frame=new Frame(new Command(kind,3,null,null,cut,true),new Outcome("UNKNOWN",null),
                new Model(h.external).observe());
            int boundary=h.frames.size();h.frames.add(frame);
            var result=new ArchiveRecoveryChecker().check(h.genesis,h.frames);
            if(broken!=Broken.NONE) {
                assertEquals(ArchiveRecoveryChecker.Verdict.INVALID,result.verdict());
                assertEquals(boundary,result.checked(),"reject the actual partial prune, not unrelated setup");
                assertEquals("state mismatch: PRUNE",result.reason());
                h.witnesses.add(Map.of("stage","mutant-rejected","result",result));
                return;
            }
            assertEquals(ArchiveRecoveryChecker.Verdict.VALID,result.verdict());
            // Negative control: neither a generic timeout nor an invented cut is evidence.
            var mislabeled=new ArrayList<>(h.frames);
            mislabeled.set(boundary,new Frame(new Command(kind,3,null,null,sent?Mode.BEFORE:Mode.AFTER,true),
                frame.outcome(),frame.observed()));
            var wrongCut=new ArchiveRecoveryChecker().check(h.genesis,mislabeled);
            assertEquals(ArchiveRecoveryChecker.Verdict.INVALID,wrongCut.verdict());assertEquals(boundary,wrongCut.checked());
            h.witnesses.add(Map.of("stage","wrong-cut-rejected","result",wrongCut));
            h.ok(kind,3);assertEquals(cleaned,h.store.read().hot());
            var recovered=h.store.read();h.ok(kind,3);assertEquals(recovered,h.store.read(),"retry is a complete no-op");
            if(kind==Kind.SEAL) {
                for(long n=1;n<=3;n++)h.ok(Kind.PUBLISH,n);
                h.archive();h.ok(Kind.PRUNE,3);
            }
            assertEquals(old.head(),h.store.read().hot().head());assertTrue(h.store.read().hot().rows().isEmpty());
            h.named(Kind.RESTORE,"old");h.code("FENCED",Kind.ACCEPT,1);h.ok(Kind.RECOVER,0);
            assertEquals(new Hot(3,3,old.head(),Map.of(),Set.of(),false),h.store.read().hot());
            h.ok(Kind.CLOCK,0);h.code("CLOSED",Kind.ACCEPT,1);h.code("CLOSED",Kind.ACCEPT,3);
            h.check(ArchiveRecoveryChecker.Verdict.VALID);
            h.witnesses.add(Map.of("stage","recovered","observed",h.store.read()));
        }
    }

}
