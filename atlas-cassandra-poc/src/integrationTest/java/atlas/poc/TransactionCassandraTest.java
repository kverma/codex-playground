package atlas.poc;

import java.util.UUID;

class TransactionCassandraTest extends TransactionContract {
    protected Transactions.Store open() {
        return new TransactionCassandraStore(UUID.randomUUID(),"127.0.0.1",9042,"dc1","single");
    }
}
