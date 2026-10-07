package atlas.poc;

import com.datastax.oss.driver.api.core.*;
import com.datastax.oss.driver.api.core.cql.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Duration;
import java.util.*;
import static atlas.poc.ArchiveRecovery.*;

/** Test-only persistence adapter. External authority/archive are shared in-memory fixtures. */
final class ArchiveCassandraFixture implements AutoCloseable {
    record Persisted(UUID guard,Hot hot) {}
    record Pending(Persisted before,Hot after) {}
    private final UUID subject;
    private final Storage external;
    private final Model transition;
    private final CassandraStore transport;
    private final CqlSession session;
    private final ObjectMapper json=new ObjectMapper();
    private final String dc;
    private final Duration timeout;
    ArchiveCassandraFixture(UUID subject,Storage external,String host,int port,String dc,String topology) {
        this(subject,external,host,port,dc,topology,Broken.NONE);
    }
    ArchiveCassandraFixture(UUID subject,Storage external,String host,int port,String dc,String topology,Broken broken) {
        this(subject,external,host,port,dc,topology,broken,Duration.ofSeconds(20));
    }
    ArchiveCassandraFixture(UUID subject,Storage external,String host,int port,String dc,String topology,Broken broken,Duration timeout) {
        this.timeout=timeout;
        this.subject=subject;this.external=external;this.dc=dc;transition=new Model(external,broken);
        transport=new CassandraStore(subject,host,port,dc,topology,timeout);session=transport.sessionForPoc();
        session.execute("CREATE TABLE IF NOT EXISTS atlas_poc.archive_fixture_v1 (subject uuid,row text,guard uuid,payload text,PRIMARY KEY(subject,row))");
        Hot initial=new Hot(0,0,external.genesis,Map.of(),Set.of(),false);
        PocPolicy.execute(session,write("INSERT INTO atlas_poc.archive_fixture_v1(subject,row,guard,payload) VALUES (?,'HEAD',?,?) IF NOT EXISTS",subject,UUID.randomUUID(),encode(initial)),true,dc);
    }
    private String encode(Object value) {
        try { return json.writeValueAsString(value); } catch(Exception e) { throw new IllegalStateException(e); }
    }
    private <T> T decode(String text,Class<T> type) {
        try { return json.readValue(text,type); } catch(Exception e) { throw new IllegalStateException(e); }
    }
    private SimpleStatement write(String cql,Object... values) {
        return SimpleStatement.builder(cql).addPositionalValues(values).setConsistencyLevel(DefaultConsistencyLevel.QUORUM)
            .setSerialConsistencyLevel(DefaultConsistencyLevel.SERIAL).setIdempotence(false).setTimeout(timeout).build();
    }
    Persisted read() {
        var rows=PocPolicy.execute(session,SimpleStatement.builder("SELECT * FROM atlas_poc.archive_fixture_v1 WHERE subject=?")
            .addPositionalValues(subject).setConsistencyLevel(DefaultConsistencyLevel.SERIAL).setTimeout(timeout).build(),false,dc).all();
        Row head=rows.stream().filter(r->r.getString("row").equals("HEAD")).findFirst().orElseThrow();
        Hot metadata=decode(head.getString("payload"),Hot.class);var entries=new HashMap<Long,Item>();
        if(!metadata.rows().isEmpty())throw new IllegalStateException("HEAD must not hide receipt rows");
        for(Row row:rows)if(!row.getString("row").equals("HEAD"))entries.put(Long.parseLong(row.getString("row").substring(2)),decode(row.getString("payload"),Item.class));
        return new Persisted(head.getUuid("guard"),new Hot(metadata.allocated(),metadata.floor(),metadata.head(),entries,metadata.sealed(),metadata.fenced()));
    }
    // HEAD (including seal/fence/floor) and all changed slot rows share one conditional batch.
    static String marker(UUID subject,Kind kind) { return "/* atlas:"+subject+":"+kind+" */"; }
    private boolean swap(Persisted before,Hot after) { return swap(before,after,""); }
    private boolean swap(Persisted before,Hot after,String marker) {
        Hot metadata=new Hot(after.allocated(),after.floor(),after.head(),Map.of(),after.sealed(),after.fenced());
        StringBuilder cql=new StringBuilder("BEGIN BATCH "+marker+" UPDATE atlas_poc.archive_fixture_v1 SET guard=?,payload=? WHERE subject=? AND row='HEAD' IF guard=?;");
        var args=new ArrayList<Object>(List.of(UUID.randomUUID(),encode(metadata),subject,before.guard()));
        for(var entry:after.rows().entrySet())if(!entry.getValue().equals(before.hot().rows().get(entry.getKey()))) {
            cql.append("INSERT INTO atlas_poc.archive_fixture_v1(subject,row,payload) VALUES (?,?,?);");
            args.addAll(List.of(subject,"S:"+entry.getKey(),encode(entry.getValue())));
        }
        for(long n:before.hot().rows().keySet())if(!after.rows().containsKey(n)) {
            cql.append("DELETE FROM atlas_poc.archive_fixture_v1 WHERE subject=? AND row=?;");args.addAll(List.of(subject,"S:"+n));
        }
        cql.append("APPLY BATCH");return PocPolicy.execute(session,write(cql.toString(),args.toArray()),true,dc).wasApplied();
    }
    Frame execute(Command command) {
        // Serial orchestration is intentional; this lock is not a distributed authority implementation.
        synchronized(external) {
            Persisted before=read();external.hot=before.hot();Frame proposed=transition.execute(command);
            if(!before.hot().equals(external.hot)&&!swap(before,external.hot,marker(subject,command.kind()))) {
                external.hot=read().hot();throw new IllegalStateException("unexpected concurrent fixture mutation; do not claim a serial trace");
            }
            external.hot=read().hot();return new Frame(command,proposed.outcome(),transition.observe());
        }
    }
    Pending prepareAcceptance(long sequence) {
        synchronized(external) {
            Persisted before=read();external.hot=before.hot();
            Frame result=transition.execute(new Command(Kind.ACCEPT,sequence,null,null,Mode.NORMAL,true));
            if(!result.outcome().code().equals("OK"))throw new IllegalStateException("delayed request must first be admissible");
            Hot after=external.hot;external.hot=before.hot();return new Pending(before,after);
        }
    }
    boolean deliver(Pending pending) { return swap(pending.before(),pending.after()); }
    // Negative control: an old writer incorrectly replaces its guard with today's guard.
    boolean deliverIgnoringGuard(Pending pending) { return swap(read(),pending.after()); }
    public void close() { transport.close(); }
}
