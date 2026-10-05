package atlas.poc;

import java.time.Duration;
import java.util.*;
import com.datastax.oss.driver.api.core.cql.*;
import com.datastax.oss.driver.api.core.DefaultConsistencyLevel;
import static atlas.poc.DraftProcessClient.JSON;

/** Root survives logical offer restoration only; same-cluster disaster recovery is not qualified. */
final class CassandraRootAuthority implements AutoCloseable {
    record Version(UUID guard,SplitArchiveCheckpoint.Root root) {}
    private final UUID subject;
    private final CassandraStore transport;
    private final String dc;
    CassandraRootAuthority(UUID subject) { this(subject,9042,"dc1","single"); }
    CassandraRootAuthority(UUID subject,int port,String dc,String topology) {
        this.subject=subject;this.dc=dc;transport=new CassandraStore(subject,"127.0.0.1",port,dc,topology,Duration.ofSeconds(20));
        transport.sessionForPoc().execute("CREATE TABLE IF NOT EXISTS atlas_poc.recovery_root (subject uuid PRIMARY KEY,guard uuid,payload text)");
    }
    private SimpleStatement write(String cql,Object...args) {
        return SimpleStatement.builder(cql).addPositionalValues(args).setConsistencyLevel(DefaultConsistencyLevel.QUORUM)
            .setSerialConsistencyLevel(DefaultConsistencyLevel.SERIAL).setIdempotence(false).setTimeout(Duration.ofSeconds(20)).build();
    }
    void bootstrap(SplitArchiveCheckpoint.Root root) throws Exception {
        boolean applied=PocPolicy.execute(transport.sessionForPoc(),write("INSERT INTO atlas_poc.recovery_root(subject,guard,payload) VALUES (?,?,?) IF NOT EXISTS",
            subject,UUID.randomUUID(),JSON.writeValueAsString(root)),true,dc).wasApplied();
        if(!applied)throw new IllegalStateException("root already bootstrapped");
    }
    Version read() throws Exception {
        Row row=PocPolicy.execute(transport.sessionForPoc(),SimpleStatement.builder("SELECT guard,payload FROM atlas_poc.recovery_root WHERE subject=?")
            .addPositionalValues(subject).setConsistencyLevel(DefaultConsistencyLevel.SERIAL).setTimeout(Duration.ofSeconds(20)).build(),false,dc).one();
        if(row==null)throw new IllegalStateException("AUTHORITY_MISSING: no automatic bootstrap");
        var root=JSON.readValue(row.getString("payload"),SplitArchiveCheckpoint.Root.class);
        if(root.version()!=1||!subject.equals(root.subject()))throw new IllegalStateException("AUTHORITY_INVALID");
        return new Version(row.getUuid("guard"),root);
    }
    boolean compareAndSet(Version expected,SplitArchiveCheckpoint.Root next) throws Exception {
        if(!subject.equals(next.subject()))throw new IllegalArgumentException("wrong root subject");
        return PocPolicy.execute(transport.sessionForPoc(),write("UPDATE atlas_poc.recovery_root SET guard=?,payload=? WHERE subject=? IF guard=?",
            UUID.randomUUID(),JSON.writeValueAsString(next),subject,expected.guard()),true,dc).wasApplied();
    }
    public void close() { transport.close(); }
}
