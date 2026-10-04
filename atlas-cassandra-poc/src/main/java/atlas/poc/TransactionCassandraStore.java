package atlas.poc;

import com.datastax.oss.driver.api.core.*;
import com.datastax.oss.driver.api.core.cql.*;
import java.time.Duration;
import java.util.UUID;
import static atlas.poc.Transactions.*;
import atlas.poc.Transactions.Error;

/** Packed bounded subject manifest + immutable acceptance receipt in one conditional batch. */
public final class TransactionCassandraStore implements Store {
    private final CassandraStore transport;
    private final CqlSession session;
    private final UUID subject;
    private final String dc;
    public TransactionCassandraStore(UUID subject,String host,int port,String dc,String topology) {
        this.subject=subject; this.dc=dc;
        transport=new CassandraStore(subject,host,port,dc,topology,Duration.ofSeconds(20));
        session=transport.sessionForPoc();
        session.execute("CREATE TABLE IF NOT EXISTS atlas_poc.term_subject (subject uuid, row text, generation uuid, state text, request_hash text, before_state text, PRIMARY KEY (subject,row))");
        Snapshot initial=initial();
        PocPolicy.execute(session,write("INSERT INTO atlas_poc.term_subject (subject,row,generation,state) VALUES (?,'HEAD',?,?) IF NOT EXISTS",subject,initial.generation(),TransactionCodec.encode(initial)),true,dc);
    }
    private SimpleStatement write(String cql,Object... values) {
        return SimpleStatement.builder(cql).addPositionalValues(values).setConsistencyLevel(DefaultConsistencyLevel.QUORUM)
            .setSerialConsistencyLevel(DefaultConsistencyLevel.SERIAL).setIdempotence(false).setTimeout(Duration.ofSeconds(20)).build();
    }
    private Row row(String key) {
        return PocPolicy.execute(session,SimpleStatement.builder("SELECT * FROM atlas_poc.term_subject WHERE subject=? AND row=?")
            .addPositionalValues(subject,key).setConsistencyLevel(DefaultConsistencyLevel.SERIAL).setTimeout(Duration.ofSeconds(20)).build(),false,dc).one();
    }
    public Snapshot read() {
        try { return TransactionCodec.decode(row("HEAD").getString("state")); }
        catch(DriverException e) { throw new Rejected(e); }
    }
    private Receipt receipt(Request request) {
        Row old=row("OP:"+request.operation());
        return old==null?null:exact(new Receipt(request.operation(),old.getString("request_hash"),TransactionCodec.decode(old.getString("before_state")),TransactionCodec.decode(old.getString("state"))),request);
    }
    public Receipt commit(Request request) {
        try {
            for(int attempt=0;attempt<32;attempt++) {
                Receipt old=receipt(request); if(old!=null) return old;
                Snapshot before=read();
                Snapshot after;
                try { after=apply(before,request); }
                catch(Rejected rejection) {
                    // A competing exact retry may have committed between our receipt and HEAD reads.
                    old=receipt(request); if(old!=null) return old; throw rejection;
                }
                boolean accepted=PocPolicy.execute(session,write("""
                    BEGIN BATCH
                    UPDATE atlas_poc.term_subject SET generation=?,state=? WHERE subject=? AND row='HEAD' IF generation=?;
                    INSERT INTO atlas_poc.term_subject (subject,row,generation,state,request_hash,before_state) VALUES (?,?,?,?,?,?) IF NOT EXISTS;
                    APPLY BATCH
                    """,after.generation(),TransactionCodec.encode(after),subject,before.generation(),subject,"OP:"+request.operation(),
                    after.generation(),TransactionCodec.encode(after),request.hash(),TransactionCodec.encode(before)),true,dc).wasApplied();
                if(accepted) return new Receipt(request.operation(),request.hash(),before,after);
                // Preserve logical read versions and intended updates; revalidate against a fresh subject generation.
            }
            throw new Rejected(Error.INDETERMINATE);
        } catch(DriverException e) { throw new Rejected(e); }
    }
    public void close() { transport.close(); }
}
