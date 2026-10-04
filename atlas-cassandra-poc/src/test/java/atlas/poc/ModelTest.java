package atlas.poc;

import org.junit.jupiter.api.Test;
import java.util.*;
import static atlas.poc.Protocol.*;
import static org.junit.jupiter.api.Assertions.*;

class ModelTest extends Contract {
    protected Store open() { return new Model(); }
    @Test void revisionCapturesIntentAndExcludesObservationTime() {
        Intent march = new Intent(500,false);
        assertEquals(march.revision(), new Intent(500,false).revision()); // same configuration observed in April
        assertNotEquals(march.revision(),new Intent(500,true).revision()); // eligibility broadened
        assertNotEquals(march.revision(),new Intent(600,false).revision());
    }
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
