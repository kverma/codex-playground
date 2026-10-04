package atlas.poc;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import static atlas.poc.Transactions.*;
import static atlas.poc.Retention.*;
import static org.junit.jupiter.api.Assertions.*;
class RetentionCassandraTest extends RetentionContract {
    protected Retention.Store open(UUID subject,RetentionClock clock) {
        return new RetentionCassandraStore(subject,KEY,clock,"127.0.0.1",9042,"dc1","single");
    }
    @Test void floorAndUnexpiredReceiptsSurviveSessionRestart() {
        var clock=new RetentionClock(); UUID subject=UUID.randomUUID(); Request old,young; Ticket expired,retained; Receipt receipt;
        try(var store=open(subject,clock)) {
            old=store.edit(java.util.Map.of(Group.ROYALTY,"2000")); expired=store.issue(old);
            clock.advance(LIFETIME_MILLIS/2); young=store.edit(java.util.Map.of(Group.ROYALTY,"2500")); retained=store.issue(young); receipt=store.commit(retained,young);
            clock.advance(LIFETIME_MILLIS/2); store.compact();
        }
        try(var reopened=open(subject,clock)) {
            assertEquals(1,reopened.view().floor()); assertEquals(receipt,reopened.commit(retained,young));
            assertEquals(Code.REQUEST_TOO_OLD,assertThrows(Failure.class,()->reopened.commit(expired,old)).code);
        }
    }
}
