package atlas.poc;

import com.datastax.oss.driver.api.core.CqlSession;
import com.datastax.oss.driver.api.core.DefaultConsistencyLevel;
import com.datastax.oss.driver.api.core.cql.*;
import java.net.InetSocketAddress;
import java.time.Duration;
import java.util.UUID;
import static atlas.poc.Protocol.*;

/** One subject partition, one table, all authoritative mutations conditional. */
public final class CassandraStore implements Store {
    private final CqlSession session;
    private final UUID subject;
    public CassandraStore(UUID subject) {
        this.subject = subject;
        session = CqlSession.builder().addContactPoint(new InetSocketAddress("127.0.0.1", 9042)).withLocalDatacenter("dc1").build();
        String version = session.execute("SELECT release_version FROM system.local").one().getString("release_version");
        if (!"4.0.5".equals(version)) { session.close(); throw new IllegalStateException("Expected Cassandra 4.0.5, got " + version); }
        session.execute("CREATE KEYSPACE IF NOT EXISTS atlas_poc WITH replication = {'class':'NetworkTopologyStrategy','dc1':1}");
        session.execute("CREATE TABLE IF NOT EXISTS atlas_poc.subject (subject uuid, row text, token uuid, request_hash text, cents int, churned boolean, PRIMARY KEY (subject,row))");
        session.execute(write("INSERT INTO atlas_poc.subject (subject,row,token,cents,churned) VALUES (?,'HEAD',?,500,false) IF NOT EXISTS", subject, UUID.randomUUID()));
    }
    private SimpleStatement write(String cql, Object... args) {
        return SimpleStatement.builder(cql).addPositionalValues(args)
            .setConsistencyLevel(DefaultConsistencyLevel.QUORUM)
            .setSerialConsistencyLevel(DefaultConsistencyLevel.SERIAL)
            .setIdempotent(false).setTimeout(Duration.ofSeconds(20)).build();
    }
    private Row row(String key) {
        // SERIAL reads resolve in-flight Paxos before exposing authoritative state.
        return session.execute(SimpleStatement.builder("SELECT * FROM atlas_poc.subject WHERE subject=? AND row=?")
            .addPositionalValues(subject,key).setConsistencyLevel(DefaultConsistencyLevel.SERIAL)
            .setTimeout(Duration.ofSeconds(20)).build()).one();
    }
    private Intent intent(Row row) { return new Intent(row.getInt("cents"), row.getBoolean("churned")); }
    public Head read() { Row r = row("HEAD"); return new Head(r.getUuid("token"), intent(r)); }
    private Receipt receipt(Request request) {
        Row r = row("OP:" + request.operation());
        return r == null ? null : exact(new Receipt(request.operation(), r.getString("request_hash"), r.getUuid("token"), intent(r)), request);
    }
    public Receipt commit(Request r) {
        try {
            Receipt old = receipt(r);
            if (old != null) return old;
            UUID token = UUID.randomUUID();
            boolean applied = session.execute(write("""
                BEGIN BATCH
                  UPDATE atlas_poc.subject SET token=?, cents=?, churned=? WHERE subject=? AND row='HEAD' IF token=?;
                  INSERT INTO atlas_poc.subject (subject,row,token,request_hash,cents,churned) VALUES (?,?,?,?,?,?) IF NOT EXISTS;
                APPLY BATCH
                """, token,r.intent().cents(),r.intent().churnedEligible(),subject,r.expected(),
                subject,"OP:"+r.operation(),token,r.hash(),r.intent().cents(),r.intent().churnedEligible())).wasApplied();
            if (applied) return new Receipt(r.operation(),r.hash(),token,r.intent());
            old = receipt(r);
            if (old != null) return old;
            throw new Conflict();
        } catch (Conflict | KeyReuse e) { throw e; }
        catch (com.datastax.oss.driver.api.core.DriverException e) { throw new Indeterminate(e); }
    }
    public void close() { session.close(); }
}
