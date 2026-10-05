package atlas.poc;

import org.junit.jupiter.api.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.datastax.oss.driver.api.core.servererrors.UnavailableException;
import com.datastax.oss.driver.api.core.AllNodesFailedException;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;
import static atlas.poc.Retention.*;
import static atlas.poc.Transactions.*;
import static org.junit.jupiter.api.Assertions.*;

@Tag("three") @Tag("Retention")
class ReadinessThreeNodeTest {
    static final class ReadinessFailure extends RuntimeException {
        final int exit;
        ReadinessFailure(int exit) { super("fresh membership readiness failed: exit="+exit);this.exit=exit; }
    }
    // BEGIN ATLAS SCENARIO
    /**
     * Goal: Recover an offer when replicas disappear after readiness passed
     * Boundary: Isolate dc1 after all views are ready; witness two peers Down and an authoritative read requiring two replicas but finding one
     * Expected: Fresh readiness precedes bounded retry; complete offer and receipt state survive and retired edits stay closed.
     */
    @org.junit.jupiter.api.DisplayName("AT-076 | Recover an offer when replicas disappear after readiness passed")
    // END ATLAS SCENARIO
    @Test void membershipLossAfterReadinessRequiresFreshRecovery() throws Exception { boundary(true); }
    // BEGIN ATLAS SCENARIO
    /**
     * Goal: Refuse an authoritative offer while its coordinator stays isolated
     * Boundary: Keep the verified partition active after a witnessed quorum failure; require a fresh readiness check with a five-second polling budget
     * Expected: Readiness fails, no second read occurs and no successful view is invented; cleanup restores the fixture afterward.
     */
    @org.junit.jupiter.api.DisplayName("AT-077 | Refuse an authoritative offer while its coordinator stays isolated")
    // END ATLAS SCENARIO
    @Test void persistentIsolationCannotPassTheReadinessRecoveryGate() throws Exception { boundary(false); }
    private void boundary(boolean recover) throws Exception {
        Path folder=Path.of("build/evidence/readiness-boundary",recover?"recovered":"persistent");Files.createDirectories(folder);
        var events=new ArrayList<Map<String,Object>>();var attempts=new ArrayList<HealedReads.Attempt>();
        var mapper=new ObjectMapper();UUID subject=UUID.randomUUID();var clock=new RetentionClock();boolean partition=false;
        try {
            command("ready",folder,events,false);
            try(var store=new RetentionCassandraStore(subject,RetentionContract.KEY,clock,"127.0.0.1",9042,"dc1","three")) {
                Request old=store.edit(Map.of(Group.ECONOMICS,"400"));Ticket first=store.issue(old);store.commit(first,old);
                clock.advance(LIFETIME_MILLIS/2);
                Request young=store.edit(Map.of(Group.ROYALTY,"2500"));Ticket second=store.issue(young);Receipt accepted=store.commit(second,young);
                clock.advance(LIFETIME_MILLIS/2);View expected=store.compact();
                assertEquals(1,expected.floor());assertEquals(Set.of(2L),expected.entries().keySet());assertEquals(accepted.after(),expected.snapshot());
                events.add(Map.of("event","expected-offer","subject",subject,"view",expected,"receipt",accepted));
                command("ready",folder,events,false); // This successful snapshot must precede the injected change.
                partition=true;command("partition",folder,events,false);command("isolated",folder,events,false);
                var readyCalls=new java.util.concurrent.atomic.AtomicInteger();
                java.util.function.Supplier<View> read=()->{
                    try { return store.view(); }
                    catch(Failure e) {
                        // Refuse generic timeout/connection/setup failures as a quorum-loss witness.
                        if(attempts.isEmpty()) {
                            assertEquals(Code.INDETERMINATE,e.code);
                            Throwable cause=e.getCause();
                            events.add(Map.of("event","read-failure","cause",String.valueOf(cause)));
                            var causes=new ArrayList<Throwable>();
                            if(cause instanceof AllNodesFailedException all) {
                                var errors=all.getAllErrors();assertEquals(1,errors.size(),"only the dc1 coordinator is eligible");
                                for(var entry:errors.entrySet()) {
                                    events.add(Map.of("event","coordinator-errors","dc",String.valueOf(entry.getKey().getDatacenter()),
                                        "errors",entry.getValue().stream().map(Throwable::toString).toList()));
                                    assertEquals("dc1",entry.getKey().getDatacenter());assertFalse(entry.getValue().isEmpty());
                                    causes.addAll(entry.getValue());
                                }
                            } else causes.add(cause);
                            assertFalse(causes.isEmpty());
                            for(Throwable actual:causes) {
                                var unavailable=assertInstanceOf(UnavailableException.class,actual);
                                assertEquals(2,unavailable.getRequired());assertEquals(1,unavailable.getAlive());
                            }
                            events.add(Map.of("event","witnessed-quorum-loss","required",2,"alive",1,"causes",causes.size()));
                        }
                        throw e;
                    }
                };
                HealedReads.Readiness readiness=()->{
                    int n=readyCalls.incrementAndGet();
                    if(recover && n==1)command("heal",folder,events,false);
                    command("ready",folder,events,!recover);
                };
                if(recover) {
                    View observed=HealedReads.read(read,1,readiness,attempts::add);
                    assertTrue(attempts.size()>=2 && attempts.size()<=3);assertEquals(attempts.size()-1,readyCalls.get());
                    assertEquals("INDETERMINATE",attempts.getFirst().error());assertNull(attempts.getFirst().view());
                    assertEquals(expected,observed,"full offer, receipt, generation, floor and allocation must survive");
                    assertEquals(accepted,store.commit(second,young));
                    assertEquals(Code.REQUEST_TOO_OLD,assertThrows(Failure.class,()->store.commit(first,old)).code);
                    assertEquals(expected,store.view(),"recovery and exact retries cannot mutate the offer");
                    events.add(Map.of("event","exact-recovery","view",observed));
                } else {
                    var failed=assertThrows(ReadinessFailure.class,()->HealedReads.read(read,1,readiness,attempts::add));
                    assertEquals(1,failed.exit);assertEquals(1,attempts.size());assertEquals(1,readyCalls.get());
                    assertEquals("INDETERMINATE",attempts.getFirst().error());assertNull(attempts.getFirst().view());
                    command("isolated",folder,events,false);command("counters",folder,events,false);
                    events.add(Map.of("event","persistent-unavailability-rejected","reads",attempts.size(),"readinessCalls",readyCalls.get()));
                }
            }
        } finally {
            try { if(partition) { command("heal",folder,events,false);command("ready",folder,events,false); } }
            finally {
                mapper.writerWithDefaultPrettyPrinter().writeValue(folder.resolve("events.json").toFile(),events);
                mapper.writerWithDefaultPrettyPrinter().writeValue(folder.resolve("reads.json").toFile(),attempts);
            }
        }
    }
    private void command(String action,Path folder,List<Map<String,Object>> events,boolean shortBudget) throws Exception {
        Path output=folder.resolve(String.format("%02d-%s.log",events.size(),action));
        var builder=new ProcessBuilder("bash","scripts/three.sh",action).redirectErrorStream(true).redirectOutput(output.toFile());
        if(shortBudget)builder.environment().put("ATLAS_MEMBERSHIP_TIMEOUT_SECONDS","5");
        long started=System.nanoTime();Process p=builder.start();
        if(!p.waitFor(120,TimeUnit.SECONDS)) { p.destroyForcibly();fail("membership fault command timeout: "+action); }
        events.add(Map.of("event","command","action",action,"exit",p.exitValue(),"shortBudget",shortBudget,
            "elapsedMillis",TimeUnit.NANOSECONDS.toMillis(System.nanoTime()-started),"output",output.toString()));
        if(action.equals("ready") && p.exitValue()!=0) {
            if(shortBudget) {
                String log=Files.readString(output);
                assertTrue(log.contains("Cassandra membership did not recover within the bounded readiness window"),log);
                assertTrue(log.contains("node=dc1 up_normal=1"),log);
            }
            throw new ReadinessFailure(p.exitValue());
        }
        assertEquals(0,p.exitValue(),action+": "+Files.readString(output));
    }
}
