package atlas.poc;
import com.datastax.oss.driver.api.core.*;
import com.datastax.oss.driver.api.core.cql.*;
import org.junit.jupiter.api.Test;
import static atlas.poc.TraceAssertions.*;
class PocPolicyTest {
    // BEGIN ATLAS SCENARIO
    /**
     * Goal: Keep the POC's cross-DC consistency assumptions explicit
     * Boundary: Try weaker local-only consistency and unapproved automatic driver replay settings
     * Expected: The policy guard rejects those settings before they can count as valid evidence.
     */
    @org.junit.jupiter.api.DisplayName("AT-018 | Keep the POC's cross-DC consistency assumptions explicit")
    // END ATLAS SCENARIO
    @Test void rejectsLocalSerialOrLocalQuorumAndUnsafeDriverReplay() {
        var good=SimpleStatement.builder("UPDATE fixture SET value=1 IF value=0")
            .setConsistencyLevel(DefaultConsistencyLevel.QUORUM).setSerialConsistencyLevel(DefaultConsistencyLevel.SERIAL).setIdempotence(false).build();
        assertDoesNotThrow(()->PocPolicy.validate(good,true));
        assertThrows(IllegalStateException.class,()->PocPolicy.validate(good.setSerialConsistencyLevel(DefaultConsistencyLevel.LOCAL_SERIAL),true));
        assertThrows(IllegalStateException.class,()->PocPolicy.validate(good.setConsistencyLevel(DefaultConsistencyLevel.LOCAL_QUORUM),true));
        assertThrows(IllegalStateException.class,()->PocPolicy.validate(good.setIdempotent(true),true));
        assertThrows(IllegalStateException.class,()->PocPolicy.validate(SimpleStatement.newInstance("SELECT * FROM fixture").setConsistencyLevel(DefaultConsistencyLevel.LOCAL_QUORUM),false));
    }
}
