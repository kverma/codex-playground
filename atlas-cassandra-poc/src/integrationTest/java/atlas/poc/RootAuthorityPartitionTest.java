package atlas.poc;

import org.junit.jupiter.api.*;
import com.datastax.oss.driver.api.core.*;
import com.datastax.oss.driver.api.core.servererrors.UnavailableException;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;
import static atlas.poc.DraftProcessClient.save;
import static atlas.poc.TraceAssertions.*;

@Tag("three") @Tag("Archive")
class RootAuthorityPartitionTest {
    private final Path folder=Path.of("build/evidence/root-authority-partition");
    private final List<Map<String,Object>> events=new ArrayList<>();
    private void command(String action) throws Exception {
        Files.createDirectories(folder);Path log=folder.resolve(String.format("%02d-%s.log",events.size(),action));
        Process p=new ProcessBuilder("bash","scripts/three.sh",action).redirectErrorStream(true).redirectOutput(log.toFile()).start();
        if(!p.waitFor(120,TimeUnit.SECONDS)) { p.destroyForcibly();p.waitFor(10,TimeUnit.SECONDS);fail("fault command timed out: "+action); }
        events.add(Map.of("event","command","action",action,"exit",p.exitValue(),"log",log.toString()));
        assertEquals(0,p.exitValue(),action+": "+Files.readString(log));
    }
    private void unavailable(String operation,org.junit.jupiter.api.function.Executable call) {
        DriverException failure=assertThrows(DriverException.class,call,"minority cannot provide an authoritative result");
        var causes=new ArrayList<Throwable>();
        if(failure instanceof AllNodesFailedException all) {
            assertEquals(1,all.getAllErrors().size(),"no fallback coordinator outside dc1");
            all.getAllErrors().forEach((node,errors)->{ assertEquals("dc1",node.getDatacenter());causes.addAll(errors); });
        } else causes.add(failure);
        assertFalse(causes.isEmpty());
        for(Throwable cause:causes) {
            var quorum=assertInstanceOf(UnavailableException.class,cause,"generic timeouts do not witness known quorum loss");
            assertEquals(2,quorum.getRequired());assertEquals(1,quorum.getAlive());
        }
        events.add(Map.of("event","minority-unavailable","operation",operation,"required",2,"alive",1,
            "causes",causes.stream().map(Throwable::toString).toList()));
    }
    private CassandraRootAuthority.Version read(CassandraRootAuthority authority,String label) throws Exception {
        DriverException last=null;
        for(int attempt=1;attempt<=3;attempt++) {
            try {
                var observed=authority.read();events.add(Map.of("event","read","label",label,"attempt",attempt,"observed",observed));return observed;
            } catch(DriverException e) {
                last=e;events.add(Map.of("event","read-unknown","label",label,"attempt",attempt,"cause",e.toString()));
                if(attempt<3)command("ready");
            }
        }
        throw new AssertionError("bounded authoritative recovery exhausted: "+label,last);
    }
    private void publish(CassandraRootAuthority authority,CassandraRootAuthority.Proposal proposal) throws Exception {
        DriverException last=null;
        for(int attempt=1;attempt<=3;attempt++) {
            try {
                boolean applied=authority.publish(proposal);var observed=authority.read();
                events.add(Map.of("event","majority-publication","attempt",attempt,"applied",applied,"proposal",proposal,"observed",observed));
                assertEquals(proposal.next(),observed,"exact proposal must resolve, even if its first ACK was lost");return;
            } catch(DriverException e) {
                last=e;events.add(Map.of("event","majority-publication-unknown","attempt",attempt,"proposal",proposal,"cause",e.toString()));
            }
        }
        throw new AssertionError("majority publication did not resolve in three exact attempts",last);
    }
    // BEGIN ATLAS SCENARIO
    /**
     * Goal: Keep an isolated DC from authorizing recovery while the majority advances
     * Boundary: Prove a two-way partition and one-live/two-down membership; attempt root reads and a write on the minority before healing
     * Expected: Minority reads and writes report explicit quorum loss, the majority publishes the exact proposal, every healed coordinator agrees, stale replay fails and the recovered DC can publish again.
     */
    @org.junit.jupiter.api.DisplayName("AT-090 | Keep an isolated DC from authorizing recovery while the majority advances")
    // END ATLAS SCENARIO
    @Test void isolatedAuthorityCannotReadOrPublishWhileMajorityAdvances() throws Exception {
        UUID subject=UUID.randomUUID();boolean partition=false;
        try(var minority=new CassandraRootAuthority(subject,9042,"dc1","three");
            var majority=new CassandraRootAuthority(subject,9142,"dc2","three");
            var third=new CassandraRootAuthority(subject,9242,"dc3","three")) {
            command("ready");
            minority.bootstrap(new SplitArchiveCheckpoint.Root(1,subject,"a".repeat(64),"b".repeat(64)));
            var initial=minority.read();assertEquals(initial,majority.read());assertEquals(initial,third.read());
            var stale=minority.propose(initial,new SplitArchiveCheckpoint.Root(1,subject,"c".repeat(64),"d".repeat(64)));
            var advancing=majority.propose(initial,new SplitArchiveCheckpoint.Root(1,subject,"e".repeat(64),"f".repeat(64)));
            events.add(Map.of("event","prepared","initial",initial,"minorityProposal",stale,"majorityProposal",advancing));
            try {
                partition=true;command("partition");command("isolated");
                unavailable("read-before-majority",()->minority.read());
                unavailable("publish",()->minority.publish(stale));
                publish(majority,advancing);
                assertEquals(advancing.next(),third.read());
                unavailable("read-after-majority",()->minority.read());
                command("isolated");command("counters");
            } finally { command("heal");command("ready");partition=false; }
            assertEquals(advancing.next(),read(minority,"healed-dc1"));
            assertEquals(advancing.next(),read(majority,"healed-dc2"));
            assertEquals(advancing.next(),read(third,"healed-dc3"));
            assertFalse(minority.publish(stale),"captured minority proposal remains stale after healing");
            assertEquals(advancing.next(),minority.read());
            assertEquals(CassandraRootAuthority.Resolution.UNKNOWN,minority.resolve(stale));
            // The formerly isolated coordinator must also recover write progress.
            var resumed=minority.propose(advancing.next(),initial.root());publish(minority,resumed);
            assertEquals(resumed.next(),read(minority,"resumed-dc1"));
            assertEquals(resumed.next(),read(majority,"resumed-dc2"));
            assertEquals(resumed.next(),read(third,"resumed-dc3"));
            events.add(Map.of("event","stale-rejected-and-authoring-resumed","final",resumed.next()));
        } finally {
            try { if(partition) { command("heal");command("ready"); } }
            finally { save(folder.resolve("events.json"),events); }
        }
    }
}
