package atlas.poc;

import org.junit.jupiter.api.Test;
import java.util.UUID;
import java.util.concurrent.*;
import static atlas.poc.Protocol.*;
import static org.junit.jupiter.api.Assertions.*;

public abstract class Contract {
    protected abstract Store open();
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
