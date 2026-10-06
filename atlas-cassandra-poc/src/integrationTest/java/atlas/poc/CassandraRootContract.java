package atlas.poc;

import org.junit.jupiter.api.Test;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;
import static atlas.poc.DraftProcessClient.save;
import static org.junit.jupiter.api.Assertions.*;

abstract class CassandraRootContract {
    abstract CassandraRootAuthority open(UUID subject,boolean peer);
    abstract String folder();
    record PublicationCall(int writer,long invoked,long returned,boolean applied,String error) {}
    // BEGIN ATLAS SCENARIO
    /**
     * Goal: Choose exactly one recovery pointer when two Atlas writers compete
     * Boundary: Release two clients from the same observed root in six rounds, including identical payloads with different guards, on one node and through different DCs
     * Expected: Exactly one conditional publication applies; full root and guard match the winner, both stale replays fail, and equal content never identifies the wrong proposal.
     */
    @org.junit.jupiter.api.DisplayName("AT-089 | Choose exactly one recovery pointer when two Atlas writers compete")
    // END ATLAS SCENARIO
    @Test void competingRootPublicationsHaveExactlyOneWinner() throws Exception {
        UUID subject=UUID.randomUUID();
        var evidence=new ArrayList<Map<String,Object>>();
        try(var a=open(subject,false);var b=open(subject,true)) {
            a.bootstrap(new SplitArchiveCheckpoint.Root(1,subject,"a".repeat(64),"b".repeat(64)));
            for(int round=0;round<6;round++) {
                var base=a.read();assertEquals(base,b.read());
                var left=new SplitArchiveCheckpoint.Root(1,subject,("%064x".formatted(100+round)),("%064x".formatted(200+round)));
                // Equal payloads are intentionally distinct publications with different guards.
                var right=round%2==0?left:new SplitArchiveCheckpoint.Root(1,subject,"c".repeat(64),"d".repeat(64));
                var proposals=List.of(a.propose(base,left),b.propose(base,right));
                assertNotEquals(proposals.get(0).next().guard(),proposals.get(1).next().guard());
                var calls=new PublicationCall[2];var ready=new CountDownLatch(2);var release=new CountDownLatch(1);
                try(var pool=Executors.newVirtualThreadPerTaskExecutor()) {
                    var futures=new ArrayList<Future<?>>();
                    for(int writer=0;writer<2;writer++) {
                        final int id=writer;var authority=id==0?a:b;
                        futures.add(pool.submit(()->{
                            long invoked=System.nanoTime();ready.countDown();
                            try {
                                if(!release.await(10,TimeUnit.SECONDS))throw new IllegalStateException("dispatch gate timed out");
                                boolean applied=authority.publish(proposals.get(id));
                                calls[id]=new PublicationCall(id,invoked,System.nanoTime(),applied,"");
                            } catch(Exception e) {
                                calls[id]=new PublicationCall(id,invoked,System.nanoTime(),false,e.toString());
                                throw new RuntimeException(e);
                            }
                        }));
                    }
                    try { assertTrue(ready.await(10,TimeUnit.SECONDS),"both invocations must precede dispatch"); }
                    finally { release.countDown(); }
                    for(var future:futures)future.get(60,TimeUnit.SECONDS);
                } finally {
                    evidence.add(Map.of("round",round,"base",base,"proposals",proposals,"calls",Arrays.asList(calls)));
                }
                assertTrue(Math.max(calls[0].invoked(),calls[1].invoked())<Math.min(calls[0].returned(),calls[1].returned()),"recorded calls overlap");
                assertNotEquals(calls[0].applied(),calls[1].applied(),"exactly one proposal must apply");
                int winner=calls[0].applied()?0:1;var accepted=proposals.get(winner);var rejected=proposals.get(1-winner);
                assertEquals(accepted.next(),a.read());assertEquals(accepted.next(),b.read());
                assertEquals(CassandraRootAuthority.Resolution.PUBLISHED,b.resolve(accepted));
                assertEquals(CassandraRootAuthority.Resolution.UNKNOWN,a.resolve(rejected),"payload equality cannot identify the winner");
                assertFalse(a.publish(accepted),"exact replay must not apply twice");
                assertFalse(b.publish(rejected),"loser must not silently refresh its expected guard");
                assertEquals(accepted.next(),a.read());assertEquals(accepted.next(),b.read());
                evidence.add(Map.of("round",round,"winner",winner,"afterReplays",a.read(),"loserResolution",a.resolve(rejected)));
            }
        } finally { save(Path.of("build/evidence",folder(),"root-contention.json"),evidence); }
    }
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
