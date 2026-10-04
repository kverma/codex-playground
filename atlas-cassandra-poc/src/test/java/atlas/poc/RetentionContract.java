package atlas.poc;

import org.junit.jupiter.api.Test;
import java.util.*;
import java.util.concurrent.*;
import static atlas.poc.Retention.*;
import atlas.poc.Retention.Store;
import static atlas.poc.Transactions.*;
import static org.junit.jupiter.api.Assertions.*;

public abstract class RetentionContract {
    // Synthetic fixture key only. Production secret provisioning/rotation is out of scope.
    public static final byte[] KEY="atlas-retention-synthetic-key-0001".getBytes(java.nio.charset.StandardCharsets.US_ASCII);
    protected abstract Store open(UUID subject,RetentionClock clock);
    private void old(Runnable action) {
        Failure failure=assertThrows(Failure.class,action::run);
        assertEquals(Code.REQUEST_TOO_OLD,failure.code); assertEquals("UNKNOWN",failure.outcome());
    }
    @Test void exposesAllocatorContentionBetweenIndependentDrafts() {
        var clock=new RetentionClock();
        try(Store store=open(UUID.randomUUID(),clock)) {
            Request economics=store.edit(Map.of(Group.ECONOMICS,"400"));
            Request royalty=store.edit(Map.of(Group.ROYALTY,"2000"));
            assertEquals(economics.operation(),royalty.operation(),"known candidate limitation: drafts choose the same unallocated slot");
            Ticket first=store.issue(economics);
            assertEquals(Transactions.Error.KEY_REUSE,assertThrows(Rejected.class,()->store.issue(royalty)).error);
            store.commit(first,economics);
            Request fresh=store.edit(Map.of(Group.ROYALTY,"2000")); store.commit(store.issue(fresh),fresh);
            assertEquals("400",store.view().snapshot().value(Group.ECONOMICS));
            assertEquals("2000",store.view().snapshot().value(Group.ROYALTY));
        }
    }
    @Test void originalReceiptSurvivesLaterEditsAndExpiryUntilCompacted() {
        var clock=new RetentionClock();
        try(Store store=open(UUID.randomUUID(),clock)) {
            Request first=store.edit(Map.of(Group.ROYALTY,"2000")); Ticket ticket=store.issue(first);
            Receipt receipt=store.commit(ticket,first);
            Request later=store.edit(Map.of(Group.ROYALTY,"2500")); store.commit(store.issue(later),later);
            clock.advance(LIFETIME_MILLIS);
            assertEquals(receipt,store.commit(ticket,first));
            var before=store.view(); var pruned=store.compact();
            assertEquals(2,pruned.floor()); assertTrue(pruned.entries().isEmpty());
            assertEquals(before.snapshot(),pruned.snapshot());
            old(()->store.commit(ticket,first)); old(()->store.issue(first));
            assertEquals(before.snapshot(),store.view().snapshot());
        }
    }
    @Test void exactIssueRetryRecoversTicketWithoutAllocatingAgainAndRejectsKeyReuse() {
        var clock=new RetentionClock();
        try(Store store=open(UUID.randomUUID(),clock)) {
            Request request=store.edit(Map.of(Group.ROYALTY,"2000")); Ticket ticket=store.issue(request);
            assertEquals(ticket,store.issue(request)); assertEquals(1,store.view().allocated());
            Request changed=new Request(request.operation(),request.epoch(),request.reads(),Map.of(Group.ROYALTY,"3000"),null,null);
            assertEquals(Transactions.Error.KEY_REUSE,assertThrows(Rejected.class,()->store.issue(changed)).error);
            assertEquals(Code.INVALID_TICKET,assertThrows(Failure.class,()->store.commit(ticket,changed)).code);
        }
    }
    @Test void neverExecutedExpiredRequestCannotBeReopenedAfterPruning() {
        var clock=new RetentionClock();
        try(Store store=open(UUID.randomUUID(),clock)) {
            var before=store.view().snapshot(); Request request=store.edit(Map.of(Group.ROYALTY,"2000")); Ticket ticket=store.issue(request);
            clock.advance(LIFETIME_MILLIS); old(()->store.commit(ticket,request));
            store.compact(); old(()->store.commit(ticket,request)); old(()->store.issue(request));
            assertEquals(before,store.view().snapshot());
            Request fresh=store.edit(Map.of(Group.ROYALTY,"2000"));
            assertNotEquals(request.operation(),fresh.operation()); store.commit(store.issue(fresh),fresh);
            assertEquals("2000",store.view().snapshot().value(Group.ROYALTY));
        }
    }
    @Test void signedAgeCannotBeChangedOrMovedToAnotherSubject() {
        var clock=new RetentionClock();
        try(Store store=open(UUID.randomUUID(),clock);Store other=open(UUID.randomUUID(),clock)) {
            Request request=store.edit(Map.of(Group.ROYALTY,"2000")); Ticket ticket=store.issue(request);
            Ticket forged=new Ticket(ticket.sequence(),ticket.expiresAt()+LIFETIME_MILLIS,ticket.operation(),ticket.requestHash(),ticket.signature());
            assertEquals(Code.INVALID_TICKET,assertThrows(Failure.class,()->store.commit(forged,request)).code);
            assertEquals(Code.INVALID_TICKET,assertThrows(Failure.class,()->other.commit(ticket,request)).code);
        }
    }
    @Test void capacityIncludesUnresolvedTicketsAndCompactionCannotExpireThemEarly() {
        var clock=new RetentionClock();
        try(Store store=open(UUID.randomUUID(),clock)) {
            for(int i=0;i<CAPACITY;i++) store.issue(store.edit(Map.of(Group.ROYALTY,"2000")));
            var full=store.view(); assertEquals(CAPACITY,full.entries().size());
            assertEquals(Code.CAPACITY,assertThrows(Failure.class,()->store.issue(store.edit(Map.of(Group.ROYALTY,"2000")))).code);
            assertEquals(full,store.compact());
            clock.advance(LIFETIME_MILLIS); assertTrue(store.compact().entries().isEmpty());
            Ticket next=store.issue(store.edit(Map.of(Group.ROYALTY,"2000")));
            assertEquals(CAPACITY+1,next.sequence()); assertEquals(CAPACITY,store.view().floor());
        }
    }
    @Test void expiredPrefixRetiresWithoutDeletingYoungerAcceptedReceipt() {
        var clock=new RetentionClock();
        try(Store store=open(UUID.randomUUID(),clock)) {
            Request first=store.edit(Map.of(Group.ROYALTY,"2000")); Ticket old=store.issue(first);
            clock.advance(LIFETIME_MILLIS/2);
            Request second=store.edit(Map.of(Group.ROYALTY,"2500")); Ticket young=store.issue(second); Receipt receipt=store.commit(young,second);
            clock.advance(LIFETIME_MILLIS/2);
            View compacted=store.compact(); assertEquals(1,compacted.floor()); assertEquals(1,compacted.entries().size());
            old(()->store.commit(old,first)); assertEquals(receipt,store.commit(young,second));
            assertEquals(receipt.after(),compacted.snapshot());
        }
    }
    @Test void backwardClockCannotReverseFloorOrShortenLaterDeadline() {
        var clock=new RetentionClock();
        try(Store store=open(UUID.randomUUID(),clock)) {
            Ticket first=store.issue(store.edit(Map.of(Group.ROYALTY,"2000")));
            clock.advance(-1000); Ticket second=store.issue(store.edit(Map.of(Group.ROYALTY,"2000")));
            assertEquals(first.expiresAt(),second.expiresAt());
            clock.advance(LIFETIME_MILLIS+1000); long floor=store.compact().floor();
            clock.advance(-LIFETIME_MILLIS); assertEquals(floor,store.compact().floor());
        }
    }
    @Test void retryRacingCompactionReturnsOriginalReceiptOrUnknownWithoutNewWrite() throws Exception {
        var clock=new RetentionClock();
        try(Store store=open(UUID.randomUUID(),clock);var pool=Executors.newVirtualThreadPerTaskExecutor()) {
            Request request=store.edit(Map.of(Group.ROYALTY,"2000")); Ticket ticket=store.issue(request); Receipt receipt=store.commit(ticket,request);
            clock.advance(LIFETIME_MILLIS); var barrier=new CyclicBarrier(2);
            var retry=pool.submit(()->{ barrier.await(10,TimeUnit.SECONDS); try { assertEquals(receipt,store.commit(ticket,request)); } catch(Failure e) { assertEquals(Code.REQUEST_TOO_OLD,e.code); } return true; });
            var cleanup=pool.submit(()->{ barrier.await(10,TimeUnit.SECONDS); return store.compact(); });
            assertTrue(retry.get(60,TimeUnit.SECONDS)); assertEquals(1,cleanup.get(60,TimeUnit.SECONDS).floor());
            assertEquals(receipt.after(),store.view().snapshot()); old(()->store.commit(ticket,request));
        }
    }
}
