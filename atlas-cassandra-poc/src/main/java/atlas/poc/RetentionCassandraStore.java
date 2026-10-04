package atlas.poc;

import com.datastax.oss.driver.api.core.*;
import com.datastax.oss.driver.api.core.cql.*;
import java.time.*;
import java.util.*;
import static atlas.poc.Retention.*;
import static atlas.poc.Transactions.*;

/** Atomic floor advance + receipt pruning, guarded by the same partition metadata CAS as acceptance. */
public final class RetentionCassandraStore extends Base {
    private final CassandraStore transport;
    private final CqlSession session;
    private final String dc;
    public RetentionCassandraStore(UUID subject,byte[] key,Clock clock,String host,int port,String dc,String topology) {
        super(subject,key,clock); this.dc=dc;
        transport=new CassandraStore(subject,host,port,dc,topology,Duration.ofSeconds(20));
        session=transport.sessionForPoc();
        session.execute("CREATE TABLE IF NOT EXISTS atlas_poc.retained_subject (subject uuid,row text,generation uuid,allocated bigint,floor bigint,last_expiry bigint,state text,expires_at bigint,operation uuid,request_hash text,before_state text,PRIMARY KEY(subject,row))");
        View initial=initialView();
        PocPolicy.execute(session,statement("INSERT INTO atlas_poc.retained_subject(subject,row,generation,allocated,floor,last_expiry,state) VALUES (?,'HEAD',?,0,0,0,?) IF NOT EXISTS",subject,initial.generation(),TransactionCodec.encode(initial.snapshot())),true,dc);
    }
    private SimpleStatement statement(String cql,Object... values) {
        return SimpleStatement.builder(cql).addPositionalValues(values).setConsistencyLevel(DefaultConsistencyLevel.QUORUM)
            .setSerialConsistencyLevel(DefaultConsistencyLevel.SERIAL).setIdempotence(false).setTimeout(Duration.ofSeconds(20)).build();
    }
    public View view() {
        try {
            var rows=PocPolicy.execute(session,SimpleStatement.builder("SELECT * FROM atlas_poc.retained_subject WHERE subject=?")
                .addPositionalValues(subject).setConsistencyLevel(DefaultConsistencyLevel.SERIAL).setTimeout(Duration.ofSeconds(20)).build(),false,dc).all();
            Row head=rows.stream().filter(r->r.getString("row").equals("HEAD")).findFirst().orElseThrow();
            Map<Long,Entry> entries=new HashMap<>();
            for(Row row:rows) if(!row.getString("row").equals("HEAD")) {
                long sequence=Long.parseLong(row.getString("row").substring(2));
                Receipt receipt=row.isNull("state")?null:new Receipt(row.getUuid("operation"),row.getString("request_hash"),TransactionCodec.decode(row.getString("before_state")),TransactionCodec.decode(row.getString("state")));
                entries.put(sequence,new Entry(row.getLong("expires_at"),row.getUuid("operation"),row.getString("request_hash"),receipt));
            }
            return new View(head.getUuid("generation"),head.getLong("allocated"),head.getLong("floor"),head.getLong("last_expiry"),TransactionCodec.decode(head.getString("state")),entries);
        } catch(DriverException e) { throw new Failure(Code.INDETERMINATE); }
    }
    protected boolean swap(View before,View after) {
        StringBuilder cql=new StringBuilder("BEGIN BATCH UPDATE atlas_poc.retained_subject SET generation=?,allocated=?,floor=?,last_expiry=?,state=? WHERE subject=? AND row='HEAD' IF generation=?;");
        var values=new ArrayList<Object>(List.of(after.generation(),after.allocated(),after.floor(),after.lastExpiry(),TransactionCodec.encode(after.snapshot()),subject,before.generation()));
        for(var item:after.entries().entrySet()) if(!item.getValue().equals(before.entries().get(item.getKey()))) {
            Entry entry=item.getValue();
            cql.append("INSERT INTO atlas_poc.retained_subject(subject,row,expires_at,operation,request_hash,state,before_state) VALUES (?,?,?,?,?,?,?);");
            values.addAll(List.of(subject,"T:"+item.getKey(),entry.expiresAt(),entry.operation(),entry.requestHash()));
            values.add(entry.receipt()==null?null:TransactionCodec.encode(entry.receipt().after()));
            values.add(entry.receipt()==null?null:TransactionCodec.encode(entry.receipt().before()));
        }
        for(long sequence:before.entries().keySet()) if(!after.entries().containsKey(sequence)) {
            cql.append("DELETE FROM atlas_poc.retained_subject WHERE subject=? AND row=?;"); values.add(subject); values.add("T:"+sequence);
        }
        cql.append("APPLY BATCH");
        try { return PocPolicy.execute(session,statement(cql.toString(),values.toArray()),true,dc).wasApplied(); }
        catch(DriverException e) { throw new Failure(Code.INDETERMINATE); }
    }
    public void close() { transport.close(); }
}
