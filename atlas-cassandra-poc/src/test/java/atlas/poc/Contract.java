package atlas.poc;

import org.junit.jupiter.api.Test;
import java.util.UUID;
import java.util.concurrent.*;
import static atlas.poc.Protocol.*;
import static org.junit.jupiter.api.Assertions.*;

public abstract class Contract {
    protected abstract Store open();
    // BEGIN ATLAS SCENARIO
    /**
     * Goal: Recover the original offer edit after its reply is lost
     * Boundary: Accept an edit, make a later edit, then retry the original request; also reuse its ID with changed terms
     * Expected: Return the original receipt without reverting later terms; reject changed content under the same ID.
     */
    @org.junit.jupiter.api.DisplayName("AT-001 | Recover the original offer edit after its reply is lost")
    // END ATLAS SCENARIO
    @Test void lostResponseRetryAfterLaterEditReturnsOriginalReceipt() {
        try (Store store = open()) {
            Request first = new Request(UUID.randomUUID(), store.read().token(), new Intent(600,false));
            Receipt original = store.commit(first); // simulate loss by withholding this from caller
            store.commit(new Request(UUID.randomUUID(), store.read().token(), new Intent(600,true)));
            assertEquals(original, store.commit(first));
            assertEquals(new Intent(600,true),store.read().intent());
            assertThrows(KeyReuse.class, () -> store.commit(new Request(first.operation(), first.expected(), new Intent(700,false))));
        }
    }
    // BEGIN ATLAS SCENARIO
    /**
     * Goal: Prevent two editors from silently overwriting each other
     * Boundary: Two whole-offer edits start from the same saved version at the same time
     * Expected: Exactly one succeeds; the other receives a conflict.
     */
    @org.junit.jupiter.api.DisplayName("AT-002 | Prevent two editors from silently overwriting each other")
    // END ATLAS SCENARIO
    @Test void twoConcurrentWritersFromSameBaseHaveExactlyOneWinner() throws Exception {
        try (Store store = open(); ExecutorService pool = Executors.newVirtualThreadPerTaskExecutor()) {
            UUID base = store.read().token();
            var ready = new CountDownLatch(2);
            var go = new CountDownLatch(1);
            Callable<Integer> a = () -> attempt(store, new Request(UUID.randomUUID(),base,new Intent(600,false)),ready,go);
            Callable<Integer> b = () -> attempt(store, new Request(UUID.randomUUID(),base,new Intent(500,true)),ready,go);
            var fa = pool.submit(a); var fb = pool.submit(b);
            assertTrue(ready.await(10,TimeUnit.SECONDS)); go.countDown();
            assertEquals(1,fa.get(60,TimeUnit.SECONDS)+fb.get(60,TimeUnit.SECONDS));
            assertNotEquals(base,store.read().token());
        }
    }
    private int attempt(Store store, Request request, CountDownLatch ready, CountDownLatch go) throws Exception {
        ready.countDown(); if (!go.await(10,TimeUnit.SECONDS)) throw new AssertionError("start barrier");
        try { store.commit(request); return 1; } catch (Conflict e) { return 0; }
    }
    // BEGIN ATLAS SCENARIO
    /**
     * Goal: Keep an old draft stale even when the offer returns to its old price
     * Boundary: Change the offer and then restore its earlier commercial configuration
     * Expected: The intent ID matches the old configuration, but the old draft still conflicts.
     */
    @org.junit.jupiter.api.DisplayName("AT-003 | Keep an old draft stale even when the offer returns to its old price")
    // END ATLAS SCENARIO
    @Test void abaIntentDoesNotMakeStaleConcurrencyTokenValid() {
        try (Store store = open()) {
            Head base = store.read();
            store.commit(new Request(UUID.randomUUID(),base.token(),new Intent(600,false)));
            store.commit(new Request(UUID.randomUUID(),store.read().token(),base.intent()));
            assertEquals(base.intent().revision(),store.read().intent().revision());
            assertNotEquals(base.token(),store.read().token());
            assertThrows(Conflict.class, () -> store.commit(new Request(UUID.randomUUID(),base.token(),new Intent(700,false))));
        }
    }
}
