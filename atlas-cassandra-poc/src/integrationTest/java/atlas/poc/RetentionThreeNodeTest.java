package atlas.poc;

import org.junit.jupiter.api.*;
import java.util.*;
import java.nio.file.*;
import java.util.concurrent.TimeUnit;
import com.fasterxml.jackson.databind.ObjectMapper;
import static atlas.poc.Retention.*;
import static atlas.poc.Transactions.*;
import static atlas.poc.TraceAssertions.*;

@Tag("three") @Tag("Retention")
class RetentionThreeNodeTest {
    // BEGIN ATLAS SCENARIO
    /**
     * Goal: Keep retired offer edits closed across a partition
     * Boundary: Attempt cleanup on an isolated DC, prune through the majority, then heal
     * Expected: Retain the younger receipt, prove old-row deletion and reject the retired request after healing.
     */
    @org.junit.jupiter.api.DisplayName("AT-058 | Keep retired offer edits closed across a partition")
    // END ATLAS SCENARIO
    @Test void majorityPrunesAndMinorityCannotReopenExpiredTicketAfterHealing() throws Exception {
        UUID subject=UUID.randomUUID(); var clock=new RetentionClock(); var events=new ArrayList<Map<String,Object>>();
        try(var minority=new RetentionCassandraStore(subject,RetentionContract.KEY,clock,"127.0.0.1",9042,"dc1","three");
            var majority=new RetentionCassandraStore(subject,RetentionContract.KEY,clock,"127.0.0.1",9142,"dc2","three")) {
            Request expired=majority.edit(Map.of(Group.ROYALTY,"2000")); Ticket old=majority.issue(expired); Receipt original=majority.commit(old,expired);
            clock.advance(LIFETIME_MILLIS/2);
            Request fresh=majority.edit(Map.of(Group.ROYALTY,"2500")); Ticket young=majority.issue(fresh); Receipt later=majority.commit(young,fresh);
            clock.advance(LIFETIME_MILLIS/2);
            try {
                command("partition");
                assertEquals(Code.INDETERMINATE,assertThrows(Failure.class,minority::compact).code);
                events.add(Map.of("event","minority-compaction","outcome","INDETERMINATE"));
                // The clock and subject remain fixed throughout exact compaction recovery.
                View pruned=recoverCompaction(majority::compact,events); assertEquals(1,pruned.floor()); assertEquals(later.after(),pruned.snapshot());
                assertEquals(Set.of(young.sequence()),pruned.entries().keySet(),"old row must actually be pruned");
                command("counters");
                assertEquals(later,majority.commit(young,fresh));
                assertEquals(Code.REQUEST_TOO_OLD,assertThrows(Failure.class,()->majority.commit(old,expired)).code);
                events.add(Map.of("event","majority-compaction","floor",pruned.floor(),"retained",pruned.entries().size()));
            } finally { command("heal"); }
            command("ready");
            assertEquals(1,minority.view().floor());
            assertEquals(Set.of(young.sequence()),minority.view().entries().keySet());
            assertEquals(Code.REQUEST_TOO_OLD,assertThrows(Failure.class,()->minority.commit(old,expired)).code);
            assertEquals(later,minority.commit(young,fresh)); assertEquals(later.after(),minority.view().snapshot());
            events.add(Map.of("event","healed-expired-retry","outcome","REQUEST_TOO_OLD","original",original,"current",later));
        } finally {
            Files.createDirectories(Path.of("build/evidence"));
            new ObjectMapper().writerWithDefaultPrettyPrinter().writeValue(Path.of("build/evidence/retention-three.json").toFile(),events);
        }
    }
    static View recoverCompaction(java.util.function.Supplier<View> compact,List<Map<String,Object>> events) {
        Failure last=null;
        for(int attempt=1;attempt<=3;attempt++) {
            try {
                View view=compact.get();
                events.add(Map.of("event","compaction-attempt","attempt",attempt,"outcome","RESOLVED","view",view));
                return view;
            } catch(Failure failure) {
                events.add(Map.of("event","compaction-attempt","attempt",attempt,"outcome",failure.code.name(),
                    "cause",failure.getCause()==null?failure.toString():failure.getCause().toString()));
                if(failure.code!=Code.INDETERMINATE)throw failure;
                last=failure;
            }
        }
        throw new AssertionError("majority compaction must resolve within three attempts",last);
    }
    // BEGIN ATLAS SCENARIO
    /**
     * Goal: Fail cleanup that never produces a definite result
     * Boundary: A test supplier returns uncertainty for all three compaction attempts
     * Expected: The progress gate fails and preserves the unresolved cause.
     */
    @org.junit.jupiter.api.DisplayName("AT-059 | Fail cleanup that never produces a definite result")
    // END ATLAS SCENARIO
    @Test void persistentAmbiguityFailsCompactionProgressGate() {
        var events=new ArrayList<Map<String,Object>>();
        var failure=assertThrows(AssertionError.class,()->recoverCompaction(()->{throw new Failure(Code.INDETERMINATE);},events));
        assertEquals(3,events.size());assertInstanceOf(Failure.class,failure.getCause());
        assertTrue(events.stream().allMatch(e->e.get("outcome").equals("INDETERMINATE")));
    }
    // BEGIN ATLAS SCENARIO
    /**
     * Goal: Recover cleanup without changing which edits are expired
     * Boundary: The model performs cleanup but loses its first reply; retry at the same clock and target
     * Expected: The next attempt observes the same completed cleanup, with unchanged accepted terms.
     */
    @org.junit.jupiter.api.DisplayName("AT-060 | Recover cleanup without changing which edits are expired")
    // END ATLAS SCENARIO
    @Test void lostCompactionReplyRecoversWithoutMovingClockOrChangingTarget() {
        var clock=new RetentionClock();var model=new Retention.Model(UUID.randomUUID(),RetentionContract.KEY,clock);
        Request request=model.edit(Map.of(Group.ROYALTY,"2000"));Ticket ticket=model.issue(request);Receipt receipt=model.commit(ticket,request);
        clock.advance(LIFETIME_MILLIS);
        var events=new ArrayList<Map<String,Object>>();var calls=new java.util.concurrent.atomic.AtomicInteger();
        View recovered=recoverCompaction(()->{
            View result=model.compact();
            if(calls.incrementAndGet()==1)throw new Failure(Code.INDETERMINATE);
            return result;
        },events);
        assertEquals(2,calls.get());assertEquals(1,recovered.floor());assertTrue(recovered.entries().isEmpty());
        assertEquals(receipt.after(),recovered.snapshot());assertEquals(recovered,model.view());
        assertEquals("INDETERMINATE",events.getFirst().get("outcome"));assertEquals("RESOLVED",events.getLast().get("outcome"));
    }
    private void command(String action) throws Exception {
        Process process=new ProcessBuilder("bash","scripts/three.sh",action).inheritIO().start();
        if(!process.waitFor(action.equals("ready")?120:30,TimeUnit.SECONDS)) { process.destroyForcibly(); fail("fault script timeout"); }
        assertEquals(0,process.exitValue());
    }
}
