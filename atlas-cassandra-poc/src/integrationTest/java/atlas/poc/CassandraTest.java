package atlas.poc;

import java.util.UUID;
import org.junit.jupiter.api.Test;
import static atlas.poc.Protocol.*;
import static org.junit.jupiter.api.Assertions.*;

class CassandraTest extends Contract {
    protected Store open() { return new CassandraStore(UUID.randomUUID()); }
    @Test void receiptSurvivesClientSessionRestart() {
        UUID subject = UUID.randomUUID();
        Request request; Receipt receipt;
        try (Store store = new CassandraStore(subject)) {
            request = new Request(UUID.randomUUID(),store.read().token(),new Intent(600,true));
            receipt = store.commit(request);
        }
        try (Store reopened = new CassandraStore(subject)) {
            assertEquals(receipt,reopened.commit(request));
            assertEquals(receipt.token(),reopened.read().token());
        }
    }
}
