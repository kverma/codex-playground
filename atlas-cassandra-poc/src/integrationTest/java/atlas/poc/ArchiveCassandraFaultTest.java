package atlas.poc;
import org.junit.jupiter.api.*;
import java.util.*;
import java.util.concurrent.TimeUnit;
import static atlas.poc.ArchiveRecovery.*;
import static org.junit.jupiter.api.Assertions.*;
@Tag("fault")
class ArchiveCassandraFaultTest {
    // BEGIN ATLAS SCENARIO
    /**
     * Goal: Resume archive-backed cleanup after a real Cassandra process crash
     * Boundary: Keep verified archive facts in the external fixture, kill Cassandra before pruning and reconnect after restart
     * Expected: Accepted terms and slot rows survive; repeated pruning removes the rows safely. Remote archive and power-loss durability remain unproven.
     */
    @org.junit.jupiter.api.DisplayName("AT-068 | Resume archive-backed cleanup after a real Cassandra process crash")
    // END ATLAS SCENARIO
    @Test void serverCrashBetweenArchiveAndPruningPreservesTheOffer() throws Exception {
        var contract=new ArchiveCassandraTest();
        try(var h=contract.new Scenario("server-crash-before-prune")) {
            h.base();var expected=h.store.read().hot().head();h.seal();h.archive();h.store.close();
            try { VerifiedKill.kill(List.of("docker","compose"),"cassandra"); }
            finally {
                Process p=new ProcessBuilder("docker","compose","up","-d","--wait","--wait-timeout","300").inheritIO().start();
                if(!p.waitFor(330,TimeUnit.SECONDS)) { p.destroyForcibly();fail("Cassandra restart timeout"); }
                assertEquals(0,p.exitValue());
            }
            h.store=contract.open(h.subject,h.external,Broken.NONE,false);
            assertEquals(expected,h.store.read().hot().head());assertEquals(Set.of(1L,2L,3L),h.store.read().hot().rows().keySet());
            h.ok(Kind.PRUNE,3);h.ok(Kind.PRUNE,3);assertTrue(h.store.read().hot().rows().isEmpty());
            h.check(ArchiveRecoveryChecker.Verdict.VALID);
        }
    }
}
