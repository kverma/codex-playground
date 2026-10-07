package org.apache.cassandra.distributed.test;

import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import org.junit.Test;
import org.apache.cassandra.distributed.Cluster;
import org.apache.cassandra.distributed.api.ConsistencyLevel;
import org.apache.cassandra.net.Verb;
import static org.junit.Assert.*;
import static org.apache.cassandra.distributed.api.Feature.NETWORK;
import static org.apache.cassandra.distributed.api.Feature.GOSSIP;

/** Atlas-authored overlay on the pinned 4.0.5 harness, not an upstream test. */
public class AtlasBatchPhaseTest extends TestBaseImpl {
    private static final Verb[] PHASES={Verb.PAXOS_PREPARE_REQ,Verb.PAXOS_PREPARE_RSP,
        Verb.PAXOS_PROPOSE_REQ,Verb.PAXOS_PROPOSE_RSP,Verb.PAXOS_COMMIT_REQ,Verb.PAXOS_COMMIT_RSP};
    private static final class Batch {
        final String select, mutation; final Object[][] before, after;
        Batch(String select,String mutation,Object[][] before,Object[][] after) {
            this.select=select;this.mutation=mutation;this.before=before;this.after=after;
        }
    }
    private Batch setup(Cluster cluster,boolean prune,int index) {
        UUID subject=new UUID(11,index),old=new UUID(12,index),next=new UUID(13,index),operation=new UUID(14,index);
        String table=KEYSPACE+(prune?".atlas_archive":".atlas_subject");
        String select="SELECT row,"+(prune?"guard,payload":"commit_token,request_hash,cents,churned")+" FROM "+table+" WHERE subject="+subject;
        if(prune) {
            cluster.coordinator(1).execute("INSERT INTO "+table+"(subject,row,guard,payload) VALUES (?, 'HEAD', ?, ?)",ConsistencyLevel.ALL,subject,old,"floor=0;sealed=1;head=receipt-1");
            cluster.coordinator(1).execute("INSERT INTO "+table+"(subject,row,payload) VALUES (?, 'S:1', ?)",ConsistencyLevel.ALL,subject,"receipt-1");
            String mutation="BEGIN BATCH UPDATE "+table+" SET guard="+next+",payload='floor=1;sealed=1;head=receipt-1' WHERE subject="+subject+" AND row='HEAD' IF guard="+old+"; DELETE FROM "+table+" WHERE subject="+subject+" AND row='S:1'; APPLY BATCH";
            return new Batch(select,mutation,new Object[][]{{"HEAD",old,"floor=0;sealed=1;head=receipt-1"},{"S:1",null,"receipt-1"}},
                new Object[][]{{"HEAD",next,"floor=1;sealed=1;head=receipt-1"}});
        }
        cluster.coordinator(1).execute("INSERT INTO "+table+"(subject,row,commit_token,cents,churned) VALUES (?, 'HEAD', ?, 500, false)",ConsistencyLevel.ALL,subject,old);
        String mutation="BEGIN BATCH UPDATE "+table+" SET commit_token="+next+",cents=400,churned=true WHERE subject="+subject+" AND row='HEAD' IF commit_token="+old+"; INSERT INTO "+table+"(subject,row,commit_token,request_hash,cents,churned) VALUES ("+subject+",'OP:"+operation+"',"+next+",'exact-request-hash',400,true) IF NOT EXISTS; APPLY BATCH";
        return new Batch(select,mutation,new Object[][]{{"HEAD",old,null,500,false}},
            new Object[][]{{"HEAD",next,null,400,true},{"OP:"+operation,next,"exact-request-hash",400,true}});
    }
    private Cluster cluster() throws Exception {
        Cluster c=init(Cluster.create(3,config->config.set("write_request_timeout_in_ms",2000L).set("cas_contention_timeout_in_ms",2000L).set("hinted_handoff_enabled",false)));
        c.schemaChange("CREATE TABLE "+KEYSPACE+".atlas_subject (subject uuid,row text,commit_token uuid,request_hash text,cents int,churned boolean,PRIMARY KEY(subject,row))");
        c.schemaChange("CREATE TABLE "+KEYSPACE+".atlas_archive (subject uuid,row text,guard uuid,payload text,PRIMARY KEY(subject,row))");
        return c;
    }
    private boolean whole(Batch batch,Object[][] rows) { return Arrays.deepEquals(rows,batch.before)||Arrays.deepEquals(rows,batch.after); }
    private void phases(boolean prune) throws Throwable {
        try(Cluster c=cluster()) {
            for(int index=0;index<PHASES.length;index++) {
                Batch batch=setup(c,prune,index+1);Verb phase=PHASES[index];boolean response=phase.name().endsWith("RSP");
                System.out.println("ATLAS_SAMPLE before="+Arrays.deepToString(batch.before)+" DML="+batch.mutation+" expected="+Arrays.deepToString(batch.after));
                AtomicInteger hits=new AtomicInteger();
                var builder=c.filters().verbs(phase.id);
                if(response)builder.from(2,3).to(1);else builder.from(1).to(2,3);
                var filter=builder.messagesMatching((from,to,message)->{hits.incrementAndGet();return true;}).drop();
                String failure=null;
                try { c.coordinator(1).execute(batch.mutation,ConsistencyLevel.QUORUM);fail("phase cut must make the result uncertain"); }
                catch(RuntimeException e) {
                    assertEquals("org.apache.cassandra.exceptions.CasWriteTimeoutException",e.getClass().getName());
                    failure=e.getClass().getName();
                } finally {filter.off();}
                assertTrue("selected phase must actually be intercepted",hits.get()>0);
                Object[][] resolved=c.coordinator(2).execute(batch.select,ConsistencyLevel.SERIAL);
                assertTrue("no partial HEAD/receipt or floor/delete state: "+Arrays.deepToString(resolved),whole(batch,resolved));
                for(int peer=1;peer<=3;peer++)assertTrue(Arrays.deepEquals(resolved,c.coordinator(peer).execute(batch.select,ConsistencyLevel.SERIAL)));
                Object[][] retried=c.coordinator(3).execute(batch.mutation,ConsistencyLevel.QUORUM);
                System.out.println("ATLAS_RETRY actual="+Arrays.deepToString(retried));
                assertEquals(Arrays.deepEquals(resolved,batch.before),retried[0][0]);
                for(int peer=1;peer<=3;peer++)assertTrue(Arrays.deepEquals(batch.after,c.coordinator(peer).execute(batch.select,ConsistencyLevel.SERIAL)));
                System.out.println("ATLAS_PHASE kind="+(prune?"floor-delete":"head-receipt")+" phase="+phase+" hits="+hits.get()+" error="+failure+" resolved="+Arrays.deepToString(resolved)+" final="+Arrays.deepToString(batch.after));
                c.filters().reset();
            }
        }
    }
    @Test public void headReceiptAtEveryPaxosMessageBoundary() throws Throwable {phases(false);}
    @Test public void floorDeleteAtEveryPaxosMessageBoundary() throws Throwable {phases(true);}
    @Test public void atomicityOracleRejectsAnActualSplitPrune() throws Throwable {
        try(Cluster c=cluster()) {
            Batch batch=setup(c,true,99);
            // Deliberately execute only the HEAD effect from the real batch; the row deletion is absent.
            String update=batch.mutation.substring("BEGIN BATCH ".length(),batch.mutation.indexOf("; DELETE FROM"));
            assertEquals(true,c.coordinator(1).execute(update,ConsistencyLevel.QUORUM)[0][0]);
            Object[][] partial=c.coordinator(2).execute(batch.select,ConsistencyLevel.SERIAL);
            assertFalse("the actual partial state must be rejected",whole(batch,partial));
            assertEquals(2,partial.length);assertEquals(batch.after[0][2],partial[0][2]);assertEquals("S:1",partial[1][0]);
            System.out.println("ATLAS_SPLIT_PRUNE rejected="+Arrays.deepToString(partial));
        }
    }
    @Test public void editCompletesInsideWitnessedRepairValidationPause() throws Throwable {
        try(Cluster c=init(Cluster.build().withNodes(3).withConfig(config->config.with(NETWORK).with(GOSSIP)
            .set("hinted_handoff_enabled",false)).start())) {
            String table=KEYSPACE+".atlas_subject";
            c.schemaChange("CREATE TABLE "+table+" (subject uuid,row text,commit_token uuid,request_hash text,cents int,churned boolean,PRIMARY KEY(subject,row)) WITH read_repair='NONE'");
            Batch edit=setup(c,false,120);
            UUID divergent=new UUID(21,1),token=new UUID(22,1);
            String insert="INSERT INTO "+table+"(subject,row,commit_token,cents,churned) VALUES (?, 'HEAD', ?, 123, false)";
            c.get(1).executeInternal(insert,divergent,token);c.get(2).executeInternal(insert,divergent,token);
            String local="SELECT row,commit_token,request_hash,cents,churned FROM "+table+" WHERE subject=?";
            assertEquals(0,c.get(3).executeInternal(local,divergent).length);
            for(int peer=1;peer<=3;peer++)c.get(peer).flush(KEYSPACE);
            var entered=new CountDownLatch(1);var release=new CountDownLatch(1);var once=new AtomicBoolean();
            var pauseStart=new AtomicLong();var pauseEnd=new AtomicLong();var timedOut=new AtomicBoolean();
            var filter=c.filters().verbs(Verb.VALIDATION_RSP.id).from(2).to(1).messagesMatching((from,to,message)->{
                if(once.compareAndSet(false,true)) {
                    pauseStart.set(System.nanoTime());entered.countDown();
                    try {if(!release.await(45,TimeUnit.SECONDS))timedOut.set(true);}
                    catch(InterruptedException e) {Thread.currentThread().interrupt();timedOut.set(true);}
                    finally {pauseEnd.set(System.nanoTime());}
                }
                return false; // pause and deliver, never silently drop the repair result
            }).drop();
            var pool=Executors.newSingleThreadExecutor();
            try {
                var repair=pool.submit(()->DistributedRepairUtils.repair(c,DistributedRepairUtils.RepairType.FULL,true,KEYSPACE,"atlas_subject"));
                long writeStart,writeEnd;
                try {
                    assertTrue("repair must reach the selected validation response",entered.await(60,TimeUnit.SECONDS));
                    assertFalse("repair is still in flight",repair.isDone());
                    writeStart=System.nanoTime();assertEquals(true,c.coordinator(3).execute(edit.mutation,ConsistencyLevel.QUORUM)[0][0]);writeEnd=System.nanoTime();
                    assertEquals(0,pauseEnd.get());assertTrue(pauseStart.get()<writeStart);
                } finally {release.countDown();}
                repair.get(120,TimeUnit.SECONDS).asserts().success();
                assertFalse(timedOut.get());assertTrue(writeEnd<pauseEnd.get());
                DistributedRepairUtils.assertParentRepairSuccess(c,KEYSPACE,"atlas_subject");
                // Local reads first: no quorum read or read repair can explain healing this sentinel.
                for(int peer=1;peer<=3;peer++)assertTrue(Arrays.deepEquals(new Object[][]{{"HEAD",token,null,123,false}},c.get(peer).executeInternal(local,divergent)));
                for(int peer=1;peer<=3;peer++)assertTrue(Arrays.deepEquals(edit.after,c.coordinator(peer).execute(edit.select,ConsistencyLevel.SERIAL)));
                System.out.println("ATLAS_REPAIR_PAUSE verb=VALIDATION_RSP from=2 to=1 pauseStart="+pauseStart.get()+" writeStart="+writeStart+" writeEnd="+writeEnd+" pauseEnd="+pauseEnd.get()+" hints=false readRepair=NONE localSentinelReplicas=3 exactEditReplicas=3");
            } finally {release.countDown();filter.off();pool.shutdownNow();pool.awaitTermination(10,TimeUnit.SECONDS);}
        }
    }
}
