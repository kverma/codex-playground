package atlas.poc;

import org.junit.jupiter.api.*;
import java.time.Duration;
import java.util.UUID;
import java.util.concurrent.*;
import static atlas.poc.Protocol.*;
import static org.junit.jupiter.api.Assertions.*;

@Tag("fault")
class FaultTest {
    // BEGIN ATLAS SCENARIO
    /**
     * Goal: Safely retry an offer edit that never reached Cassandra
     * Boundary: Intercept and drop the actual batch frame before it reaches the server
     * Expected: Observe unchanged state, then accept the exact retry once.
     */
    @org.junit.jupiter.api.DisplayName("AT-050 | Safely retry an offer edit that never reached Cassandra")
    // END ATLAS SCENARIO
    @Test void requestLostBeforeSendLeavesHeadUnchangedAndExactRetryCommits() throws Exception {
        UUID subject = UUID.randomUUID();
        try (FrameProxy proxy = new FrameProxy();
             Store store = new CassandraStore(subject,"127.0.0.1",proxy.port(),"dc1",false,Duration.ofSeconds(3))) {
            Head before = store.read();
            Request request = new Request(UUID.randomUUID(),before.token(),new Intent(600,true));
            proxy.arm(FrameProxy.Fault.BEFORE_SEND);
            assertThrows(Indeterminate.class, () -> store.commit(request));
            assertTrue(proxy.injected.await(1,TimeUnit.SECONDS),"must intercept the actual batch frame");
            try (Store recovered = new CassandraStore(subject)) {
                assertEquals(before,recovered.read());
                Receipt result = recovered.commit(request);
                assertEquals(result,recovered.commit(request));
            }
        }
    }
    // BEGIN ATLAS SCENARIO
    /**
     * Goal: Recover an edit accepted before its reply was lost
     * Boundary: Drop an actual server response, verify acceptance, kill Cassandra and restart it
     * Expected: Recover the same receipt; later edits do not alter the original retry result.
     */
    @org.junit.jupiter.api.DisplayName("AT-051 | Recover an edit accepted before its reply was lost")
    // END ATLAS SCENARIO
    @Test void committedResponseLostThenCoordinatorKilledResolvesOriginalReceipt() throws Exception {
        UUID subject = UUID.randomUUID();
        Request request;
        Head accepted;
        try (FrameProxy proxy = new FrameProxy();
             Store store = new CassandraStore(subject,"127.0.0.1",proxy.port(),"dc1",false,Duration.ofSeconds(3))) {
            request = new Request(UUID.randomUUID(),store.read().token(),new Intent(600,true));
            proxy.arm(FrameProxy.Fault.AFTER_RESPONSE);
            assertThrows(Indeterminate.class, () -> store.commit(request));
            assertTrue(proxy.injected.await(1,TimeUnit.SECONDS),"must drop a real server response");
            try (Store observer = new CassandraStore(subject)) { accepted = observer.read(); }
            assertEquals(request.intent(),accepted.intent());
        }
        // SIGKILL (not graceful stop) tests commit-log recovery after a committed lost response.
        try { VerifiedKill.kill(java.util.List.of("docker","compose"),"cassandra"); }
        finally { compose("up","-d","--wait","--wait-timeout","300"); }
        try (Store recovered = new CassandraStore(subject)) {
            Receipt original = recovered.commit(request);
            assertEquals(accepted.token(),original.token());
            recovered.commit(new Request(UUID.randomUUID(),recovered.read().token(),new Intent(700,true)));
            assertEquals(original,recovered.commit(request));
        }
    }
    private static void compose(String... args) throws Exception {
        var command = new java.util.ArrayList<String>();
        command.add("docker"); command.add("compose"); command.addAll(java.util.List.of(args));
        Process p = new ProcessBuilder(command).inheritIO().start();
        if (!p.waitFor(330,TimeUnit.SECONDS)) { p.destroyForcibly(); fail("Docker timeout"); }
        assertEquals(0,p.exitValue(),"Docker fault command");
    }
}
