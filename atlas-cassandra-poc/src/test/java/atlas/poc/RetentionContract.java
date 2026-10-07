package atlas.poc;

import org.junit.jupiter.api.Test;
import java.util.*;
import java.util.concurrent.*;
import static atlas.poc.Retention.*;
import atlas.poc.Retention.Store;
import static atlas.poc.Transactions.*;
import static atlas.poc.TraceAssertions.*;

public abstract class RetentionContract {
    // Synthetic fixture key only. Production secret provisioning/rotation is out of scope.
    public static final byte[] KEY="atlas-retention-synthetic-key-0001".getBytes(java.nio.charset.StandardCharsets.US_ASCII);
    protected abstract Store open(UUID subject,RetentionClock clock);
    private void old(Runnable action) {
        Failure failure=assertThrows(Failure.class,action::run);
        assertEquals(Code.REQUEST_TOO_OLD,failure.code); assertEquals("UNKNOWN",failure.outcome());
    }
    // BEGIN ATLAS SCENARIO
    /**
     * Goal: Let two independent authors receive distinct edit identities
     * Boundary: Prepare price and royalty drafts from one view, allocate and accept them concurrently
     * Expected: Both receive distinct tickets and preserve their original dependencies; both changes and exact retries survive.
     */
    @org.junit.jupiter.api.DisplayName("AT-019 | Let two independent authors receive distinct edit identities")
    // END ATLAS SCENARIO
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
    // BEGIN ATLAS SCENARIO
    /**
     * Goal: Allocate one edit identity for duplicate submissions
     * Boundary: Submit the same signed draft concurrently, then reuse its identity with different terms
     * Expected: Duplicate submissions share one allocation; changed content is rejected.
     */
    @org.junit.jupiter.api.DisplayName("AT-020 | Allocate one edit identity for duplicate submissions")
    // END ATLAS SCENARIO
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
    // BEGIN ATLAS SCENARIO
    /**
     * Goal: Prevent retired drafts from becoming new edits
     * Boundary: Prune an accepted draft, turn the clock back, alter its deadline or use it for another offer
     * Expected: The old draft remains closed; forged or cross-offer use is rejected; a genuinely new draft gets a new identity.
     */
    @org.junit.jupiter.api.DisplayName("AT-021 | Prevent retired drafts from becoming new edits")
    // END ATLAS SCENARIO
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
    // BEGIN ATLAS SCENARIO
    /**
     * Goal: Close a waiting draft whose allocation anchor was retired
     * Boundary: Prepare a later-expiring draft but do not allocate it before its older anchor is cleaned up
     * Expected: Reject that waiting draft even though its own deadline has not arrived.
     */
    @org.junit.jupiter.api.DisplayName("AT-022 | Close a waiting draft whose allocation anchor was retired")
    // END ATLAS SCENARIO
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
    // BEGIN ATLAS SCENARIO
    /**
     * Goal: Preserve a younger accepted edit while older drafts are cleaned up
     * Boundary: Allocate a younger draft before its earlier anchor is retired
     * Expected: The younger ticket and original receipt still replay correctly.
     */
    @org.junit.jupiter.api.DisplayName("AT-023 | Preserve a younger accepted edit while older drafts are cleaned up")
    // END ATLAS SCENARIO
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
    // BEGIN ATLAS SCENARIO
    /**
     * Goal: Keep the original edit result available during retry retention
     * Boundary: Make later edits, pass the deadline, then remove expired retry records
     * Expected: Before cleanup return the original receipt; after cleanup report the old request as too old without changing current terms.
     */
    @org.junit.jupiter.api.DisplayName("AT-024 | Keep the original edit result available during retry retention")
    // END ATLAS SCENARIO
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
    // BEGIN ATLAS SCENARIO
    /**
     * Goal: Recover the same ticket when allocation is retried
     * Boundary: Retry an allocation, then try to change its commercial content under the same identity
     * Expected: Return the same ticket with no extra allocation; reject identity reuse with changed content.
     */
    @org.junit.jupiter.api.DisplayName("AT-025 | Recover the same ticket when allocation is retried")
    // END ATLAS SCENARIO
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
    // BEGIN ATLAS SCENARIO
    /**
     * Goal: Ensure an expired unsubmitted edit never runs later
     * Boundary: Issue a ticket without accepting its edit, expire and prune it, then retry
     * Expected: The old request never changes the offer; a new request with a new identity may succeed.
     */
    @org.junit.jupiter.api.DisplayName("AT-026 | Ensure an expired unsubmitted edit never runs later")
    // END ATLAS SCENARIO
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
    // BEGIN ATLAS SCENARIO
    /**
     * Goal: Bind each retry permission to its original offer and deadline
     * Boundary: Extend a ticket's deadline without a valid signature or submit it to a different offer
     * Expected: Reject both uses without accepting an edit.
     */
    @org.junit.jupiter.api.DisplayName("AT-027 | Bind each retry permission to its original offer and deadline")
    // END ATLAS SCENARIO
    @Test void signedAgeCannotBeChangedOrMovedToAnotherSubject() {
        var clock=new RetentionClock();
        try(Store store=open(UUID.randomUUID(),clock);Store other=open(UUID.randomUUID(),clock)) {
            Request request=store.edit(Map.of(Group.ROYALTY,"2000")); Ticket ticket=store.issue(request);
            Ticket forged=new Ticket(ticket.sequence(),ticket.expiresAt()+LIFETIME_MILLIS,ticket.operation(),ticket.requestHash(),ticket.signature());
            assertEquals(Code.INVALID_TICKET,assertThrows(Failure.class,()->store.commit(forged,request)).code);
            assertEquals(Code.INVALID_TICKET,assertThrows(Failure.class,()->other.commit(ticket,request)).code);
        }
    }
    // BEGIN ATLAS SCENARIO
    /**
     * Goal: Do not discard unresolved edits just to free capacity
     * Boundary: Fill the retry window with unresolved tickets and try cleanup before their deadlines
     * Expected: Reject further allocation until expiry; early cleanup preserves tickets and later cleanup frees capacity.
     */
    @org.junit.jupiter.api.DisplayName("AT-028 | Do not discard unresolved edits just to free capacity")
    // END ATLAS SCENARIO
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
    // BEGIN ATLAS SCENARIO
    /**
     * Goal: Clean up old retry records without deleting a younger result
     * Boundary: Expire an older ticket while a younger accepted ticket is still valid
     * Expected: Remove only the expired prefix and preserve the younger receipt and current terms.
     */
    @org.junit.jupiter.api.DisplayName("AT-029 | Clean up old retry records without deleting a younger result")
    // END ATLAS SCENARIO
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
    // BEGIN ATLAS SCENARIO
    /**
     * Goal: Keep retirement monotonic when clocks move backward
     * Boundary: Issue tickets around a backward clock jump, then roll time back after cleanup
     * Expected: Later deadlines do not shrink and the retired prefix never reopens.
     */
    @org.junit.jupiter.api.DisplayName("AT-030 | Keep retirement monotonic when clocks move backward")
    // END ATLAS SCENARIO
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
    // BEGIN ATLAS SCENARIO
    /**
     * Goal: Keep retry and cleanup races safe
     * Boundary: Retry an accepted edit at the same time its retained record is removed
     * Expected: Return the original receipt or the explicit too-old/unknown outcome; never execute a new edit.
     */
    @org.junit.jupiter.api.DisplayName("AT-031 | Keep retry and cleanup races safe")
    // END ATLAS SCENARIO
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
