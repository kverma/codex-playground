package atlas.poc;

import org.junit.jupiter.api.Test;
import java.util.*;
import java.util.concurrent.*;
import static atlas.poc.Transactions.*;
import atlas.poc.Transactions.Error;
import static org.junit.jupiter.api.Assertions.*;

public abstract class TransactionContract {
    protected abstract Store open();
    private Request edit(Snapshot snapshot,Map<Group,String> changes) { return Transactions.edit(UUID.randomUUID(),snapshot,changes); }
    @Test void independentGroupEditRevalidatesWithoutLosingPriorChanges() {
        try(Store store=open()) {
            Snapshot base=store.read();
            Request economics=edit(base,Map.of(Group.ECONOMICS,"1000"));
            Request royalty=edit(base,Map.of(Group.ROYALTY,"2500"));
            Receipt first=store.commit(economics), second=store.commit(royalty);
            assertEquals(first.after(),second.before());
            assertEquals("1000",second.after().value(Group.ECONOMICS));
            assertEquals("2500",store.read().value(Group.ROYALTY));
            assertEquals(first,store.commit(economics));
        }
    }
    @Test void staleValidationDependencyCannotIntroduceWriteSkew() throws Exception {
        try(Store store=open();var pool=Executors.newVirtualThreadPerTaskExecutor()) {
            Snapshot base=store.read();
            Request economics=edit(base,Map.of(Group.ECONOMICS,"600"));
            Request eligibility=edit(base,Map.of(Group.ELIGIBILITY,"NEW,CHURNED"));
            var barrier=new CyclicBarrier(2);
            Callable<Integer> a=()->{ barrier.await(10,TimeUnit.SECONDS); return winner(store,economics); };
            Callable<Integer> b=()->{ barrier.await(10,TimeUnit.SECONDS); return winner(store,eligibility); };
            var fa=pool.submit(a); var fb=pool.submit(b);
            assertEquals(1,fa.get(60,TimeUnit.SECONDS)+fb.get(60,TimeUnit.SECONDS));
            Snapshot current=store.read();
            assertFalse(current.value(Group.ECONOMICS).equals("600")&&current.value(Group.ELIGIBILITY).equals("NEW,CHURNED"));
        }
    }
    private int winner(Store store,Request request) {
        try { store.commit(request); return 1; }
        catch(Rejected e) { assertEquals(Error.CONFLICT,e.error); return 0; }
    }
    @Test void boundedMultiGroupEditCommitsWithExactReceiptOrNothing() {
        try(Store store=open()) {
            Snapshot base=store.read();
            Request invalid=edit(base,Map.of(Group.ECONOMICS,"600",Group.ELIGIBILITY,"NEW,CHURNED"));
            assertEquals(Error.INVALID,assertThrows(Rejected.class,()->store.commit(invalid)).error);
            assertEquals(base,store.read());
            Request valid=edit(base,Map.of(Group.ECONOMICS,"400",Group.ELIGIBILITY,"NEW,CHURNED",Group.ROYALTY,"2000"));
            Receipt receipt=store.commit(valid);
            assertEquals(base,receipt.before()); assertEquals(receipt.after(),store.read());
            assertEquals(receipt,store.commit(valid));
            assertEquals("400",receipt.after().value(Group.ECONOMICS));
            assertEquals("NEW,CHURNED",receipt.after().value(Group.ELIGIBILITY));
            assertEquals("2000",receipt.after().value(Group.ROYALTY));
        }
    }
    @Test void modelEpochFencesStaleEditsButDoesNotInvalidateOriginalReceipts() {
        try(Store store=open()) {
            Snapshot base=store.read();
            Request original=edit(base,Map.of(Group.ROYALTY,"2000"));
            Receipt receipt=store.commit(original);
            Snapshot observed=store.read(); Request stale=edit(observed,Map.of(Group.ROYALTY,"2500"));
            store.commit(admit(UUID.randomUUID(),observed,2,128));
            assertEquals(Error.CONFLICT,assertThrows(Rejected.class,()->store.commit(stale)).error);
            assertEquals(receipt,store.commit(original));
            assertEquals(Error.KEY_REUSE,assertThrows(Rejected.class,()->store.commit(new Request(original.operation(),original.epoch(),original.reads(),Map.of(Group.ROYALTY,"3000"),null,null))).error);
        }
    }
    @Test void sharedByteBudgetIsRevalidatedAcrossIndependentEdits() {
        try(Store store=open()) {
            Snapshot base=store.read();
            store.commit(admit(UUID.randomUUID(),base,2,base.payloadBytes()+1));
            Snapshot admitted=store.read();
            Request first=edit(admitted,Map.of(Group.ECONOMICS,"1000"));
            Request second=edit(admitted,Map.of(Group.ROYALTY,"10000"));
            Receipt accepted=store.commit(first);
            assertEquals(Error.INVALID,assertThrows(Rejected.class,()->store.commit(second)).error);
            assertEquals(accepted.after(),store.read());
            assertEquals(admitted.budget(),store.read().payloadBytes());
        }
    }
    @Test void omittedReadDependencyAndNonCanonicalNumbersAreRejected() {
        try(Store store=open()) {
            Snapshot base=store.read();
            Request missing=new Request(UUID.randomUUID(),base.epoch(),Map.of(Group.ELIGIBILITY,base.cells().get(Group.ELIGIBILITY).version()),Map.of(Group.ELIGIBILITY,"NEW,CHURNED"),null,null);
            assertEquals(Error.INVALID,assertThrows(Rejected.class,()->store.commit(missing)).error);
            assertEquals(Error.INVALID,assertThrows(Rejected.class,()->store.commit(edit(base,Map.of(Group.ECONOMICS,"0500")))).error);
            assertEquals(base,store.read());
        }
    }
    @Test void semanticIdentityExcludesObservationTimeAndConcurrencyTokens() {
        try(Store store=open()) {
            Snapshot march=store.read();
            Receipt same=store.commit(edit(march,Map.of(Group.ROYALTY,march.value(Group.ROYALTY))));
            assertEquals(march.intentId(),same.after().intentId());
            assertNotEquals(march.generation(),same.after().generation());
            Receipt eligibility=store.commit(edit(store.read(),Map.of(Group.ELIGIBILITY,"NEW,CHURNED")));
            assertNotEquals(march.intentId(),eligibility.after().intentId());
            assertEquals(eligibility.after().intentId(),store.read().intentId());
        }
    }
}
