package atlas.poc;

import org.junit.jupiter.api.*;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.TimeUnit;
import static atlas.poc.Protocol.*;
import static atlas.poc.TraceAssertions.*;

/** Linux Docker host with nine healthy nodes; never included in the single-node grade. */
@Tag("fullHa")
class FullHaTest {
    // BEGIN ATLAS SCENARIO
    /**
     * Goal: Explore availability with three replicas in each of three DCs
     * Boundary: The separate nine-node scaffold removes an ingress DC and tests a three-replica minority
     * Expected: Its assertions require surviving-majority progress and minority rejection; this scaffold is not part of the qualified hosted run.
     */
    @org.junit.jupiter.api.DisplayName("AT-061 | Explore availability with three replicas in each of three DCs")
    // END ATLAS SCENARIO
    @Test void survivesIngressDcLossAndRejectsThreeReplicaMinority() throws Exception {
        UUID subject = UUID.randomUUID();
        Request first;
        Receipt acknowledged;
        try (Store ingress = connect(subject,"dc1",9042)) {
            first = new Request(UUID.randomUUID(),ingress.read().token(),new Intent(600,false));
            acknowledged = ingress.commit(first);
        }
        try (Store failover = connect(subject,"dc2",9142)) {
            try {
                compose("kill","-s","SIGKILL","dc1n1","dc1n2","dc1n3");
                assertEquals(acknowledged,failover.commit(first),"ACK survives whole ingress DC loss");
                Receipt majority = failover.commit(new Request(UUID.randomUUID(),failover.read().token(),new Intent(600,true)));
                compose("kill","-s","SIGKILL","dc3n1","dc3n2","dc3n3");
                Request minority = new Request(UUID.randomUUID(),majority.token(),new Intent(700,true));
                assertThrows(Indeterminate.class, () -> failover.commit(minority),"3/9 cannot acknowledge authoring");
            } finally {
                compose("up","-d","--wait","--wait-timeout","300");
            }
        }
        try (Store recovered = connect(subject,"dc3",9242)) {
            assertEquals(acknowledged,recovered.commit(first),"original receipt survives recovery and later edits");
            assertEquals(new Intent(600,true),recovered.read().intent(),"minority must not overwrite majority state");
        }
    }
    private Store connect(UUID subject,String dc,int port) {
        return new CassandraStore(subject,"127.0.0.1",port,dc,true,Duration.ofSeconds(30));
    }
    private void compose(String... args) throws Exception {
        var command = new ArrayList<>(List.of("docker","compose","-f","compose.ha.yaml"));
        command.addAll(List.of(args));
        Process p = new ProcessBuilder(command).inheritIO().start();
        if (!p.waitFor(330,TimeUnit.SECONDS)) { p.destroyForcibly(); fail("Docker timeout"); }
        assertEquals(0,p.exitValue());
    }
}
