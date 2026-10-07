package atlas.poc;

import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicLong;
import static atlas.poc.Transactions.*;
import atlas.poc.Transactions.Error;
import static atlas.poc.TraceAssertions.*;

/** Seeds reproduce generated requests; saved invocation/response intervals reproduce the observed schedule. */
public final class HistoryWorkload {
    private final Store store;
    private final AtomicLong clock=new AtomicLong();
    private final List<HistoryChecker.Call> calls=Collections.synchronizedList(new ArrayList<>());
    public HistoryWorkload(Store store) { this.store=store; }
    public Receipt write(Request request,boolean resolve) {
        return writeCall(request,resolve,null).receipt();
    }
    private void dispatch(CyclicBarrier gate) {
        if(gate==null) return;
        try { gate.await(15,TimeUnit.SECONDS); }
        catch(Exception e) { throw new AssertionError("concurrent dispatch gate failed",e); }
    }
    private HistoryChecker.Call writeCall(Request request,boolean resolve,CyclicBarrier gate) {
        long start=clock.incrementAndGet(); dispatch(gate); Receipt receipt=null; Error error=null;
        try { receipt=resolve?store.resolve(request):store.commit(request); }
        catch(Rejected e) { error=e.error; }
        var call=new HistoryChecker.Call(start,clock.incrementAndGet(),"WRITE",request,null,receipt,error);
        calls.add(call); return call;
    }
    public Snapshot read() { return read(null); }
    private Snapshot read(CyclicBarrier gate) {
        long start=clock.incrementAndGet(); dispatch(gate); Snapshot snapshot=null; Error error=null;
        try { snapshot=store.read(); }
        catch(Rejected e) { error=e.error; }
        calls.add(new HistoryChecker.Call(start,clock.incrementAndGet(),"READ",null,snapshot,null,error));
        return snapshot;
    }
    public List<HistoryChecker.Call> calls() { return calls.stream().sorted(Comparator.comparingLong(HistoryChecker.Call::start)).toList(); }
    public void round(long seed,Path evidence,Runnable fault) throws Exception {
        calls.clear(); clock.set(0);
        Snapshot initial=store.read(); Random random=new Random(seed);
        var requests=new ArrayList<Request>();
        for(int i=0;i<3;i++) {
            Map<Group,String> updates=switch(i) {
                case 0 -> Map.of(Group.ECONOMICS,Integer.toString(random.nextInt(2)==0?400:600),Group.ELIGIBILITY,"NEW");
                case 1 -> Map.of(Group.ELIGIBILITY,random.nextBoolean()?"NEW":"NEW,CHURNED");
                default -> Map.of(Group.ROYALTY,Integer.toString(1000+random.nextInt(2000)));
            };
            requests.add(edit(new UUID(seed,i),initial,updates));
        }
        if(seed%5==0) requests.set(0,admit(new UUID(seed,0),initial,initial.epoch()+1,128));
        try(var pool=Executors.newVirtualThreadPerTaskExecutor()) {
            var barrier=new CyclicBarrier(4);
            var futures=new ArrayList<Future<?>>();
            fault.run();
            for(Request request:requests) futures.add(pool.submit(()->{
                writeCall(request,false,barrier); read(); return null;
            }));
            futures.add(pool.submit(()->{ read(barrier); return null; }));
            for(Future<?> future:futures) future.get(90,TimeUnit.SECONDS);
        }
        // Every exact retry is also a recorded operation; indeterminate writes may remain pending until here.
        var recoveries=new ArrayList<HistoryChecker.Call>();
        for(Request request:requests) {
            HistoryChecker.Call recovered=null;
            for(int attempt=0;attempt<3;attempt++) {
                recovered=writeCall(request,true,null);
                if(recovered.error()!=Error.INDETERMINATE) break;
            }
            recoveries.add(recovered);
        }
        Snapshot finalRead=read();
        List<HistoryChecker.Call> history=calls();
        HistoryChecker checker=new HistoryChecker(); var result=checker.check(initial,history);
        HistoryChecker.save(evidence.resolve("seed-"+seed+".json"),new HistoryChecker.Evidence(seed,initial,history,result));
        if(result.verdict()==HistoryChecker.Verdict.NON_LINEARIZABLE) {
            var reduced=checker.shrink(initial,history);
            HistoryChecker.save(evidence.resolve("minimized/seed-"+seed+".json"),new HistoryChecker.Evidence(seed,initial,reduced,checker.check(initial,reduced)));
        }
        assertEquals(HistoryChecker.Verdict.LINEARIZABLE,result.verdict(),"seed="+seed+", evidence="+evidence);
        assertWitnesses(history,recoveries,finalRead);
    }
    private void assertWitnesses(List<HistoryChecker.Call> history,List<HistoryChecker.Call> recoveries,Snapshot finalRead) {
        var first=history.subList(0,4);
        assertEquals(3,first.stream().filter(c->c.kind().equals("WRITE")).count(),"three initial writes must be invoked");
        assertTrue(first.stream().mapToLong(HistoryChecker.Call::start).max().orElseThrow()<first.stream().mapToLong(HistoryChecker.Call::end).min().orElseThrow(),"initial calls must overlap");
        assertTrue(recoveries.stream().noneMatch(c->c.error()==Error.INDETERMINATE),"bounded exact recovery must terminate");
        assertNotNull(finalRead,"final authoritative read must succeed");
        assertTrue(history.stream().anyMatch(c->c.receipt()!=null),"at least one acceptance is required; safety alone is not progress");
    }
}
