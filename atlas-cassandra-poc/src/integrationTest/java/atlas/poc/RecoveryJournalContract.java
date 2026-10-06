package atlas.poc;

import org.junit.jupiter.api.Test;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;
import static atlas.poc.RecoveryJournal.*;
import static atlas.poc.RecoveryJournalTest.*;
import static atlas.poc.DraftProcessClient.save;
import static org.junit.jupiter.api.Assertions.*;

abstract class RecoveryJournalContract {
    abstract CassandraRecoveryJournal open(UUID subject,String store,boolean peer);
    abstract String folder();
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
     * Boundary: Release two Cassandra clients with the exact same persisted operation request
     * Expected: Both overlapping calls return one identical receipt and the partition contains exactly one effect and receipt.
     */
    @org.junit.jupiter.api.DisplayName("AT-098 | Deduplicate simultaneous recovery retries")
    // END ATLAS SCENARIO
    @Test void competingIdenticalRecoveryRequestsProduceOneReceipt() throws Exception {
        UUID subject=UUID.randomUUID();State initial=state("GENESIS");
        try(var a=open(subject,"hot",false);var b=open(subject,"hot",true);var pool=Executors.newVirtualThreadPerTaskExecutor()) {
            a.bootstrap(initial);Request request=request(initial,"FROZEN");var ready=new CountDownLatch(2);var release=new CountDownLatch(1);
            long[] invoked=new long[2],returned=new long[2];
            var left=pool.submit(()->{invoked[0]=System.nanoTime();ready.countDown();assertTrue(release.await(10,TimeUnit.SECONDS));Result r=a.apply(request);returned[0]=System.nanoTime();return r;});
            var right=pool.submit(()->{invoked[1]=System.nanoTime();ready.countDown();assertTrue(release.await(10,TimeUnit.SECONDS));Result r=b.apply(request);returned[1]=System.nanoTime();return r;});
            try { assertTrue(ready.await(10,TimeUnit.SECONDS)); } finally { release.countDown(); }
            Result l=left.get(60,TimeUnit.SECONDS),r=right.get(60,TimeUnit.SECONDS);
            assertEquals("OK",l.code());assertEquals(l,r);assertTrue(Math.max(invoked[0],invoked[1])<Math.min(returned[0],returned[1]));
            View current=a.view();assertEquals(request.next(),current.state());assertEquals(Map.of(request.operation(),l.receipt()),current.receipts());assertEquals(current,b.view());
            save(Path.of("build/evidence",folder(),"identical-race.json"),Map.of("request",request,"left",l,"right",r,"invoked",invoked,"returned",returned,"view",current));
        }
    }
}
