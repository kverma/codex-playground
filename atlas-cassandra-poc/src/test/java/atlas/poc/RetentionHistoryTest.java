package atlas.poc;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.atomic.AtomicReference;
import static atlas.poc.Retention.*;
import static atlas.poc.Transactions.*;
import static atlas.poc.HistoryChecker.Verdict.*;
import static org.junit.jupiter.api.Assertions.*;

class RetentionHistoryTest {
    abstract static class Delegating implements Retention.Store {
        final Retention.Store delegate;
        Delegating(Retention.Store delegate) { this.delegate=delegate; }
        public View view() { return delegate.view(); }
        public Request edit(Map<Group,String> updates) { return delegate.edit(updates); }
        public Ticket issue(Request request) { return delegate.issue(request); }
        public Draft prepare(Request request) { return delegate.prepare(request); }
        public Issued issueDraft(Draft draft) { return delegate.issueDraft(draft); }
        public Receipt commit(Ticket ticket,Request request) { return delegate.commit(ticket,request); }
        public View compact() { return delegate.compact(); }
        public void close() { delegate.close(); }
    }
    // BEGIN ATLAS SCENARIO
    /**
     * Goal: Check draft allocation and retry retention under overlap
     * Boundary: Generate 200 model histories, including 100 with lost allocation or acceptance replies
     * Expected: The independent retention checker accepts every history and the workload requires progress.
     */
    @org.junit.jupiter.api.DisplayName("AT-034 | Check draft allocation and retry retention under overlap")
    // END ATLAS SCENARIO
    @Test void twoHundredConcurrentHistoriesIncludeLostAllocationAndAcceptanceReplies() throws Exception {
        for(long seed=1;seed<=200;seed++) {
            UUID subject=UUID.randomUUID(); var clock=new RetentionClock(); var model=new Retention.Model(subject,RetentionContract.KEY,clock);
            var lostIssues=Collections.synchronizedSet(new HashSet<UUID>()); var lostCommits=Collections.synchronizedSet(new HashSet<UUID>());
            final boolean lose=seed%2==1;
            try(var client=new Delegating(model) {
                public Issued issueDraft(Draft draft) {
                    Issued result=super.issueDraft(draft);
                    if(lose&&lostIssues.add(draft.request().operation())) throw new Failure(Code.INDETERMINATE);
                    return result;
                }
                public Receipt commit(Ticket ticket,Request request) {
                    Receipt result=super.commit(ticket,request);
                    if(lose&&lostCommits.add(request.operation())) throw new Failure(Code.INDETERMINATE);
                    return result;
                }
            }) {
                new RetentionWorkload(subject,client,model,clock).round(seed,Path.of("build/evidence/retention-model"),()->{});
            }
        }
    }
    enum Broken { DELETE_WITHOUT_FLOOR, FLOOR_WITHOUT_DELETE, ROLLBACK_FLOOR, RECYCLE_ALLOCATOR, OMIT_RECEIPT, LOST_GROUP_UPDATE, RECYCLE_METADATA }
    static final class BrokenStorage extends Base {
        final Broken broken;
        final AtomicReference<View> state;
        final View original;
        boolean triggered;
        BrokenStorage(UUID subject,RetentionClock clock,Broken broken) {
            super(subject,RetentionContract.KEY,clock);this.broken=broken;original=initialView();state=new AtomicReference<>(original);
        }
        public View view() { return state.get(); }
        protected synchronized boolean swap(View before,View after) {
            if(!state.get().generation().equals(before.generation())) return false;
            View stored=after;
            if(!triggered) {
                boolean prune=after.floor()>before.floor(), accept=!after.snapshot().equals(before.snapshot());
                switch(broken) {
                    case DELETE_WITHOUT_FLOOR -> { if(prune) { stored=new View(after.generation(),after.allocated(),before.floor(),after.lastExpiry(),after.snapshot(),after.entries());triggered=true; } }
                    case FLOOR_WITHOUT_DELETE -> { if(prune) { stored=new View(after.generation(),after.allocated(),after.floor(),after.lastExpiry(),after.snapshot(),before.entries());triggered=true; } }
                    case ROLLBACK_FLOOR -> { if(prune) { stored=new View(after.generation(),before.allocated(),before.floor(),before.lastExpiry(),before.snapshot(),before.entries());triggered=true; } }
                    case RECYCLE_ALLOCATOR -> { if(after.allocated()==2&&before.allocated()==1) { stored=new View(after.generation(),1,after.floor(),after.lastExpiry(),after.snapshot(),after.entries());triggered=true; } }
                    case OMIT_RECEIPT -> { if(accept) {
                        var entries=new HashMap<>(after.entries());
                        entries.replaceAll((n,e)->new Entry(e.expiresAt(),e.operation(),e.requestHash(),null,e.draftId()));
                        stored=new View(after.generation(),after.allocated(),after.floor(),after.lastExpiry(),after.snapshot(),entries);triggered=true;
                    } }
                    case LOST_GROUP_UPDATE -> { if(accept&&after.snapshot().value(Group.ECONOMICS).equals("400")&&after.snapshot().value(Group.ROYALTY).equals("2000")) {
                        var cells=new EnumMap<Group,Cell>(after.snapshot().cells());cells.put(Group.ECONOMICS,original.snapshot().cells().get(Group.ECONOMICS));
                        Snapshot head=new Snapshot(after.snapshot().generation(),after.snapshot().epoch(),after.snapshot().budget(),Transactions.bytes(cells),cells);
                        stored=new View(after.generation(),after.allocated(),after.floor(),after.lastExpiry(),head,after.entries());triggered=true;
                    } }
                    case RECYCLE_METADATA -> { if(accept&&after.snapshot().value(Group.ECONOMICS).equals("400")&&after.snapshot().value(Group.ROYALTY).equals("2000")) { stored=new View(original.generation(),after.allocated(),after.floor(),after.lastExpiry(),after.snapshot(),after.entries());triggered=true; } }
                }
            }
            state.set(stored);return true; // A real broken storage hook, not edited evidence.
        }
    }
    // BEGIN ATLAS SCENARIO
    /**
     * Goal: Prove the retention checker detects broken storage
     * Boundary: Split retirement from deletion, rewind allocation or floors, lose receipts or changes, or reuse metadata versions
     * Expected: Reject all seven broken variants and replay their saved histories.
     */
    @org.junit.jupiter.api.DisplayName("AT-035 | Prove the retention checker detects broken storage")
    // END ATLAS SCENARIO
    @Test void sevenBrokenStorageHistoriesAreRejectedAndReplayed() throws Exception {
        for(Broken broken:Broken.values()) {
            UUID subject=UUID.randomUUID(); var clock=new RetentionClock(); var store=new BrokenStorage(subject,clock,broken); View initial=store.view();
            var workload=new RetentionWorkload(subject,store,store,clock);
            Draft economics=store.prepare(initial.snapshot(),Map.of(Group.ECONOMICS,"400"));
            Draft royalty=store.prepare(initial.snapshot(),Map.of(Group.ROYALTY,"2000"));
            Issued a=workload.issue(economics).issued(), b=workload.issue(royalty).issued();
            assertNotNull(a);assertNotNull(b);
            workload.commit(a);workload.commit(b);workload.view();
            clock.advance(LIFETIME_MILLIS); workload.compact();workload.view();
            assertTrue(store.triggered,"mutation must actually run: "+broken);
            var evidence=workload.evidence(broken.ordinal(),initial);assertEquals(NON_LINEARIZABLE,evidence.result().verdict(),broken.name());
            Path file=Path.of("build/evidence/retention-mutants",broken.name()+".json");RetentionChecker.save(file,evidence);
            var replay=new ObjectMapper().readValue(file.toFile(),RetentionChecker.Evidence.class);
            assertEquals(NON_LINEARIZABLE,new RetentionChecker(replay.subject(),RetentionContract.KEY).check(replay.initial(),replay.calls()).verdict(),"saved mutant replay "+broken);
        }
    }
    // BEGIN ATLAS SCENARIO
    /**
     * Goal: Reject a retry service that never resolves an edit
     * Boundary: Keep returning uncertainty during retention operations
     * Expected: The progress gate fails even when the safety checker can explain a no-effect history.
     */
    @org.junit.jupiter.api.DisplayName("AT-036 | Reject a retry service that never resolves an edit")
    // END ATLAS SCENARIO
    @Test void blackHoleTransportFailsRecoveryEvenWhenSafetyIsLinearizable() throws Exception {
        UUID subject=UUID.randomUUID();var clock=new RetentionClock();var model=new Retention.Model(subject,RetentionContract.KEY,clock);
        var blackHole=new Delegating(model) { public Issued issueDraft(Draft draft) { throw new Failure(Code.INDETERMINATE); } };
        var workload=new RetentionWorkload(subject,blackHole,blackHole,clock);
        assertThrows(AssertionError.class,()->workload.round(901,Path.of("build/evidence/retention-controls/black-hole"),()->{}));
        assertEquals(LINEARIZABLE,workload.result.verdict(),"safety does not certify availability");assertEquals(0,model.view().allocated());
    }
    // BEGIN ATLAS SCENARIO
    /**
     * Goal: Avoid claiming proof outside the retention checker's assumptions
     * Boundary: Exceed its search limits or cross unsupported clock boundaries
     * Expected: Return inconclusive, which cannot satisfy a passing workload gate.
     */
    @org.junit.jupiter.api.DisplayName("AT-037 | Avoid claiming proof outside the retention checker's assumptions")
    // END ATLAS SCENARIO
    @Test void boundsAndClockCrossingsFailAsInconclusive() {
        UUID subject=UUID.randomUUID();var clock=new RetentionClock();var model=new Retention.Model(subject,RetentionContract.KEY,clock); View initial=model.view();
        var a=new RetentionChecker.Call(1,4,clock.millis(),"VIEW",null,null,null,initial,null);
        var b=new RetentionChecker.Call(2,5,clock.millis()+1,"VIEW",null,null,null,initial,null);
        assertEquals(INCONCLUSIVE,new RetentionChecker(subject,RetentionContract.KEY).check(initial,List.of(a,b)).verdict());
        assertEquals(INCONCLUSIVE,new RetentionChecker(subject,RetentionContract.KEY).check(initial,Collections.nCopies(25,a)).verdict());
        assertEquals(INCONCLUSIVE,new RetentionChecker(subject,RetentionContract.KEY,0).check(initial,List.of(a)).verdict());
    }
}
