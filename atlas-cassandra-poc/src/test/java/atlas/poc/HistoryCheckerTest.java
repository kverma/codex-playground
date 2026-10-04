package atlas.poc;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import java.nio.file.*;
import java.util.*;
import static atlas.poc.Transactions.*;
import static org.junit.jupiter.api.Assertions.*;

class HistoryCheckerTest {
    @Test void checksHundredsOfConcurrentModelHistoriesIncludingLostReplies() throws Exception {
        for(int seed=0;seed<20;seed++) {
            Store model=new Model(); Set<UUID> lost=new HashSet<>();
            Store transport=new Store() {
                public Snapshot read() { return model.read(); }
                public synchronized Receipt commit(Request request) {
                    Receipt result=model.commit(request);
                    if(request.operation().getLeastSignificantBits()==0 && lost.add(request.operation())) throw new Rejected(Transactions.Error.INDETERMINATE);
                    return result;
                }
                public Receipt resolve(Request request) { return model.commit(request); }
            };
            var workload=new HistoryWorkload(transport);
            for(int round=0;round<20;round++) workload.round(seed*100L+round,Path.of("build/evidence/history-model"),()->{});
        }
    }
    @Test void rejectsFourBrokenProtocolVariantsAndRetainsReplayableCounterexamples() throws Exception {
        for(Bug bug:Bug.values()) {
            Store store=new Broken(bug); Snapshot initial=store.read(); var recorder=new HistoryWorkload(store);
            Request stale=edit(new UUID(20,0),initial,Map.of(Group.ECONOMICS,"600"));
            switch(bug) {
                case STALE_DEPENDENCY -> {
                    recorder.write(edit(new UUID(20,1),initial,Map.of(Group.ELIGIBILITY,"NEW")),false);
                    recorder.write(stale,false);
                }
                case LOST_UPDATE -> {
                    recorder.write(edit(new UUID(20,1),initial,Map.of(Group.ECONOMICS,"1000")),false);
                    recorder.write(edit(new UUID(20,2),initial,Map.of(Group.ROYALTY,"2500")),false);
                }
                case FORGET_RECEIPTS -> {
                    Request request=edit(new UUID(20,1),initial,Map.of(Group.ROYALTY,"2500"));
                    recorder.write(request,false); recorder.write(request,true);
                }
                case PARTIAL_BATCH -> recorder.write(edit(new UUID(20,1),initial,Map.of(Group.ECONOMICS,"400",Group.ELIGIBILITY,"NEW,CHURNED",Group.ROYALTY,"2500")),false);
            }
            recorder.read();
            var checker=new HistoryChecker(); var result=checker.check(initial,recorder.calls());
            assertEquals(HistoryChecker.Verdict.NON_LINEARIZABLE,result.verdict(),bug.name());
            List<HistoryChecker.Call> minimized=checker.shrink(initial,recorder.calls());
            Path path=Path.of("build/evidence/mutants/"+bug+".json");
            HistoryChecker.save(path,new HistoryChecker.Evidence(bug.ordinal(),initial,minimized,checker.check(initial,minimized)));
            var replay=new ObjectMapper().readValue(path.toFile(),HistoryChecker.Evidence.class);
            assertEquals(HistoryChecker.Verdict.NON_LINEARIZABLE,checker.check(replay.initial(),replay.calls()).verdict());
        }
    }
    @Test void honorsRealTimeAndAllowsOverlappingReadBeforeWrite() {
        Store store=new Model(); Snapshot initial=store.read();
        Request request=edit(new UUID(1,1),initial,Map.of(Group.ROYALTY,"2500")); Receipt receipt=store.commit(request);
        var write=new HistoryChecker.Call(1,5,"WRITE",request,null,receipt,null);
        var overlapping=new HistoryChecker.Call(2,3,"READ",null,initial,null,null);
        var stale=new HistoryChecker.Call(6,7,"READ",null,initial,null,null);
        var checker=new HistoryChecker();
        assertEquals(HistoryChecker.Verdict.LINEARIZABLE,checker.check(initial,List.of(write,overlapping)).verdict());
        assertEquals(HistoryChecker.Verdict.NON_LINEARIZABLE,checker.check(initial,List.of(write,stale)).verdict());
        assertEquals(HistoryChecker.Verdict.INCONCLUSIVE,new HistoryChecker(0).check(initial,List.of(write)).verdict());
    }
    enum Bug { STALE_DEPENDENCY, LOST_UPDATE, FORGET_RECEIPTS, PARTIAL_BATCH }
    /** Intentionally broken implementations; these must never be used by the real adapter. */
    private static final class Broken implements Store {
        final Bug bug; final Snapshot initial=initial(); Snapshot state=initial;
        final Map<UUID,Receipt> receipts=new HashMap<>();
        Broken(Bug bug) { this.bug=bug; }
        public Snapshot read() { return state; }
        public Receipt commit(Request request) {
            Receipt prior=receipts.get(request.operation());
            if(prior!=null && bug!=Bug.FORGET_RECEIPTS) return exact(prior,request);
            Request actual=request;
            if(bug==Bug.STALE_DEPENDENCY) actual=edit(request.operation(),state,request.updates());
            if(bug==Bug.PARTIAL_BATCH && request.updates().size()>1) {
                var partial=new EnumMap<Group,String>(request.updates()); partial.remove(Group.ROYALTY);
                actual=new Request(request.operation(),request.epoch(),request.reads(),partial,null,null);
            }
            Snapshot next=apply(state,actual);
            if(bug==Bug.LOST_UPDATE) {
                var cells=new EnumMap<Group,Cell>(next.cells());
                for(Group group:Group.values()) if(!request.updates().containsKey(group)) cells.put(group,initial.cells().get(group));
                next=new Snapshot(next.generation(),next.epoch(),next.budget(),bytes(cells),cells);
            }
            Receipt receipt=new Receipt(request.operation(),request.hash(),state,next);
            receipts.put(request.operation(),receipt); state=next; return receipt;
        }
    }
}
