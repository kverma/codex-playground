package atlas.poc;

import org.junit.jupiter.api.Test;
import java.util.*;
import static atlas.poc.Protocol.*;
import static atlas.poc.TraceAssertions.*;

class ModelTest extends Contract {
    protected Store open() { return new Model(); }
    // BEGIN ATLAS SCENARIO
    /**
     * Goal: Track changes in commercial intent rather than calendar time
     * Boundary: Compare unchanged terms observed later with changed price or eligibility
     * Expected: Unchanged terms keep the same intent ID; changed terms produce a different ID.
     */
    @org.junit.jupiter.api.DisplayName("AT-004 | Track changes in commercial intent rather than calendar time")
    // END ATLAS SCENARIO
    @Test void revisionCapturesIntentAndExcludesObservationTime() {
        Intent march = new Intent(500,false);
        assertEquals(march.revision(), new Intent(500,false).revision()); // same configuration observed in April
        assertNotEquals(march.revision(),new Intent(500,true).revision()); // eligibility broadened
        assertNotEquals(march.revision(),new Intent(600,false).revision());
    }
    // BEGIN ATLAS SCENARIO
    /**
     * Goal: Keep retry results stable through many later offer edits
     * Boundary: Generate 100 sequences of 100 edits and repeatedly retry earlier requests
     * Expected: Every retry returns its own original receipt, not the latest offer state.
     */
    @org.junit.jupiter.api.DisplayName("AT-005 | Keep retry results stable through many later offer edits")
    // END ATLAS SCENARIO
    @Test void seededRetryHistoriesPreserveEveryOriginalResult() {
        for (int seed=0;seed<100;seed++) {
            Random rng = new Random(seed);
            try (Store store = open()) {
                List<Request> requests = new ArrayList<>(); List<Receipt> results = new ArrayList<>();
                for (int i=0;i<100;i++) {
                    Request r = new Request(new UUID(seed,i),store.read().token(),new Intent(rng.nextInt(1000),rng.nextBoolean()));
                    requests.add(r); results.add(store.commit(r));
                    int past = rng.nextInt(requests.size());
                    assertEquals(results.get(past),store.commit(requests.get(past)),"seed="+seed+", step="+i);
                }
            }
        }
    }
}
