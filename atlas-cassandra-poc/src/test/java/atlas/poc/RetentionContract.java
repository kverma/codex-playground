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
    @Test void independentDraftsAllocateAndCommitConcurrentlyWithoutRefreshingReadSets() throws Exception {
        var clock=new RetentionClock();
        try(Store store=open(UUID.randomUUID(),clock);var pool=Executors.newVirtualThreadPerTaskExecutor()) {
            Snapshot observed=store.view().snapshot();
            Draft economics=store.prepare(observed,Map.of(Group.ECONOMICS,"400"));
            Draft royalty=store.prepare(observed,Map.of(Group.ROYALTY,"2000"));
            assertNotEquals(economics.request().operation(),royalty.request().operation());
            assertEquals(economics.anchor(),royalty.anchor());
            var gate=new CyclicBarrier(2);
            var first=pool.submit(()->{gate.await(10,TimeUnit.SECONDS);return store.issueDraft(economics);});
            var second=pool.submit(()->{gate.await(10,TimeUnit.SECONDS);return store.issueDraft(royalty);});
            Issued a=first.get(60,TimeUnit.SECONDS), b=second.get(60,TimeUnit.SECONDS);
            assertNotEquals(a.ticket().sequence(),b.ticket().sequence());
            assertNotEquals(a.request().operation(),b.request().operation());
            assertEquals(economics.request().reads(),a.request().reads());
            assertEquals(royalty.request().reads(),b.request().reads());
            assertEquals(economics.request().hash(),a.request().hash());
            var commits=new CyclicBarrier(2);
            var ca=pool.submit(()->{commits.await(10,TimeUnit.SECONDS);return store.commit(a.ticket(),a.request());});
            var cb=pool.submit(()->{commits.await(10,TimeUnit.SECONDS);return store.commit(b.ticket(),b.request());});
            Receipt ra=ca.get(60,TimeUnit.SECONDS), rb=cb.get(60,TimeUnit.SECONDS);
            assertEquals("400",store.view().snapshot().value(Group.ECONOMICS));
            assertEquals("2000",store.view().snapshot().value(Group.ROYALTY));
            assertEquals(a,store.issueDraft(economics)); assertEquals(b,store.issueDraft(royalty));
            assertEquals(ra,store.commit(a.ticket(),a.request())); assertEquals(rb,store.commit(b.ticket(),b.request()));
        }
    }
    @Test void concurrentExactDraftRetriesAllocateOnceAndChangedPayloadCannotReuseLease() throws Exception {
        var clock=new RetentionClock();
        try(Store store=open(UUID.randomUUID(),clock);var pool=Executors.newVirtualThreadPerTaskExecutor()) {
            Request template=Transactions.edit(UUID.randomUUID(),store.view().snapshot(),Map.of(Group.ROYALTY,"2000"));
            Draft draft=store.prepare(template);
            Draft changed=store.prepare(new Request(template.operation(),template.epoch(),template.reads(),Map.of(Group.ROYALTY,"3000"),null,null));
            assertEquals(draft.anchor(),changed.anchor()); assertEquals(draft.expiresAt(),changed.expiresAt());
            var gate=new CyclicBarrier(2);
            var a=pool.submit(()->{gate.await(10,TimeUnit.SECONDS);return store.issueDraft(draft);});
            var b=pool.submit(()->{gate.await(10,TimeUnit.SECONDS);return store.issueDraft(draft);});
            assertEquals(a.get(60,TimeUnit.SECONDS),b.get(60,TimeUnit.SECONDS));
            assertEquals(1,store.view().allocated()); assertEquals(1,store.view().entries().size());
            assertEquals(Transactions.Error.KEY_REUSE,assertThrows(Rejected.class,()->store.issueDraft(changed)).error);
        }
    }
    @Test void originalDraftCannotReopenAfterPruningAndBackwardClockOrForgedAge() {
        var clock=new RetentionClock();
        try(Store store=open(UUID.randomUUID(),clock);Store other=open(UUID.randomUUID(),clock)) {
            Draft draft=store.prepare(store.view().snapshot(),Map.of(Group.ROYALTY,"2000"));
            Issued issued=store.issueDraft(draft); Receipt receipt=store.commit(issued.ticket(),issued.request());
            assertEquals(Code.INVALID_TICKET,assertThrows(Failure.class,()->other.issueDraft(draft)).code);
            Draft forged=new Draft(draft.request(),draft.anchor(),draft.expiresAt()+LIFETIME_MILLIS,draft.signature());
            assertEquals(Code.INVALID_TICKET,assertThrows(Failure.class,()->store.issueDraft(forged)).code);
            clock.advance(LIFETIME_MILLIS); assertEquals(issued,store.issueDraft(draft));
            assertEquals(receipt,store.commit(issued.ticket(),issued.request()));
            store.compact(); clock.advance(-LIFETIME_MILLIS);
            old(()->store.issueDraft(draft)); old(()->store.commit(issued.ticket(),issued.request()));
            Draft fresh=store.prepare(store.view().snapshot(),Map.of(Group.ROYALTY,"2500"));
            Issued next=store.issueDraft(fresh); assertEquals(2,next.ticket().sequence());
        }
    }
    @Test void anchorRetirementConservativelyClosesAnUnissuedDraft() {
        var clock=new RetentionClock();
        try(Store store=open(UUID.randomUUID(),clock)) {
            Draft first=store.prepare(store.view().snapshot(),Map.of(Group.ROYALTY,"2000"));
            clock.advance(LIFETIME_MILLIS/2);
            Draft waiting=store.prepare(store.view().snapshot(),Map.of(Group.ECONOMICS,"400"));
            assertEquals(first.anchor(),waiting.anchor()); assertTrue(waiting.expiresAt()>first.expiresAt());
            store.issueDraft(first); clock.advance(LIFETIME_MILLIS/2); store.compact();
            assertTrue(clock.millis()<waiting.expiresAt()); old(()->store.issueDraft(waiting));
        }
    }
    @Test void issuedYoungerLeaseReplaysAfterItsAnchorRetires() {
        var clock=new RetentionClock();
        try(Store store=open(UUID.randomUUID(),clock)) {
            Draft first=store.prepare(store.view().snapshot(),Map.of(Group.ECONOMICS,"400"));
            clock.advance(LIFETIME_MILLIS/2);
            Draft younger=store.prepare(store.view().snapshot(),Map.of(Group.ROYALTY,"2000"));
            store.issueDraft(first); Issued issued=store.issueDraft(younger);
            assertEquals(1,younger.anchor()); assertEquals(2,issued.ticket().sequence());
            Receipt receipt=store.commit(issued.ticket(),issued.request());
            clock.advance(LIFETIME_MILLIS/2); assertEquals(1,store.compact().floor());
            assertEquals(issued,store.issueDraft(younger)); assertEquals(receipt,store.commit(issued.ticket(),issued.request()));
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
            for(int i=0;i<CAPACITY;i++) {
                if(i%2==0) store.issue(store.edit(Map.of(Group.ROYALTY,"2000")));
                else store.issueDraft(store.prepare(store.view().snapshot(),Map.of(Group.ROYALTY,"2000")));
            }
            var full=store.view(); assertEquals(CAPACITY,full.entries().size());
            assertEquals(Code.CAPACITY,assertThrows(Failure.class,()->store.issue(store.edit(Map.of(Group.ROYALTY,"2000")))).code);
            assertEquals(Code.CAPACITY,assertThrows(Failure.class,()->store.issueDraft(store.prepare(store.view().snapshot(),Map.of(Group.ROYALTY,"2000")))).code);
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
