package atlas.poc;

import com.datastax.oss.driver.api.core.CqlSession;
import com.datastax.oss.driver.api.core.DefaultConsistencyLevel;
import com.datastax.oss.driver.api.core.cql.*;
import com.datastax.oss.driver.api.core.config.*;
import java.net.InetSocketAddress;
import java.time.Duration;
import java.util.UUID;
import static atlas.poc.Protocol.*;

/** One subject partition, one table, all authoritative mutations conditional. */
public final class CassandraStore implements Store {
    private final CqlSession session;
    private final UUID subject;
    public CassandraStore(UUID subject) {
        this(subject, "127.0.0.1", 9042, "dc1", false, Duration.ofSeconds(20));
    }
    public CassandraStore(UUID subject, String host, int port, String dc, boolean fullHa, Duration timeout) {
        this.subject = subject;
        this.timeout = timeout;
        var config = DriverConfigLoader.programmaticBuilder()
            .withString(DefaultDriverOption.PROTOCOL_VERSION, "V4")
            .withDuration(DefaultDriverOption.REQUEST_TIMEOUT, timeout).build();
        session = CqlSession.builder().addContactPoint(new InetSocketAddress(host, port)).withLocalDatacenter(dc)
            .withConfigLoader(config).build();
        String version = session.execute("SELECT release_version FROM system.local").one().getString("release_version");
        if (!"4.0.5".equals(version)) { session.close(); throw new IllegalStateException("Expected Cassandra 4.0.5, got " + version); }
        session.execute("CREATE KEYSPACE IF NOT EXISTS atlas_poc WITH replication = " +
            (fullHa ? "{'class':'NetworkTopologyStrategy','dc1':3,'dc2':3,'dc3':3}" : "{'class':'NetworkTopologyStrategy','dc1':1}"));
        var replication = session.execute("SELECT replication FROM system_schema.keyspaces WHERE keyspace_name='atlas_poc'")
            .one().getMap("replication",String.class,String.class);
        boolean valid = fullHa ? "3".equals(replication.get("dc1")) && "3".equals(replication.get("dc2")) && "3".equals(replication.get("dc3"))
            : "1".equals(replication.get("dc1")) && replication.size() == 2;
        if (!valid) { session.close(); throw new IllegalStateException("Wrong test replication: " + replication); }
        session.execute("CREATE TABLE IF NOT EXISTS atlas_poc.subject (subject uuid, row text, commit_token uuid, request_hash text, cents int, churned boolean, PRIMARY KEY (subject,row))");
        session.execute(write("INSERT INTO atlas_poc.subject (subject,row,commit_token,cents,churned) VALUES (?,'HEAD',?,500,false) IF NOT EXISTS", subject, UUID.randomUUID()));
    }
    private final Duration timeout;
    private SimpleStatement write(String cql, Object... args) {
        return SimpleStatement.builder(cql).addPositionalValues(args)
            .setConsistencyLevel(DefaultConsistencyLevel.QUORUM)
            .setSerialConsistencyLevel(DefaultConsistencyLevel.SERIAL)
            .setIdempotence(false).setTimeout(timeout).build();
    }
    private Row row(String key) {
        // SERIAL reads resolve in-flight Paxos before exposing authoritative state.
        return session.execute(SimpleStatement.builder("SELECT * FROM atlas_poc.subject WHERE subject=? AND row=?")
            .addPositionalValues(subject,key).setConsistencyLevel(DefaultConsistencyLevel.SERIAL)
            .setTimeout(timeout).build()).one();
    }
    private Intent intent(Row row) { return new Intent(row.getInt("cents"), row.getBoolean("churned")); }
    public Head read() { Row r = row("HEAD"); return new Head(r.getUuid("commit_token"), intent(r)); }
    private Receipt receipt(Request request) {
        Row r = row("OP:" + request.operation());
        return r == null ? null : exact(new Receipt(request.operation(), r.getString("request_hash"), r.getUuid("commit_token"), intent(r)), request);
    }
    public Receipt commit(Request r) {
        try {
            Receipt old = receipt(r);
            if (old != null) return old;
            UUID commit_token = UUID.randomUUID();
            boolean applied = session.execute(write("""
                BEGIN BATCH
                  UPDATE atlas_poc.subject SET commit_token=?, cents=?, churned=? WHERE subject=? AND row='HEAD' IF commit_token=?;
                  INSERT INTO atlas_poc.subject (subject,row,commit_token,request_hash,cents,churned) VALUES (?,?,?,?,?,?) IF NOT EXISTS;
                APPLY BATCH
                """, commit_token,r.intent().cents(),r.intent().churnedEligible(),subject,r.expected(),
                subject,"OP:"+r.operation(),commit_token,r.hash(),r.intent().cents(),r.intent().churnedEligible())).wasApplied();
            if (applied) return new Receipt(r.operation(),r.hash(),commit_token,r.intent());
            old = receipt(r);
            if (old != null) return old;
            throw new Conflict();
        } catch (Conflict | KeyReuse e) { throw e; }
        catch (com.datastax.oss.driver.api.core.DriverException e) { throw new Indeterminate(e); }
    }
    public void close() { session.close(); }
}
