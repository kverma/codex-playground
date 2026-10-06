package atlas.poc;

import java.time.Duration;
import java.util.*;
import com.datastax.oss.driver.api.core.cql.*;
import com.datastax.oss.driver.api.core.DefaultConsistencyLevel;
import static atlas.poc.DraftProcessClient.JSON;

/** Root survives logical offer restoration only; same-cluster disaster recovery is not qualified. */
final class CassandraRootAuthority implements AutoCloseable {
    record Version(UUID guard,SplitArchiveCheckpoint.Root root) {}
    record Proposal(Version expected,Version next) {}
    enum Resolution { UNCHANGED, PUBLISHED, UNKNOWN }
    static String marker(UUID subject,String operation) { return "/* atlas-root:"+subject+":"+operation+" */"; }
    private final UUID subject;
    private final CassandraStore transport;
    private final String dc;
    private final Duration timeout;
    CassandraRootAuthority(UUID subject) { this(subject,9042,"dc1","single"); }
    CassandraRootAuthority(UUID subject,int port,String dc,String topology) {
        this(subject,port,dc,topology,Duration.ofSeconds(20));
    }
    CassandraRootAuthority(UUID subject,int port,String dc,String topology,Duration timeout) {
        this.subject=subject;this.dc=dc;this.timeout=timeout;transport=new CassandraStore(subject,"127.0.0.1",port,dc,topology,timeout);
        transport.sessionForPoc().execute("CREATE TABLE IF NOT EXISTS atlas_poc.recovery_root (subject uuid PRIMARY KEY,guard uuid,payload text)");
    }
    private SimpleStatement write(String cql,Object...args) {
        return SimpleStatement.builder(cql).addPositionalValues(args).setConsistencyLevel(DefaultConsistencyLevel.QUORUM)
            .setSerialConsistencyLevel(DefaultConsistencyLevel.SERIAL).setIdempotence(false).setTimeout(timeout).build();
    }
    void bootstrap(SplitArchiveCheckpoint.Root root) throws Exception {
        boolean applied=PocPolicy.execute(transport.sessionForPoc(),write("INSERT INTO atlas_poc.recovery_root(subject,guard,payload) VALUES (?,?,?) IF NOT EXISTS",
            subject,UUID.randomUUID(),JSON.writeValueAsString(root)),true,dc).wasApplied();
        if(!applied)throw new IllegalStateException("root already bootstrapped");
    }
    Version read() throws Exception {
        Row row=PocPolicy.execute(transport.sessionForPoc(),SimpleStatement.builder("SELECT guard,payload FROM atlas_poc.recovery_root WHERE subject=? "+marker(subject,"READ"))
            .addPositionalValues(subject).setConsistencyLevel(DefaultConsistencyLevel.SERIAL).setTimeout(timeout).build(),false,dc).one();
        if(row==null)throw new IllegalStateException("AUTHORITY_MISSING: no automatic bootstrap");
        var root=JSON.readValue(row.getString("payload"),SplitArchiveCheckpoint.Root.class);
        if(root.version()!=1||!subject.equals(root.subject()))throw new IllegalStateException("AUTHORITY_INVALID");
        return new Version(row.getUuid("guard"),root);
    }
    Proposal propose(Version expected,SplitArchiveCheckpoint.Root next) {
        if(!subject.equals(next.subject())||!subject.equals(expected.root().subject()))throw new IllegalArgumentException("wrong root subject");
        return new Proposal(expected,new Version(UUID.randomUUID(),next));
    }
    boolean publish(Proposal proposal) throws Exception {
        if(!subject.equals(proposal.next().root().subject()))throw new IllegalArgumentException("wrong root subject");
        return PocPolicy.execute(transport.sessionForPoc(),write("UPDATE atlas_poc.recovery_root SET guard=?,payload=? WHERE subject=? IF guard=? "+marker(subject,"CAS"),
            proposal.next().guard(),JSON.writeValueAsString(proposal.next().root()),subject,proposal.expected().guard()),true,dc).wasApplied();
    }
    Resolution resolve(Proposal proposal) throws Exception {
        Version observed=read();
        if(observed.equals(proposal.next()))return Resolution.PUBLISHED;
        if(observed.equals(proposal.expected()))return Resolution.UNCHANGED;
        return Resolution.UNKNOWN; // A later writer can hide whether this proposal ever applied.
    }
    boolean compareAndSet(Version expected,SplitArchiveCheckpoint.Root next) throws Exception {
        return publish(propose(expected,next));
    }
    public void close() { transport.close(); }
}

