package atlas.poc;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import org.junit.jupiter.api.Test;
import static atlas.poc.Retention.*;
import static atlas.poc.Transactions.*;
import static org.junit.jupiter.api.Assertions.*;
class RetentionModelTest extends RetentionContract {
    protected Retention.Store open(UUID subject,RetentionClock clock) { return new Retention.Model(subject,KEY,clock); }
    @Test void compactionFencesAnAcceptanceAlreadyComputedBeforeExpiry() throws Exception {
        var clock=new RetentionClock(); var ready=new CountDownLatch(1); var release=new CountDownLatch(1); var paused=new AtomicBoolean();
        try(var store=new Base(UUID.randomUUID(),KEY,clock) {
            private final AtomicReference<View> state=new AtomicReference<>(initialView());
            public View view() { return state.get(); }
            protected boolean swap(View before,View after) {
                if(!before.snapshot().equals(after.snapshot()) && paused.compareAndSet(false,true)) {
                    ready.countDown();
                    try { if(!release.await(10,TimeUnit.SECONDS)) throw new AssertionError("release timeout"); }
                    catch(InterruptedException e) { throw new AssertionError(e); }
                }
                return state.compareAndSet(before,after);
            }
        };var pool=Executors.newVirtualThreadPerTaskExecutor()) {
            var initial=store.view().snapshot(); Request request=store.edit(Map.of(Group.ROYALTY,"2000")); Ticket ticket=store.issue(request);
            var future=pool.submit(()->assertThrows(Failure.class,()->store.commit(ticket,request)).code);
            try {
                assertTrue(ready.await(10,TimeUnit.SECONDS)); clock.advance(LIFETIME_MILLIS);
                assertEquals(1,store.compact().floor());
            } finally { release.countDown(); }
            assertEquals(Code.REQUEST_TOO_OLD,future.get(10,TimeUnit.SECONDS)); assertEquals(initial,store.view().snapshot());
        }
    }
    @Test void repeatedRetirementKeepsLiveRowsBoundedAndOldFloorClosed() {
        var clock=new RetentionClock();
        try(var store=open(UUID.randomUUID(),clock)) {
            Request original=store.edit(Map.of(Group.ROYALTY,"2000")); Ticket first=store.issue(original); store.commit(first,original);
            for(int cycle=0;cycle<64;cycle++) {
                for(int i=store.view().entries().size();i<4;i++) {
                    Request request=store.edit(Map.of(Group.ROYALTY,Integer.toString(1000+cycle)));
                    store.commit(store.issue(request),request);
                    assertTrue(store.view().entries().size()<=CAPACITY);
                }
                clock.advance(LIFETIME_MILLIS); assertTrue(store.compact().entries().isEmpty());
            }
            assertEquals(256,store.view().floor()); assertEquals(256,store.view().allocated());
            assertEquals(Code.REQUEST_TOO_OLD,assertThrows(Failure.class,()->store.commit(first,original)).code);
        }
    }
}
