package atlas.poc;

import org.junit.jupiter.api.*;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.TimeUnit;
import static atlas.poc.Retention.*;
import static atlas.poc.Transactions.*;
import static org.junit.jupiter.api.Assertions.*;

@Tag("three") @Tag("Retention")
class RetentionHistoryCassandraTest {
    record HealedRead(int dc,int attempt,View view,String error) {}
    @Test void sixCrossDcHistoriesIncludePartitionRecoveryAndPostHealClosure() throws Exception {
        for(long seed=2000;seed<2006;seed++) {
            UUID subject=UUID.randomUUID();var clock=new RetentionClock();var stores=new ArrayList<Retention.Store>();
            var healedReads=new ArrayList<HealedRead>();
            try {
                for(int dc=1;dc<=3;dc++) stores.add(new RetentionCassandraStore(subject,RetentionContract.KEY,clock,"127.0.0.1",9042+(dc-1)*100,"dc"+dc,"three"));
                Retention.Store majority=stores.get(1);
                Retention.Store routed=new Retention.Store() {
                    public View view() { return majority.view(); }
                    public Request edit(Map<Group,String> updates) { return majority.edit(updates); }
                    public Ticket issue(Request request) { return majority.issue(request); }
                    public Draft prepare(Request request) { return majority.prepare(request); }
                    public Issued issueDraft(Draft draft) { return stores.get(Math.floorMod((int)draft.request().operation().getLeastSignificantBits(),3)).issueDraft(draft); }
                    public Receipt commit(Ticket ticket,Request request) { return stores.get(Math.floorMod((int)ticket.sequence()-1,3)).commit(ticket,request); }
                    public View compact() { return majority.compact(); }
                };
                final boolean partition=seed==2004;
                try {
                    var workload=new RetentionWorkload(subject,routed,majority,clock);
                    workload.round(seed,Path.of("build/evidence/retention-cassandra"),()->{
                        if(partition) try { command("partition"); } catch(Exception e) { throw new RuntimeException(e); }
                    });
                    if(partition) {
                        command("counters");
                        assertTrue(workload.history().stream().filter(c->c.kind().equals("ISSUE")).limit(3).anyMatch(c->Math.floorMod(c.draft().request().operation().getLeastSignificantBits(),3)==0&&"INDETERMINATE".equals(c.error())),"the draft routed to isolated dc1 must be ambiguous");
                        assertTrue(workload.history().stream().filter(c->c.kind().equals("COMMIT")).limit(3).anyMatch(c->Math.floorMod(c.issued().ticket().sequence()-1,3)==0&&"INDETERMINATE".equals(c.error())),"the commit routed to isolated dc1 must be ambiguous");
                    }
                } finally { if(partition) command("heal"); }
                var healedViews=new ArrayList<View>();
                for(int dc=1;dc<=3;dc++) healedViews.add(readAfterHeal(stores.get(dc-1),dc,healedReads));
                View agreed=healedViews.get(1);
                for(View healed:healedViews) {
                    assertEquals(agreed,healed,"full authoritative state must agree after healing");assertEquals(3,healed.floor());assertEquals(4,healed.allocated());assertEquals(Set.of(4L),healed.entries().keySet());
                }
            } finally {
                Path file=Path.of("build/evidence/retention-healed","seed-"+seed+".json");Files.createDirectories(file.getParent());
                new com.fasterxml.jackson.databind.ObjectMapper().writerWithDefaultPrettyPrinter().writeValue(file.toFile(),healedReads);
                for(Retention.Store store:stores) store.close();
            }
        }
    }
    private View readAfterHeal(Retention.Store store,int dc,List<HealedRead> evidence) {
        for(int attempt=1;attempt<=3;attempt++) {
            try {
                View view=store.view();evidence.add(new HealedRead(dc,attempt,view,null));return view;
            } catch(Failure e) {
                evidence.add(new HealedRead(dc,attempt,null,e.code.name()));
                if(e.code!=Code.INDETERMINATE) throw e;
            }
        }
        throw new AssertionError("dc"+dc+" must return an authoritative healed view within three attempts");
    }
    private void command(String action) throws Exception {
        Process p=new ProcessBuilder("bash","scripts/three.sh",action).inheritIO().start();
        if(!p.waitFor(30,TimeUnit.SECONDS)) { p.destroyForcibly();fail("fault script timeout"); }
        assertEquals(0,p.exitValue());
    }
}
