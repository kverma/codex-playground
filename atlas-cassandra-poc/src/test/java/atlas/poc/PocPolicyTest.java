package atlas.poc;
import com.datastax.oss.driver.api.core.*;
import com.datastax.oss.driver.api.core.cql.*;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
class PocPolicyTest {
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
