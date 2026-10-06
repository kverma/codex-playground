package atlas.poc;

import org.junit.jupiter.api.Test;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.TimeUnit;
import static atlas.poc.ArchiveRecovery.*;
import static atlas.poc.Retention.*;
import static atlas.poc.DraftProcessClient.*;
import static atlas.poc.DurableArchiveServer.*;
import static atlas.poc.Transactions.Group.*;
import static org.junit.jupiter.api.Assertions.*;

class DurableArchiveProcessTest {
    final class Harness implements AutoCloseable {
        final UUID subject=UUID.randomUUID();
        final Transactions.Snapshot genesis=Transactions.initial();
        final Path folder,state;
        final boolean split;
        final String mode;
        final List<Map<String,Object>> processes=new ArrayList<>();
        final List<Frame> frames=new ArrayList<>();
        int step;
        int authorityPort=9042;
        Harness(String name) throws Exception { this(name,false); }
        Harness(String name,boolean split) throws Exception { this(name,split?"split":"combined"); }
        Harness(String name,String mode) throws Exception {
            this.mode=mode;this.split=!mode.equals("combined");folder=Path.of("build/evidence",mode.equals("authority")?"cassandra-root":split?"split-storage":"durable-server",name);
            Files.createDirectories(folder);state=folder.resolve("facts.json");
            Facts initial=Facts.capture(subject,new Storage(genesis),Map.of());
            if(split)SplitArchiveCheckpoint.publish(state,initial,"INIT",cut->{});
            else publish(state,initial,()->{},()->{});
            if(mode.equals("authority"))try(var a=new CassandraRootAuthority(subject)) { a.bootstrap(SplitArchiveCheckpoint.root(state,subject)); }
        }
        Input message(String action,Draft draft,Issued issued,String cut) {
            return new Input(subject,new Message(action,subject,action.equals("prepare")?Transactions.edit(UUID.randomUUID(),genesis,Map.of(ROYALTY,"2000")):null,draft,issued),null,cut);
        }
        Input command(Kind kind,long n,String name,Mode mode,String cut) {
            return new Input(subject,null,new Command(kind,n,null,name,mode,true),cut);
        }
        Path process(Input input,int expectedExit) throws Exception {
            int id=++step;Path request=folder.resolve(id+"-input.json"),result=folder.resolve(id+"-report.json");save(request,input);
            Path before=folder.resolve(id+"-before.bin");if(Files.exists(state))Files.copy(state,before,StandardCopyOption.REPLACE_EXISTING);
            Process p=new ProcessBuilder(Path.of(System.getProperty("java.home"),"bin","java").toString(),"-cp",
                System.getProperty("atlas.test.classpath"),DurableArchiveServer.class.getName(),state.toString(),request.toString(),result.toString(),mode,Integer.toString(authorityPort))
                .redirectErrorStream(true).redirectOutput(folder.resolve(id+"-server.log").toFile()).start();
            try { assertTrue(p.waitFor(55,TimeUnit.SECONDS),"server process must finish or hit the exact crash cut"); }
            finally { if(p.isAlive()) { p.destroyForcibly();p.waitFor(10,TimeUnit.SECONDS); } }
            processes.add(Map.of("step",id,"pid",p.pid(),"exit",p.exitValue(),"expectedExit",expectedExit,"cut",Objects.toString(input.cut(),"none")));
            assertNotEquals(ProcessHandle.current().pid(),p.pid());assertEquals(expectedExit,p.exitValue(),"inspect "+id+"-server.log");
            assertTrue(Files.exists(result),"a crash requires a saved boundary witness, not just any nonzero exit");
            assertEquals(p.pid(),JSON.readTree(result.toFile()).get("pid").asLong());
            if(Files.exists(state))Files.copy(state,folder.resolve(id+"-after.bin"),StandardCopyOption.REPLACE_EXISTING);
            return result;
        }
        Report run(Input in,String code) throws Exception {
            Report r=JSON.readValue(process(in,code.equals("OK")?0:2).toFile(),Report.class);
            assertEquals("REPLY",r.phase());assertEquals(code,r.reply().code());frames.addAll(r.frames());return r;
        }
        Report crash(Input in,boolean published) throws Exception {
            assertNotNull(in.cut());Report r=JSON.readValue(process(in,86).toFile(),Report.class);
            assertEquals(in.cut(),r.phase());assertNull(r.reply());
            if(published)frames.addAll(r.frames());
            return r;
        }
        Report cmd(Kind kind,long n,String code) throws Exception { return run(command(kind,n,null,Mode.NORMAL,null),code); }
        Hot actual() {
            try(var db=new ArchiveCassandraFixture(subject,new Storage(genesis),"127.0.0.1",9042,"dc1","single")) { return db.read().hot(); }
        }
        void check() throws Exception {
            var result=new ArchiveRecoveryChecker().check(genesis,frames);assertEquals(ArchiveRecoveryChecker.Verdict.VALID,result.verdict(),result.reason());
            save(folder.resolve("history.json"),new ArchiveRecoveryChecker.Evidence(0,genesis,frames,result));
            var replay=JSON.readValue(folder.resolve("history.json").toFile(),ArchiveRecoveryChecker.Evidence.class);
            assertEquals(result,new ArchiveRecoveryChecker().check(replay.genesis(),replay.frames()));
        }
        public void close() throws Exception {
            save(folder.resolve("processes.json"),processes);
            assertEquals(processes.size(),processes.stream().map(p->p.get("pid")).distinct().count());
        }
    }
    // BEGIN ATLAS SCENARIO
    /**
     * Goal: Recover the same offer edit after the server process dies
     * Boundary: Halt real server JVMs before and after binding/archive publication and after Cassandra installs, accepts or prunes an edit
     * Expected: Reload disk facts and actual Cassandra state; preserve the exact identity and receipt, block premature cleanup and recover the archived offer.
     */
    @org.junit.jupiter.api.DisplayName("AT-080 | Recover the same offer edit after the server process dies")
    // END ATLAS SCENARIO
    @Test void serverCrashesPreserveDraftIdentityReceiptAndArchiveRecovery() throws Exception {
        try(var h=new Harness("publication-cuts")) {
            h.run(h.command(Kind.SAVE,0,"old",Mode.NORMAL,null),"OK");
            Draft draft=h.run(h.message("prepare",null,null,null),"OK").reply().draft();
            byte[] before=Files.readAllBytes(h.state);
            Report staged=h.crash(h.message("issue",draft,null,"BIND_BEFORE_MOVE"),false);
            assertEquals(1,staged.facts().highWater());assertEquals(1,staged.facts().bindings().size());
            assertArrayEquals(before,Files.readAllBytes(h.state));assertEquals(0,h.actual().allocated());
            assertTrue(Files.size(h.state.resolveSibling("facts.json.pending"))>0,"forced unpublished bytes must actually exist");
            Report bound=h.crash(h.message("issue",draft,null,"BIND_AFTER_MOVE"),true);
            Issued issued=bound.facts().bindings().values().iterator().next();
            assertEquals(staged.facts().bindings(),bound.facts().bindings());assertEquals(1,load(h.state,h.subject).highWater());
            assertEquals(0,h.actual().allocated());
            h.crash(h.message("issue",draft,null,"INSTALL_AFTER_DB"),true);
            assertEquals(1,h.actual().allocated());assertNull(h.actual().rows().get(1L).receipt());
            assertEquals(issued,h.run(h.message("issue",draft,null,null),"OK").reply().issued());
            Report accepted=h.crash(h.message("commit",null,issued,"ACCEPT_AFTER_DB"),true);
            var receipt=accepted.hot().rows().get(1L).receipt();assertNotNull(receipt);
            assertEquals(receipt,h.actual().rows().get(1L).receipt());
            assertEquals(receipt,h.run(h.message("commit",null,issued,null),"OK").reply().receipt());
            assertEquals(draft.request().reads(),issued.request().reads());assertEquals(draft.request().updates(),issued.request().updates());
            h.cmd(Kind.CLOCK,issued.ticket().expiresAt(),"OK");h.cmd(Kind.SEAL,1,"OK");h.cmd(Kind.PUBLISH,1,"OK");
            before=Files.readAllBytes(h.state);
            Report copied=h.crash(h.command(Kind.COPY,1,null,Mode.NORMAL,"COPY_BEFORE_MOVE"),false);
            assertTrue(copied.facts().archive().get(1L).complete());assertArrayEquals(before,Files.readAllBytes(h.state));
            assertTrue(load(h.state,h.subject).archive().isEmpty());
            h.cmd(Kind.CERTIFY,1,"UNCOVERED");h.cmd(Kind.PRUNE,1,"UNCOVERED");assertEquals(Set.of(1L),h.actual().rows().keySet());
            h.crash(h.command(Kind.COPY,1,null,Mode.NORMAL,"COPY_AFTER_MOVE"),true);
            assertEquals(receipt,load(h.state,h.subject).archive().get(1L).item().receipt());
            h.crash(h.command(Kind.CERTIFY,1,null,Mode.NORMAL,"CERTIFY_AFTER_MOVE"),true);
            assertEquals(1,load(h.state,h.subject).certifiedFloor());
            h.crash(h.command(Kind.PRUNE,1,null,Mode.NORMAL,"PRUNE_AFTER_DB"),true);
            assertEquals(1,h.actual().floor());assertTrue(h.actual().rows().isEmpty());h.cmd(Kind.PRUNE,1,"OK");
            h.run(h.command(Kind.RESTORE,0,"old",Mode.NORMAL,null),"OK");assertTrue(h.actual().fenced());
            h.run(h.message("commit",null,issued,null),"FENCED");h.cmd(Kind.RECOVER,0,"OK");
            assertEquals(receipt.after(),h.actual().head());assertEquals(1,h.actual().floor());
            h.cmd(Kind.CLOCK,0,"OK");h.run(h.message("issue",draft,null,null),"REQUEST_TOO_OLD");
            h.run(h.message("commit",null,issued,null),"REQUEST_TOO_OLD");
            var nextTemplate=Transactions.edit(UUID.randomUUID(),h.actual().head(),Map.of(ROYALTY,"3000"));
            Draft next=h.run(new Input(h.subject,new Message("prepare",h.subject,nextTemplate,null,null),null,null),"OK").reply().draft();
            Issued nextIssued=h.run(h.message("issue",next,null,null),"OK").reply().issued();
            assertEquals(2,nextIssued.ticket().sequence());h.run(h.message("commit",null,nextIssued,null),"OK");
            assertEquals("3000",h.actual().head().value(ROYALTY));h.check();
            // Independent control: deleting the durable reservation from the trace must break INSTALL.
            var broken=new ArrayList<>(h.frames);broken.removeIf(f->f.command().kind()==Kind.RESERVE);
            var verdict=new ArchiveRecoveryChecker().check(h.genesis,broken);
            assertEquals(ArchiveRecoveryChecker.Verdict.INVALID,verdict.verdict());
            save(h.folder.resolve("mutant-forgot-reservation.json"),new ArchiveRecoveryChecker.Evidence(0,h.genesis,broken,verdict));
        }
    }
    // BEGIN ATLAS SCENARIO
    /**
     * Goal: Keep offer history safe when saved recovery facts are incomplete or damaged
     * Boundary: Restart with a partial archive, missing or truncated checkpoint, bad checksum, missing binding and contradictory archived content
     * Expected: Invalid checkpoints fail before opening Cassandra; incomplete coverage blocks pruning and contradictory history keeps restored authoring fenced.
     */
    @org.junit.jupiter.api.DisplayName("AT-081 | Keep offer history safe when saved recovery facts are incomplete or damaged")
    // END ATLAS SCENARIO
    @Test void damagedDurableFactsCannotAuthorizeCleanupOrRecovery() throws Exception {
        try(var h=new Harness("invalid-facts")) {
            h.run(h.command(Kind.SAVE,0,"old",Mode.NORMAL,null),"OK");
            Draft draft=h.run(h.message("prepare",null,null,null),"OK").reply().draft();
            Issued issued=h.run(h.message("issue",draft,null,null),"OK").reply().issued();
            var receipt=h.run(h.message("commit",null,issued,null),"OK").reply().receipt();
            h.cmd(Kind.CLOCK,issued.ticket().expiresAt(),"OK");h.cmd(Kind.SEAL,1,"OK");h.cmd(Kind.PUBLISH,1,"OK");
            h.run(h.command(Kind.COPY,1,null,Mode.PARTIAL,null),"OK");
            assertFalse(load(h.state,h.subject).archive().get(1L).complete());
            h.cmd(Kind.CERTIFY,1,"UNCOVERED");h.cmd(Kind.PRUNE,1,"UNCOVERED");
            Hot protectedHot=h.actual();assertEquals(receipt,protectedHot.rows().get(1L).receipt());
            byte[] trusted=Files.readAllBytes(h.state);Facts facts=load(h.state,h.subject);
            for(String fault:List.of("missing","truncated","checksum","missing-binding")) {
                switch(fault) {
                    case "missing" -> Files.delete(h.state);
                    case "truncated" -> Files.write(h.state,Arrays.copyOf(trusted,trusted.length/2));
                    case "checksum" -> { Envelope e=JSON.readValue(trusted,Envelope.class);save(h.state,new Envelope(1,e.payload(),"wrong")); }
                    case "missing-binding" -> Files.write(h.state,envelope(new Facts(1,facts.subject(),facts.genesis(),facts.now(),facts.highWater(),facts.certifiedFloor(),facts.grants(),facts.manifests(),facts.archive(),facts.backups(),Map.of())));
                    default -> throw new AssertionError();
                }
                Path failed=h.process(h.command(Kind.PRUNE,1,null,Mode.NORMAL,null),65);
                assertEquals("CHECKPOINT_INVALID",JSON.readTree(failed.toFile()).get("phase").asText());
                assertEquals(protectedHot,h.actual(),"invalid facts cannot mutate Cassandra: "+fault);
                Files.write(h.state,trusted); // Test restores known-good bytes, never a candidate fallback.
            }
            h.cmd(Kind.COPY,1,"OK");h.cmd(Kind.CERTIFY,1,"OK");
            h.run(h.command(Kind.DAMAGE,1,"corrupt",Mode.NORMAL,null),"OK");
            h.cmd(Kind.PRUNE,1,"UNCOVERED");assertEquals(protectedHot,h.actual());
            h.run(h.command(Kind.RESTORE,0,"old",Mode.NORMAL,null),"OK");
            h.cmd(Kind.RECOVER,0,"UNCOVERED");assertTrue(h.actual().fenced());
            h.run(h.message("commit",null,issued,null),"FENCED");h.check();
        }
    }
}

