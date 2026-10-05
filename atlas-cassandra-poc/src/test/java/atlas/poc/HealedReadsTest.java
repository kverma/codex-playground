package atlas.poc;
import org.junit.jupiter.api.Test;
import java.util.*;
import static atlas.poc.Retention.*;
import static org.junit.jupiter.api.Assertions.*;
class HealedReadsTest {
    // BEGIN ATLAS SCENARIO
    /**
     * Goal: Refresh readiness before recovering an uncertain offer read
     * Boundary: A shallow supplier fails once; verify read, fresh readiness check, then read ordering
     * Expected: Return the exact original offer and record both attempts.
     */
    @org.junit.jupiter.api.DisplayName("AT-072 | Refresh readiness before recovering an uncertain offer read")
    // END ATLAS SCENARIO
    @Test void uncertainReadRequiresFreshReadinessBeforeExactRecovery() throws Exception {
        try(var model=new Retention.Model(UUID.randomUUID(),RetentionContract.KEY,new RetentionClock())) {
            var expected=model.view();var order=new ArrayList<String>();var evidence=new ArrayList<HealedReads.Attempt>();
            var recovered=HealedReads.read(()->{
                order.add("read");if(order.size()==1)throw new Failure(Code.INDETERMINATE);
                assertEquals(List.of("read","ready","read"),order);return model.view();
            },1,()->order.add("ready"),evidence::add);
            assertEquals(expected,recovered);assertEquals(2,evidence.size());assertEquals("INDETERMINATE",evidence.getFirst().error());
            assertEquals(expected,evidence.getLast().view());
        }
    }
    // BEGIN ATLAS SCENARIO
    /**
     * Goal: Reject recovery that never returns an authoritative offer
     * Boundary: Every read is uncertain even though readiness returns; allow only three reads and two intervening checks
     * Expected: Fail with the original unresolved cause and no invented successful view.
     */
    @org.junit.jupiter.api.DisplayName("AT-073 | Reject recovery that never returns an authoritative offer")
    // END ATLAS SCENARIO
    @Test void permanentlyUncertainReadsExhaustExactlyThreeAttempts() {
        var order=new ArrayList<String>();var evidence=new ArrayList<HealedReads.Attempt>();
        var unresolved=new Failure(Code.INDETERMINATE,new IllegalStateException("quorum unavailable"));
        var failure=assertThrows(AssertionError.class,()->HealedReads.read(()->{
            order.add("read");throw unresolved;
        },1,()->order.add("ready"),evidence::add));
        assertSame(unresolved,failure.getCause());assertEquals(List.of("read","ready","read","ready","read"),order);
        assertEquals(3,evidence.size());assertTrue(evidence.stream().allMatch(a->a.view()==null));
    }
    // BEGIN ATLAS SCENARIO
    /**
     * Goal: Stop recovery when membership cannot become ready
     * Boundary: The first read is uncertain and the next readiness check fails
     * Expected: Propagate the readiness failure without another read.
     */
    @org.junit.jupiter.api.DisplayName("AT-074 | Stop recovery when membership cannot become ready")
    // END ATLAS SCENARIO
    @Test void failedReadinessStopsRecoveryWithoutAnotherRead() {
        var order=new ArrayList<String>();var unavailable=new IllegalStateException("readiness unavailable");
        assertSame(unavailable,assertThrows(IllegalStateException.class,()->HealedReads.read(()->{
            order.add("read");throw new Failure(Code.INDETERMINATE);
        },1,()->{order.add("ready");throw unavailable;},a->{})));
        assertEquals(List.of("read","ready"),order);
    }
    // BEGIN ATLAS SCENARIO
    /**
     * Goal: Keep definite read failures distinct from temporary uncertainty
     * Boundary: A shallow supplier returns a definite invalid-ticket error
     * Expected: Preserve the original error and never invoke readiness or retry.
     */
    @org.junit.jupiter.api.DisplayName("AT-075 | Keep definite read failures distinct from temporary uncertainty")
    // END ATLAS SCENARIO
    @Test void definiteReadFailureIsNeverRetriedAsUncertainty() {
        var evidence=new ArrayList<HealedReads.Attempt>();var rejected=new Failure(Code.INVALID_TICKET);
        assertSame(rejected,assertThrows(Failure.class,()->HealedReads.read(()->{throw rejected;},1,
            ()->fail("definite failures cannot invoke readiness retries"),evidence::add)));
        assertEquals(1,evidence.size());assertEquals("INVALID_TICKET",evidence.getFirst().error());
    }
}
