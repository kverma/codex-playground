package atlas.poc;

import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicLong;
import static atlas.poc.Retention.*;
import static atlas.poc.Transactions.*;
import static atlas.poc.HistoryChecker.Verdict.*;
import static org.junit.jupiter.api.Assertions.*;

/** Controlled-clock histories; each clock advance occurs after the preceding calls join and exact recovery completes. */
public final class RetentionWorkload {
    private final Retention.Store store,recovery;
    private final UUID subject;
    private final RetentionClock wall;
    private final AtomicLong logical=new AtomicLong();
    private final List<RetentionChecker.Call> calls=Collections.synchronizedList(new ArrayList<>());
    public HistoryChecker.Result result;
    public RetentionWorkload(UUID subject,Retention.Store store,Retention.Store recovery,RetentionClock wall) {
        this.subject=subject;this.store=store;this.recovery=recovery;this.wall=wall;
    }
    private RetentionChecker.Call call(String kind,Draft draft,Issued input,boolean resolve,CyclicBarrier gate) {
        long start=logical.incrementAndGet(), now=wall.millis();
        if(gate!=null) try { gate.await(15,TimeUnit.SECONDS); } catch(Exception e) { throw new AssertionError("retention dispatch gate",e); }
        Issued issued=input; Receipt receipt=null; View view=null; String error=null;
        Retention.Store target=resolve?recovery:store;
        try {
            switch(kind) {
                case "ISSUE" -> issued=target.issueDraft(draft);
                case "COMMIT" -> receipt=target.commit(input.ticket(),input.request());
                case "VIEW" -> view=target.view();
                case "COMPACT" -> view=target.compact();
                default -> throw new IllegalArgumentException(kind);
            }
        } catch(Failure e) { error=e.code.name(); }
        catch(Rejected e) { error=e.error.name(); }
        var c=new RetentionChecker.Call(start,logical.incrementAndGet(),now,kind,draft,issued,receipt,view,error);calls.add(c);return c;
    }
    public RetentionChecker.Call issue(Draft draft) { return call("ISSUE",draft,null,false,null); }
    public RetentionChecker.Call commit(Issued issued) { return call("COMMIT",null,issued,false,null); }
    public RetentionChecker.Call compact() { return call("COMPACT",null,null,false,null); }
    public RetentionChecker.Call view() { return call("VIEW",null,null,false,null); }
    public List<RetentionChecker.Call> history() { return calls.stream().sorted(Comparator.comparingLong(RetentionChecker.Call::start)).toList(); }
    public RetentionChecker.Evidence evidence(long seed,View initial) {
        result=new RetentionChecker(subject,RetentionContract.KEY).check(initial,history());
        return new RetentionChecker.Evidence(seed,subject,initial,history(),result);
    }
    public void round(long seed,Path directory,Runnable fault) throws Exception {
        View initial=store.view(); Random random=new Random(seed); var drafts=new ArrayList<Draft>();
        for(int i=0;i<3;i++) {
            Map<Group,String> updates=switch(i) {
                case 0 -> Map.of(Group.ECONOMICS,random.nextBoolean()?"400":"600",Group.ELIGIBILITY,"NEW");
                case 1 -> Map.of(Group.ELIGIBILITY,random.nextBoolean()?"NEW":"NEW,CHURNED");
                default -> Map.of(Group.ROYALTY,Integer.toString(1000+random.nextInt(2000)));
            };
            Request template=Transactions.edit(new UUID(seed,i+1),initial.snapshot(),updates);
            if(i==0&&seed%5==0) template=Transactions.admit(template.operation(),initial.snapshot(),initial.snapshot().epoch()+1,seed%7==0?42:128);
            drafts.add(store.prepare(template));
        }
        var issued=new ArrayList<Issued>(); var recoveries=new ArrayList<RetentionChecker.Call>(); View finalView=null;
        try {
            fault.run();
            try(var pool=Executors.newVirtualThreadPerTaskExecutor()) {
                var gate=new CyclicBarrier(4); var futures=new ArrayList<Future<?>>();
                for(Draft d:drafts) futures.add(pool.submit(()->call("ISSUE",d,null,false,gate)));
                futures.add(pool.submit(()->call("VIEW",null,null,false,gate)));
                for(Future<?> f:futures) f.get(90,TimeUnit.SECONDS);
            }
            for(Draft d:drafts) {
                RetentionChecker.Call recovered=null;
                for(int attempt=0;attempt<2;attempt++) {
                    recovered=call("ISSUE",d,null,true,null);
                    if(!"INDETERMINATE".equals(recovered.error())) break;
                }
                assertNotNull(recovered.issued(),"bounded exact draft recovery must return a ticket"); issued.add(recovered.issued());
            }
            assertEquals(3,issued.stream().map(i->i.ticket().sequence()).distinct().count(),"independent leases must get distinct tickets");
            try(var pool=Executors.newVirtualThreadPerTaskExecutor()) {
                var gate=new CyclicBarrier(4); var futures=new ArrayList<Future<?>>();
                for(Issued i:issued) futures.add(pool.submit(()->call("COMMIT",null,i,false,gate)));
                futures.add(pool.submit(()->call("VIEW",null,null,false,gate)));
                for(Future<?> f:futures) f.get(90,TimeUnit.SECONDS);
            }
            for(Issued i:issued) {
                RetentionChecker.Call recovered=null;
                for(int attempt=0;attempt<2;attempt++) {
                    recovered=call("COMMIT",null,i,true,null);
                    if(!"INDETERMINATE".equals(recovered.error())) break;
                }
                recoveries.add(recovered);
            }
            assertTrue(recoveries.stream().noneMatch(c->"INDETERMINATE".equals(c.error())),"bounded exact commit recovery must terminate");
            wall.advance(LIFETIME_MILLIS); compact(); view();
            var closedDraft=call("ISSUE",drafts.getFirst(),null,true,null);
            var closedTicket=call("COMMIT",null,issued.getFirst(),true,null);
            assertEquals("REQUEST_TOO_OLD",closedDraft.error()); assertEquals("REQUEST_TOO_OLD",closedTicket.error());
            View pruned=view().view(); assertNotNull(pruned);assertEquals(3,pruned.floor());assertTrue(pruned.entries().isEmpty());
            Draft fresh=recovery.prepare(Transactions.edit(new UUID(seed,99),pruned.snapshot(),Map.of(Group.ROYALTY,"3000")));
            RetentionChecker.Call allocated=null;
            for(int attempt=0;attempt<2;attempt++) {
                allocated=call("ISSUE",fresh,null,true,null);if(!"INDETERMINATE".equals(allocated.error())) break;
            }
            assertNotNull(allocated.issued());assertEquals(4,allocated.issued().ticket().sequence());
            RetentionChecker.Call accepted=null;
            for(int attempt=0;attempt<2;attempt++) {
                accepted=call("COMMIT",null,allocated.issued(),true,null);if(!"INDETERMINATE".equals(accepted.error())) break;
            }
            assertNotNull(accepted.receipt());finalView=view().view();
        } finally {
            RetentionChecker.save(directory.resolve("seed-"+seed+".json"),evidence(seed,initial));
        }
        assertEquals(LINEARIZABLE,result.verdict(),"retention seed="+seed+", evidence="+directory);
        List<RetentionChecker.Call> h=history(); var first=h.subList(0,4);
        assertEquals(3,first.stream().filter(c->c.kind().equals("ISSUE")).count());
        assertTrue(first.stream().mapToLong(RetentionChecker.Call::start).max().orElseThrow()<first.stream().mapToLong(RetentionChecker.Call::end).min().orElseThrow(),"initial recorded calls overlap");
        var commits=h.stream().filter(c->c.kind().equals("COMMIT")).limit(3).toList();
        assertTrue(commits.stream().mapToLong(RetentionChecker.Call::start).max().orElseThrow()<commits.stream().mapToLong(RetentionChecker.Call::end).min().orElseThrow(),"initial commits overlap");
        assertTrue(h.stream().anyMatch(c->c.receipt()!=null),"safety alone is not progress");
        assertNotNull(finalView,"final authoritative view must succeed");
        assertEquals(3,finalView.floor());assertEquals(4,finalView.allocated());assertEquals(Set.of(4L),finalView.entries().keySet());
    }
}
