package atlas.poc;

import org.junit.jupiter.api.*;
import java.nio.file.*;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import static atlas.poc.Protocol.*;
import static org.junit.jupiter.api.Assertions.*;

@Tag("three")
class ThreeNodeTest {
    private final Path history = Path.of("build","evidence","three-history.jsonl");
    private synchronized void record(String event,Object detail) throws Exception {
        Files.createDirectories(history.getParent());
        String escaped = detail.toString().replace("\\","\\\\").replace("\"","\\\"").replace("\n","\\n").replace("\r","\\r");
        Files.writeString(history,"{\"time\":\""+Instant.now()+"\",\"event\":\""+event+"\",\"detail\":\""+escaped+"\"}\n",StandardOpenOption.CREATE,StandardOpenOption.APPEND);
    }
    private Store connect(UUID subject,int dc) {
        return new CassandraStore(subject,"127.0.0.1",9042+(dc-1)*100,"dc"+dc,"three",Duration.ofSeconds(20));
    }
    private Receipt edit(Store store,int cents) throws Exception {
        Request request = new Request(UUID.randomUUID(),store.read().token(),new Intent(cents,true));
        record("invoke",request);
        Receipt receipt = store.commit(request); record("ack",receipt); return receipt;
    }
    private void command(String... args) throws Exception {
        record("fault-command",Arrays.toString(args));
        Process p = new ProcessBuilder(args).redirectErrorStream(true).start();
        try (var pool=Executors.newVirtualThreadPerTaskExecutor()) {
            var output=pool.submit(() -> new String(p.getInputStream().readAllBytes(),java.nio.charset.StandardCharsets.UTF_8));
            if (!p.waitFor(330,TimeUnit.SECONDS)) { p.destroyForcibly(); fail("command timeout"); }
            record("command-output",output.get(10,TimeUnit.SECONDS));
            assertEquals(0,p.exitValue(),Arrays.toString(args));
        }
    }
    private void compose(String... args) throws Exception {
        var list=new ArrayList<>(List.of("docker","compose","-f","compose.three.yaml"));
        list.addAll(List.of(args)); command(list.toArray(String[]::new));
    }
    @Test @Tag("Partition") void majorityProgressMinorityRejectionAndHeal() throws Exception {
        UUID subject=UUID.randomUUID();
        try (Store minority=connect(subject,1); Store majority=connect(subject,2)) {
            Head before=minority.read();
            Request rejected=new Request(UUID.randomUUID(),before.token(),new Intent(900,true));
            try {
                command("bash","scripts/three.sh","partition");
                Receipt accepted=edit(majority,600);
                record("minority-invoke",rejected);
                assertThrows(Indeterminate.class,()->minority.commit(rejected));
                record("minority-indeterminate",rejected.operation());
                assertEquals(accepted.token(),majority.read().token());
                command("bash","scripts/three.sh","counters");
            } finally { command("bash","scripts/three.sh","heal"); }
            assertEquals(new Intent(600,true),minority.read().intent());
            assertThrows(Conflict.class,()->minority.commit(rejected));
            record("partition-pass",subject);
        }
    }
    @Test @Tag("CoordinatorCrash") void killAfterObservedBatchSendThenResolveOnOtherDc() throws Exception {
        UUID subject=UUID.randomUUID();
        try (Store survivor=connect(subject,2); FrameProxy proxy=new FrameProxy();
             Store origin=new CassandraStore(subject,"127.0.0.1",proxy.port(),"dc1","three",Duration.ofSeconds(10));
             var pool=Executors.newVirtualThreadPerTaskExecutor()) {
            Request request=new Request(UUID.randomUUID(),origin.read().token(),new Intent(650,true));
            record("crash-invoke",request);
            proxy.arm(FrameProxy.Fault.AFTER_SEND);
            var pending=pool.submit(()->assertThrows(Indeterminate.class,()->origin.commit(request)));
            assertTrue(proxy.injected.await(20,TimeUnit.SECONDS),"must observe actual batch send");
            try {
                compose("kill","-s","SIGKILL","dc1");
                pending.get(30,TimeUnit.SECONDS);
                Receipt resolved=survivor.commit(request); record("resolved",resolved);
                edit(survivor,700);
                assertEquals(resolved,survivor.commit(request));
            } finally { compose("up","-d","--wait","--wait-timeout","300","dc1"); }
            record("crash-pass",subject);
        }
    }
    @Test @Tag("Repair") void repairRejoinedReplicaWhileAuthoringContinues() throws Exception {
        UUID subject=UUID.randomUUID();
        try (Store writer=connect(subject,2)) {
            Receipt finalReceipt;
            try {
                compose("kill","-s","SIGKILL","dc1");
                for(int i=0;i<5;i++) edit(writer,700+i);
                compose("up","-d","--wait","--wait-timeout","300","dc1");
                try(var pool=Executors.newVirtualThreadPerTaskExecutor()) {
                    var repair=pool.submit(()->{ compose("exec","-T","dc1","nodetool","repair","-full","atlas_poc"); return true; });
                    finalReceipt=edit(writer,800);
                    for(int i=1;i<6;i++) finalReceipt=edit(writer,800+i);
                    assertTrue(repair.get(300,TimeUnit.SECONDS));
                }
                // A final repair gives a definite convergence point after concurrent traffic.
                compose("exec","-T","dc1","nodetool","repair","-full","atlas_poc");
                String cql="SELECT commit_token,cents FROM atlas_poc.subject WHERE subject="+subject+" AND row='HEAD';";
                var result=new ProcessBuilder("docker","compose","-f","compose.three.yaml","exec","-T","dc1","cqlsh","-e","CONSISTENCY LOCAL_ONE; "+cql).redirectErrorStream(true).start();
                String output=new String(result.getInputStream().readAllBytes(),java.nio.charset.StandardCharsets.UTF_8);
                assertTrue(result.waitFor(30,TimeUnit.SECONDS)); assertEquals(0,result.exitValue());
                record("repaired-local-read",output);
                assertTrue(output.contains(finalReceipt.token().toString()),"LOCAL_ONE on RF1/DC verifies repaired replica itself");
                record("repair-pass",subject);
            } finally { compose("up","-d","--wait","--wait-timeout","300","dc1"); }
        }
    }
}
