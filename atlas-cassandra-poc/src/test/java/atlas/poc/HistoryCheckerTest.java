package atlas.poc;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import java.nio.file.*;
import java.util.*;
import static atlas.poc.Transactions.*;
import static org.junit.jupiter.api.Assertions.*;

class HistoryCheckerTest {
    // BEGIN ATLAS SCENARIO
    /**
     * Goal: Check that overlapping commercial edits have a coherent history
     * Boundary: Run 400 generated model histories with overlapping edits, reads and lost replies
     * Expected: An independent checker finds a valid order, and separate assertions require successful recovery and progress.
     */
    @org.junit.jupiter.api.DisplayName("AT-014 | Check that overlapping commercial edits have a coherent history")
    // END ATLAS SCENARIO
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
    // BEGIN ATLAS SCENARIO
    /**
     * Goal: Prove the commercial-history checker catches unsafe implementations
     * Boundary: Deliberately lose edits or receipts, accept stale dependencies, apply partial bundles or reuse old version tokens
     * Expected: Reject all six broken variants and replay their reduced counterexamples.
     */
    @org.junit.jupiter.api.DisplayName("AT-015 | Prove the commercial-history checker catches unsafe implementations")
    // END ATLAS SCENARIO
    @Test void rejectsSixBrokenProtocolVariantsAndRetainsReplayableCounterexamples() throws Exception {
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
                case ABA_GENERATION, ABA_GROUP_VERSION -> {
                    recorder.write(edit(new UUID(20,1),initial,Map.of(Group.ROYALTY,"2000")),false);
                    recorder.write(edit(new UUID(20,2),store.read(),Map.of(Group.ROYALTY,"2500")),false);
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
    // BEGIN ATLAS SCENARIO
    /**
     * Goal: Distinguish a legitimate overlapping read from a stale completed read
     * Boundary: Place an old-value read during an edit, then after that edit has completed; also exhaust the checker budget
     * Expected: Allow the overlapping read, reject the later stale read and label an exhausted search inconclusive.
     */
    @org.junit.jupiter.api.DisplayName("AT-016 | Distinguish a legitimate overlapping read from a stale completed read")
    // END ATLAS SCENARIO
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
    // BEGIN ATLAS SCENARIO
    /**
     * Goal: Require Atlas to make progress, not merely avoid bad writes
     * Boundary: A transport reports uncertainty for every edit forever
     * Expected: The workload fails its recovery gate even if a no-change history is logically safe.
     */
    @org.junit.jupiter.api.DisplayName("AT-017 | Require Atlas to make progress, not merely avoid bad writes")
    // END ATLAS SCENARIO
    @Test void historyGraderRejectsAnAlwaysIndeterminateTransport() throws Exception {
        Snapshot initial=initial();
        Store blackHole=new Store() {
            public Snapshot read() { return initial; }
            public Receipt commit(Request request) { throw new Rejected(Transactions.Error.INDETERMINATE); }
        };
        var workload=new HistoryWorkload(blackHole);
        var failure=assertThrows(AssertionError.class,()->workload.round(777,Path.of("build/evidence/negative-controls/black-hole"),()->{}));
        assertTrue(failure.getMessage().contains("recovery"));
        var evidence=new ObjectMapper().readValue(Path.of("build/evidence/negative-controls/black-hole/seed-777.json").toFile(),HistoryChecker.Evidence.class);
        assertEquals(HistoryChecker.Verdict.LINEARIZABLE,evidence.result().verdict(),"safety must stay distinct from progress");
    }
    enum Bug { STALE_DEPENDENCY, LOST_UPDATE, FORGET_RECEIPTS, PARTIAL_BATCH, ABA_GENERATION, ABA_GROUP_VERSION }
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
            if(!receipts.isEmpty() && bug==Bug.ABA_GENERATION)
                next=new Snapshot(initial.generation(),next.epoch(),next.budget(),next.payloadBytes(),next.cells());
            if(!receipts.isEmpty() && bug==Bug.ABA_GROUP_VERSION) {
                var cells=new EnumMap<Group,Cell>(next.cells());
                cells.put(Group.ROYALTY,new Cell(initial.cells().get(Group.ROYALTY).version(),next.value(Group.ROYALTY)));
                next=new Snapshot(next.generation(),next.epoch(),next.budget(),next.payloadBytes(),cells);
            }
            Receipt receipt=new Receipt(request.operation(),request.hash(),state,next);
            receipts.put(request.operation(),receipt); state=next; return receipt;
        }
    }
}
