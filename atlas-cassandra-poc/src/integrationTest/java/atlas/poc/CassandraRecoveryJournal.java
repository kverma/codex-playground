package atlas.poc;

import java.time.Duration;
import java.util.*;
import com.datastax.oss.driver.api.core.cql.*;
import com.datastax.oss.driver.api.core.DefaultConsistencyLevel;
import static atlas.poc.RecoveryJournal.*;
import static atlas.poc.DraftProcessClient.JSON;

/** One table/partition per subject+store; state and accepted receipt are one conditional batch. */
final class CassandraRecoveryJournal implements Store {
    final UUID subject;final String store,dc;final Duration timeout;final CassandraStore transport;
    CassandraRecoveryJournal(UUID subject,String store,int port,String dc,String topology,Duration timeout) {
        this.subject=subject;this.store=store;this.dc=dc;this.timeout=timeout;
        transport=new CassandraStore(subject,"127.0.0.1",port,dc,topology,timeout);
        transport.sessionForPoc().execute("CREATE TABLE IF NOT EXISTS atlas_poc.recovery_journal (subject uuid, store text, row text, guard uuid, payload text, PRIMARY KEY ((subject,store),row))");
    }
    static String marker(UUID subject,String store) { return "/* atlas-recovery-journal:"+subject+":"+store+" */"; }
    private SimpleStatement write(String cql,Object...args) {
        return SimpleStatement.builder(cql).addPositionalValues(args).setConsistencyLevel(DefaultConsistencyLevel.QUORUM)
            .setSerialConsistencyLevel(DefaultConsistencyLevel.SERIAL).setIdempotence(false).setTimeout(timeout).build();
    }
    void bootstrap(State initial) {
        boolean applied=PocPolicy.execute(transport.sessionForPoc(),write("INSERT INTO atlas_poc.recovery_journal(subject,store,row,guard,payload) VALUES (?,?,'HEAD',?,?) IF NOT EXISTS",
            subject,store,initial.guard(),initial.value()),true,dc).wasApplied();
        if(!applied)throw new IllegalStateException("already bootstrapped");
    }
    public View view() throws Exception {
        ResultSet rows=PocPolicy.execute(transport.sessionForPoc(),SimpleStatement.builder("SELECT row,guard,payload FROM atlas_poc.recovery_journal WHERE subject=? AND store=?")
            .addPositionalValues(subject,store).setConsistencyLevel(DefaultConsistencyLevel.SERIAL).setTimeout(timeout).build(),false,dc);
        State head=null;var receipts=new HashMap<UUID,Receipt>();
        for(Row row:rows) {
            if(row.getString("row").equals("HEAD"))head=new State(row.getUuid("guard"),row.getString("payload"));
            else {
                Receipt receipt=JSON.readValue(row.getString("payload"),Receipt.class);
                if(!row.getString("row").equals("OP:"+receipt.request().operation()))throw new IllegalStateException("journal key mismatch");
                receipts.put(receipt.request().operation(),receipt);
            }
        }
        if(head==null)throw new IllegalStateException("missing recovery HEAD; no automatic bootstrap");
        return new View(head,receipts);
    }
    private Result replay(Receipt prior,Request request) {
        return prior.request().equals(request)?new Result("OK",prior):new Result("KEY_REUSE",null);
    }
    public Result apply(Request request) throws Exception {
        View before=view();Receipt prior=before.receipts().get(request.operation());if(prior!=null)return replay(prior,request);
        if(!before.state().equals(request.expected()))return new Result("CONFLICT",null);
        Receipt receipt=new Receipt(request,before.state(),request.next());
        boolean applied=PocPolicy.execute(transport.sessionForPoc(),write("""
            BEGIN BATCH
              UPDATE atlas_poc.recovery_journal SET guard=?,payload=? WHERE subject=? AND store=? AND row='HEAD' IF guard=? AND payload=?;
              INSERT INTO atlas_poc.recovery_journal(subject,store,row,guard,payload) VALUES (?,?,?,?,?) IF NOT EXISTS;
            APPLY BATCH
            """+marker(subject,store),request.next().guard(),request.next().value(),subject,store,request.expected().guard(),request.expected().value(),
            subject,store,"OP:"+request.operation(),request.next().guard(),JSON.writeValueAsString(receipt)),true,dc).wasApplied();
        if(applied)return new Result("OK",receipt);
        prior=view().receipts().get(request.operation());return prior==null?new Result("CONFLICT",null):replay(prior,request);
    }
    public void close() { transport.close(); }
}
