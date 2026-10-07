package atlas.poc;

class TransactionModelTest extends TransactionContract {
    protected Transactions.Store open() { return new Transactions.Model(); }
}
