package atlas.poc;

import org.junit.jupiter.api.Test;
import java.util.*;
import java.util.concurrent.*;
import static atlas.poc.Transactions.*;
import atlas.poc.Transactions.Error;
import static atlas.poc.TraceAssertions.*;

public abstract class TransactionContract {
    protected abstract Store open();
    private Request edit(Snapshot snapshot,Map<Group,String> changes) { return Transactions.edit(UUID.randomUUID(),snapshot,changes); }
    // BEGIN ATLAS SCENARIO
    /**
     * Goal: Allow independent price and royalty edits without losing either
     * Boundary: Prepare both from one version, accept the price edit first, then apply the royalty edit
     * Expected: Both values survive; the second receipt includes the first change.
     */
    @org.junit.jupiter.api.DisplayName("AT-007 | Allow independent price and royalty edits without losing either")
    // END ATLAS SCENARIO
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
    // BEGIN ATLAS SCENARIO
    /**
     * Goal: Keep eligibility and price valid together
     * Boundary: Race a price increase above the fixture limit against extending eligibility to churned customers
     * Expected: Only one can succeed; Atlas never stores the forbidden combination.
     */
    @org.junit.jupiter.api.DisplayName("AT-008 | Keep eligibility and price valid together")
    // END ATLAS SCENARIO
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
    // BEGIN ATLAS SCENARIO
    /**
     * Goal: Apply a commercial amendment as one complete change
     * Boundary: Submit an invalid price/eligibility bundle, then a valid price/eligibility/royalty bundle
     * Expected: Reject the invalid bundle without changes; accept all fields and the receipt together for the valid bundle.
     */
    @org.junit.jupiter.api.DisplayName("AT-009 | Apply a commercial amendment as one complete change")
    // END ATLAS SCENARIO
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
    // BEGIN ATLAS SCENARIO
    /**
     * Goal: Stop old drafts after an authoring-rules change
     * Boundary: Change the admitted rules version while an unsubmitted draft and an older accepted request exist
     * Expected: Reject the stale draft, but still replay the accepted request's exact receipt.
     */
    @org.junit.jupiter.api.DisplayName("AT-010 | Stop old drafts after an authoring-rules change")
    // END ATLAS SCENARIO
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
    // BEGIN ATLAS SCENARIO
    /**
     * Goal: Enforce the offer size limit across independent edits
     * Boundary: Two individually small changes together exceed the shared payload budget
     * Expected: The later edit is rejected without losing the first accepted edit.
     */
    @org.junit.jupiter.api.DisplayName("AT-011 | Enforce the offer size limit across independent edits")
    // END ATLAS SCENARIO
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
    // BEGIN ATLAS SCENARIO
    /**
     * Goal: Reject incomplete or ambiguous commercial requests
     * Boundary: Omit the price dependency from an eligibility edit; submit a price with a leading zero
     * Expected: Reject both requests and leave the offer unchanged.
     */
    @org.junit.jupiter.api.DisplayName("AT-012 | Reject incomplete or ambiguous commercial requests")
    // END ATLAS SCENARIO
    @Test void omittedReadDependencyAndNonCanonicalNumbersAreRejected() {
        try(Store store=open()) {
            Snapshot base=store.read();
            Request missing=new Request(UUID.randomUUID(),base.epoch(),Map.of(Group.ELIGIBILITY,base.cells().get(Group.ELIGIBILITY).version()),Map.of(Group.ELIGIBILITY,"NEW,CHURNED"),null,null);
            assertEquals(Error.INVALID,assertThrows(Rejected.class,()->store.commit(missing)).error);
            assertEquals(Error.INVALID,assertThrows(Rejected.class,()->store.commit(edit(base,Map.of(Group.ECONOMICS,"0500")))).error);
            assertEquals(base,store.read());
        }
    }
    // BEGIN ATLAS SCENARIO
    /**
     * Goal: Separate intent identity from edit concurrency
     * Boundary: Write the same royalty value again, then expand eligibility
     * Expected: The no-op edit gets a fresh concurrency token but the same intent ID; eligibility changes intent identity.
     */
    @org.junit.jupiter.api.DisplayName("AT-013 | Separate intent identity from edit concurrency")
    // END ATLAS SCENARIO
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
