package atlas.poc;

import org.junit.jupiter.api.Test;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;
import static atlas.poc.RecoveryJournal.*;
import static atlas.poc.RecoveryJournalTest.*;
import static atlas.poc.DraftProcessClient.save;
import static atlas.poc.TraceAssertions.*;

abstract class RecoveryJournalContract {
    record Attempt(Result result,String error) {}
    private static Attempt attempt(Store store,Request request) throws Exception {
        try { return new Attempt(store.apply(request),null); }
        catch(com.datastax.oss.driver.api.core.servererrors.WriteTimeoutException |
              com.datastax.oss.driver.api.core.servererrors.ReadTimeoutException |
              com.datastax.oss.driver.api.core.DriverTimeoutException uncertain) {
            return new Attempt(null,uncertain.toString());
        }
    }
    private static void stable(Attempt initial,Result recovered) {
        if(initial.result()!=null)assertEquals(initial.result(),recovered);
        else assertNotNull(initial.error());
    }
    private static void preserved(View before,View after,State genesis) {
        if(before.receipts().isEmpty())assertEquals(genesis,before.state());
        else assertEquals(before,after,"resolution must preserve an already durable winner");
    }
    abstract CassandraRecoveryJournal open(UUID subject,String store,boolean peer);
    abstract String folder();
    // BEGIN ATLAS SCENARIO
    /**
     * Goal: Keep concurrent recovery proposals from sharing or overwriting a receipt
     * Boundary: Race different payloads from one state with shared and distinct operation IDs in six rounds; preserve initial outcomes before one exact replay per request
     * Expected: Exact recovery identifies one winner and one key reuse or conflict; definitive initial results and any durable winner remain unchanged, both coordinators agree, and persistent uncertainty fails.
     */
    @org.junit.jupiter.api.DisplayName("AT-102 | Keep concurrent recovery proposals from sharing or overwriting a receipt")
    // END ATLAS SCENARIO
    @Test void competingRecoveryProposalsPreserveOneExactWinner() throws Exception {
        for(boolean sameKey:List.of(false,true))for(int round=0;round<3;round++) {
            UUID subject=UUID.randomUUID();State initial=state("GENESIS");
            try(var a=open(subject,"authority",false);var b=open(subject,"authority",true);var pool=Executors.newVirtualThreadPerTaskExecutor()) {
                a.bootstrap(initial);Request leftRequest=request(initial,"LEFT"),rightRequest=new Request(sameKey?leftRequest.operation():UUID.randomUUID(),initial,state("RIGHT"));
                var ready=new CountDownLatch(2);var release=new CountDownLatch(1);long[] invoked=new long[2],returned=new long[2];
                var left=pool.submit(()->{invoked[0]=System.nanoTime();ready.countDown();assertTrue(release.await(10,TimeUnit.SECONDS));Attempt result=attempt(a,leftRequest);returned[0]=System.nanoTime();return result;});
                var right=pool.submit(()->{invoked[1]=System.nanoTime();ready.countDown();assertTrue(release.await(10,TimeUnit.SECONDS));Attempt result=attempt(b,rightRequest);returned[1]=System.nanoTime();return result;});
                try {assertTrue(ready.await(10,TimeUnit.SECONDS));}finally {release.countDown();}
                Attempt initialLeft=left.get(60,TimeUnit.SECONDS),initialRight=right.get(60,TimeUnit.SECONDS);
                var evidence=new LinkedHashMap<String,Object>();
                evidence.put("sameKey",sameKey);evidence.put("leftRequest",leftRequest);evidence.put("rightRequest",rightRequest);
                evidence.put("initialLeft",initialLeft);evidence.put("initialRight",initialRight);evidence.put("invoked",invoked);evidence.put("returned",returned);
                Path path=Path.of("build/evidence",folder(),"competing-"+sameKey+"-"+round+".json");save(path,evidence);
                View beforeResolution=a.view();evidence.put("beforeResolution",beforeResolution);save(path,evidence);
                // One exact replay per original request. Persistent uncertainty fails this progress gate.
                Result l=b.apply(leftRequest),r=a.apply(rightRequest);stable(initialLeft,l);stable(initialRight,r);
                assertTrue(Math.max(invoked[0],invoked[1])<Math.min(returned[0],returned[1]));
                assertNotEquals(l.code().equals("OK"),r.code().equals("OK"));
                Request winner=l.code().equals("OK")?leftRequest:rightRequest,loser=l.code().equals("OK")?rightRequest:leftRequest;
                Result accepted=l.code().equals("OK")?l:r,rejected=l.code().equals("OK")?r:l;
                assertEquals(sameKey?"KEY_REUSE":"CONFLICT",rejected.code());assertNull(rejected.receipt());
                assertEquals(new Receipt(winner,initial,winner.next()),accepted.receipt());
                View exact=new View(winner.next(),Map.of(winner.operation(),accepted.receipt()));assertEquals(exact,a.view());assertEquals(exact,b.view());
                preserved(beforeResolution,exact,initial);
                evidence.put("left",l);evidence.put("right",r);evidence.put("view",exact);save(path,evidence);
            }
        }
    }
    // BEGIN ATLAS SCENARIO
    /**
     * Goal: Keep journal retries historical across Cassandra sessions
     * Boundary: Apply two guarded transitions, replay the first, alter its request and attempt a stale new operation
     * Expected: Original receipts survive session reopening, historical replay changes nothing and mismatched or stale proposals reject.
     */
    @org.junit.jupiter.api.DisplayName("AT-097 | Keep journal retries historical across Cassandra sessions")
    // END ATLAS SCENARIO
    @Test void recoveryJournalReplaysHistoricalSuccessWithoutRestoringOldState() throws Exception {
        UUID subject=UUID.randomUUID();State initial=state("GENESIS");var events=new ArrayList<Event>();
        try(var a=open(subject,"authority",false);var b=open(subject,"authority",true)) {
            a.bootstrap(initial);Request first=request(initial,"FIRST");Result accepted=apply(a,first,events);
            assertEquals("OK",accepted.code());Request later=request(a.view().state(),"LATER");assertEquals("OK",apply(b,later,events).code());
            View current=a.view();assertEquals(accepted,apply(b,first,events));assertEquals(current,b.view());
            assertEquals("KEY_REUSE",apply(a,new Request(first.operation(),first.expected(),state("CHANGED")),events).code());
            assertEquals("CONFLICT",apply(b,request(initial,"STALE"),events).code());assertEquals(current,a.view());
            assertTrue(RecoveryJournal.check(initial,events).valid());
            save(Path.of("build/evidence",folder(),"replay.json"),new RecoveryJournalTest.Trace(initial,events,RecoveryJournal.check(initial,events)));
        }
        try(var reopened=open(subject,"authority",true)) {
            assertEquals(2,reopened.view().receipts().size(),"receipts survive closing all original sessions");
            assertEquals("LATER",reopened.view().state().value());
        }
    }
    // BEGIN ATLAS SCENARIO
    /**
     * Goal: Deduplicate simultaneous recovery retries
     * Boundary: Release two Cassandra clients with the exact same persisted operation request; preserve initial results or timeout uncertainty before one exact replay per client
     * Expected: Both exact recoveries return one identical receipt, definitive initial results remain unchanged, and the partition contains exactly one effect and receipt; unresolved recovery fails.
     */
    @org.junit.jupiter.api.DisplayName("AT-098 | Deduplicate simultaneous recovery retries")
    // END ATLAS SCENARIO
    @Test void competingIdenticalRecoveryRequestsProduceOneReceipt() throws Exception {
        UUID subject=UUID.randomUUID();State initial=state("GENESIS");
        try(var a=open(subject,"hot",false);var b=open(subject,"hot",true);var pool=Executors.newVirtualThreadPerTaskExecutor()) {
            a.bootstrap(initial);Request request=request(initial,"FROZEN");var ready=new CountDownLatch(2);var release=new CountDownLatch(1);
            long[] invoked=new long[2],returned=new long[2];
            var left=pool.submit(()->{invoked[0]=System.nanoTime();ready.countDown();assertTrue(release.await(10,TimeUnit.SECONDS));Attempt r=attempt(a,request);returned[0]=System.nanoTime();return r;});
            var right=pool.submit(()->{invoked[1]=System.nanoTime();ready.countDown();assertTrue(release.await(10,TimeUnit.SECONDS));Attempt r=attempt(b,request);returned[1]=System.nanoTime();return r;});
            try { assertTrue(ready.await(10,TimeUnit.SECONDS)); } finally { release.countDown(); }
            Attempt initialLeft=left.get(60,TimeUnit.SECONDS),initialRight=right.get(60,TimeUnit.SECONDS);
            var evidence=new LinkedHashMap<String,Object>();evidence.put("request",request);
            evidence.put("initialLeft",initialLeft);evidence.put("initialRight",initialRight);evidence.put("invoked",invoked);evidence.put("returned",returned);
            Path path=Path.of("build/evidence",folder(),"identical-race.json");save(path,evidence);
            View beforeResolution=a.view();evidence.put("beforeResolution",beforeResolution);save(path,evidence);
            Result l=b.apply(request),r=a.apply(request);stable(initialLeft,l);stable(initialRight,r);
            assertEquals("OK",l.code());assertEquals(l,r);assertTrue(Math.max(invoked[0],invoked[1])<Math.min(returned[0],returned[1]));
            View current=a.view();assertEquals(request.next(),current.state());assertEquals(Map.of(request.operation(),l.receipt()),current.receipts());assertEquals(current,b.view());
            preserved(beforeResolution,current,initial);
            evidence.put("left",l);evidence.put("right",r);evidence.put("view",current);save(path,evidence);
        }
    }
}
