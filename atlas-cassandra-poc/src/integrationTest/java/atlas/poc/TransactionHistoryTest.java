package atlas.poc;

import org.junit.jupiter.api.*;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;
import static atlas.poc.Transactions.*;
import static org.junit.jupiter.api.Assertions.*;

@Tag("three") @Tag("History")
class TransactionHistoryTest {
    @Test void checksCrossDcConcurrentHistoriesAndPartitionRecovery() throws Exception {
        UUID subject=UUID.randomUUID();
        var stores=new ArrayList<Store>();
        try {
            for(int dc=1;dc<=3;dc++) stores.add(new TransactionCassandraStore(subject,"127.0.0.1",9042+(dc-1)*100,"dc"+dc,"three"));
            Store routed=new Store() {
                public Snapshot read() { return stores.get(1).read(); }
                public Receipt commit(Request request) { return stores.get(Math.floorMod((int)request.operation().getLeastSignificantBits(),3)).commit(request); }
                public Receipt resolve(Request request) { return stores.get(1).commit(request); }
            };
            var workload=new HistoryWorkload(routed);
            for(long seed=1000;seed<1010;seed++) workload.round(seed,Path.of("build/evidence/history-cassandra"),()->{});
            try {
                workload.round(1101,Path.of("build/evidence/history-cassandra"),()->{
                    try { command("partition"); } catch(Exception e) { throw new RuntimeException(e); }
                });
            } finally { command("heal"); }
            for(long seed=1200;seed<1203;seed++) workload.round(seed,Path.of("build/evidence/history-cassandra"),()->{});
        } finally { for(Store store:stores) store.close(); }
    }
    private void command(String action) throws Exception {
        Process process=new ProcessBuilder("bash","scripts/three.sh",action).inheritIO().start();
        if(!process.waitFor(30,TimeUnit.SECONDS)) { process.destroyForcibly(); fail("fault script timeout"); }
        assertEquals(0,process.exitValue());
    }
}
