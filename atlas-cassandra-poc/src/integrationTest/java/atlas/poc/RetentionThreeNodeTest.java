package atlas.poc;

import org.junit.jupiter.api.*;
import java.util.*;
import java.nio.file.*;
import java.util.concurrent.TimeUnit;
import com.fasterxml.jackson.databind.ObjectMapper;
import static atlas.poc.Retention.*;
import static atlas.poc.Transactions.*;
import static org.junit.jupiter.api.Assertions.*;

@Tag("three") @Tag("Retention")
class RetentionThreeNodeTest {
    @Test void majorityPrunesAndMinorityCannotReopenExpiredTicketAfterHealing() throws Exception {
        UUID subject=UUID.randomUUID(); var clock=new RetentionClock(); var events=new ArrayList<Map<String,Object>>();
        try(var minority=new RetentionCassandraStore(subject,RetentionContract.KEY,clock,"127.0.0.1",9042,"dc1","three");
            var majority=new RetentionCassandraStore(subject,RetentionContract.KEY,clock,"127.0.0.1",9142,"dc2","three")) {
            Request expired=majority.edit(Map.of(Group.ROYALTY,"2000")); Ticket old=majority.issue(expired); Receipt original=majority.commit(old,expired);
            clock.advance(LIFETIME_MILLIS/2);
            Request fresh=majority.edit(Map.of(Group.ROYALTY,"2500")); Ticket young=majority.issue(fresh); Receipt later=majority.commit(young,fresh);
            clock.advance(LIFETIME_MILLIS/2);
            try {
                command("partition");
                assertEquals(Code.INDETERMINATE,assertThrows(Failure.class,minority::compact).code);
                events.add(Map.of("event","minority-compaction","outcome","INDETERMINATE"));
                View pruned=majority.compact(); assertEquals(1,pruned.floor()); assertEquals(later.after(),pruned.snapshot());
                assertEquals(Set.of(young.sequence()),pruned.entries().keySet(),"old row must actually be pruned");
                command("counters");
                assertEquals(later,majority.commit(young,fresh));
                assertEquals(Code.REQUEST_TOO_OLD,assertThrows(Failure.class,()->majority.commit(old,expired)).code);
                events.add(Map.of("event","majority-compaction","floor",pruned.floor(),"retained",pruned.entries().size()));
            } finally { command("heal"); }
            command("ready");
            assertEquals(1,minority.view().floor());
            assertEquals(Set.of(young.sequence()),minority.view().entries().keySet());
            assertEquals(Code.REQUEST_TOO_OLD,assertThrows(Failure.class,()->minority.commit(old,expired)).code);
            assertEquals(later,minority.commit(young,fresh)); assertEquals(later.after(),minority.view().snapshot());
            events.add(Map.of("event","healed-expired-retry","outcome","REQUEST_TOO_OLD","original",original,"current",later));
        } finally {
            Files.createDirectories(Path.of("build/evidence"));
            new ObjectMapper().writerWithDefaultPrettyPrinter().writeValue(Path.of("build/evidence/retention-three.json").toFile(),events);
        }
    }
    private void command(String action) throws Exception {
        Process process=new ProcessBuilder("bash","scripts/three.sh",action).inheritIO().start();
        if(!process.waitFor(action.equals("ready")?120:30,TimeUnit.SECONDS)) { process.destroyForcibly(); fail("fault script timeout"); }
        assertEquals(0,process.exitValue());
    }
}
