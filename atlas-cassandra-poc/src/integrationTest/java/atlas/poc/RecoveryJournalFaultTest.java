package atlas.poc;

import org.junit.jupiter.api.*;
import java.io.*;
import java.nio.file.*;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.TimeUnit;
import static atlas.poc.RecoveryJournal.*;
import static atlas.poc.RecoveryJournalTest.*;
import static atlas.poc.DraftProcessClient.*;
import static org.junit.jupiter.api.Assertions.*;

@Tag("fault")
class RecoveryJournalFaultTest {
    private final Set<Long> pids=new HashSet<>();
    private RecoveryJournalWorker.Output worker(Path folder,RecoveryJournalWorker.Input input,int port,int exit,boolean halt,String label) throws Exception {
        Path request=folder.resolve(label+"-input.json"),output=folder.resolve(label+"-output.json");save(request,input);byte[] original=Files.readAllBytes(request);
        Process p=new ProcessBuilder(Path.of(System.getProperty("java.home"),"bin","java").toString(),"-cp",System.getProperty("atlas.test.classpath"),
            RecoveryJournalWorker.class.getName(),request.toString(),output.toString(),Integer.toString(port),halt?"halt":"normal")
            .redirectErrorStream(true).redirectOutput(folder.resolve(label+"-worker.log").toFile()).start();
        assertTrue(pids.add(p.pid()),"new process for every worker attempt");
        try { assertTrue(p.waitFor(55,TimeUnit.SECONDS),"bounded worker exit"); }
        finally { if(p.isAlive()) {p.destroyForcibly();p.waitFor(10,TimeUnit.SECONDS);} }
        assertEquals(exit,p.exitValue());assertArrayEquals(original,Files.readAllBytes(request));
        Path result=halt?output.resolveSibling(output.getFileName()+".halt"):output;
        var report=JSON.readValue(result.toFile(),RecoveryJournalWorker.Output.class);assertEquals(p.pid(),report.pid());
        if(halt)assertFalse(Files.exists(output),"normal response was never written");
        return report;
    }
    private void applied(FrameProxy.Witness witness) throws Exception {
        assertEquals(8,witness.responseOpcode());
        try(var in=new DataInputStream(new ByteArrayInputStream(Base64.getDecoder().decode(witness.responseBody())))) {
            assertEquals(2,in.readInt());int flags=in.readInt();assertEquals(0,flags&~1);assertEquals(1,in.readInt());
            in.skipNBytes(in.readUnsignedShort());in.skipNBytes(in.readUnsignedShort());
            assertEquals("[applied]",new String(in.readNBytes(in.readUnsignedShort()),java.nio.charset.StandardCharsets.UTF_8));
            assertEquals(4,in.readUnsignedShort());assertEquals(1,in.readInt());assertEquals(1,in.readInt());assertEquals(1,in.readUnsignedByte());assertEquals(0,in.available());
        }
    }
    // BEGIN ATLAS SCENARIO
    /**
     * Goal: Recover each handoff phase from a durable exact request
     * Boundary: Run start, freeze, publish and activate with lost sends, dropped applied replies and halted workers against separate Cassandra partitions
     * Expected: Twenty-four fresh worker processes cover twelve cuts; exact replay completes two handoffs, preserves receipts and rejects stale hot-state writes.
     */
    @org.junit.jupiter.api.DisplayName("AT-099 | Recover each handoff phase from a durable exact request")
    // END ATLAS SCENARIO
    @Test void interruptedRecoveryPhasesReplayExactJournaledEffects() throws Exception {
        for(var step:List.of(RecoveryFenceModel.Step.START,RecoveryFenceModel.Step.FREEZE,RecoveryFenceModel.Step.PUBLISH,RecoveryFenceModel.Step.ACTIVATE))
            for(String cut:List.of("BEFORE_SEND","AFTER_RESPONSE","HALT"))run(step,cut);
        assertEquals(24,pids.size(),"one interrupted worker and one new recovery worker per cut");
        save(Path.of("build/evidence/recovery-journal-wire/processes.json"),pids);
    }
    private void run(RecoveryFenceModel.Step target,String cut) throws Exception {
        UUID subject=UUID.randomUUID();Path folder=Path.of("build/evidence/recovery-journal-wire",target+"-"+cut);Files.createDirectories(folder);
        var model=new RecoveryFenceModel();var frames=new ArrayList<RecoveryFenceModel.Frame>();
        State rootInitial=state(JSON.writeValueAsString(model.view().root())),hotInitial=state(JSON.writeValueAsString(model.view().hot()));
        var rootEvents=new ArrayList<Event>();var hotEvents=new ArrayList<Event>();var requests=new ArrayList<RecoveryJournalWorker.Input>();
        try(var root=new CassandraRecoveryJournal(subject,"authority",9042,"dc1","single",Duration.ofSeconds(20));
            var hot=new CassandraRecoveryJournal(subject,"hot",9042,"dc1","single",Duration.ofSeconds(20))) {
            root.bootstrap(rootInitial);hot.bootstrap(hotInitial);Request stale=null;
            var commands=new ArrayList<>(List.of(new RecoveryFenceModel.Command(2,RecoveryFenceModel.Step.READ),new RecoveryFenceModel.Command(2,RecoveryFenceModel.Step.WRITE)));
            for(int actor=0;actor<2;actor++)for(var step:List.of(RecoveryFenceModel.Step.START,RecoveryFenceModel.Step.FREEZE,RecoveryFenceModel.Step.PUBLISH,RecoveryFenceModel.Step.ACTIVATE))commands.add(new RecoveryFenceModel.Command(actor,step));
            for(var command:commands) {
                var expected=model.execute(command);assertEquals("OK",expected.outcome());
                if(command.step()!=RecoveryFenceModel.Step.READ) {
                    boolean authority=command.step()==RecoveryFenceModel.Step.START||command.step()==RecoveryFenceModel.Step.PUBLISH;
                    var store=authority?root:hot;var events=authority?rootEvents:hotEvents;
                    Request proposal=request(store.view().state(),JSON.writeValueAsString(authority?expected.observed().root():expected.observed().hot()));
                    var input=new RecoveryJournalWorker.Input(subject,authority?"authority":"hot",proposal);requests.add(input);
                    if(command.actor()==0&&command.step()==target) {
                        View before=store.view();boolean effected=!cut.equals("BEFORE_SEND");
                        if(cut.equals("HALT")) {
                            var halted=worker(folder,input,9042,86,true,"interrupted");assertEquals("OK",halted.result().code());
                            assertEquals("AFTER_EFFECT_BEFORE_REPLY",halted.error());
                        } else {
                            try(var proxy=new FrameProxy()) {
                                var fault=FrameProxy.Fault.valueOf(cut);proxy.arm(fault,CassandraRecoveryJournal.marker(subject,input.store()));
                                var failed=worker(folder,input,proxy.port(),75,false,"interrupted");assertNull(failed.result());assertTrue(failed.error().contains("DriverTimeoutException"));
                                assertTrue(proxy.injected.await(1,TimeUnit.SECONDS));assertEquals(1,proxy.matchingRequests.get());var w=proxy.witness();assertNotNull(w);
                                assertEquals(fault,w.fault());assertTrue(w.query().contains(CassandraRecoveryJournal.marker(subject,input.store())));
                                if(effected) { assertTrue(w.forwarded());applied(w); } else {assertFalse(w.forwarded());assertEquals(-1,w.responseOpcode());}
                                save(folder.resolve("wire.json"),w);
                            }
                        }
                        View observed=store.view();
                        if(effected) {
                            assertEquals(proposal.next(),observed.state());Receipt receipt=observed.receipts().get(proposal.operation());assertNotNull(receipt);
                            events.add(new Event(proposal,new Result("OK",receipt),observed));
                        } else assertEquals(before,observed);
                        var resumed=worker(folder,input,9042,0,false,"resumed");assertEquals("OK",resumed.result().code());
                        events.add(new Event(proposal,resumed.result(),store.view()));
                        assertEquals(new Receipt(proposal,proposal.expected(),proposal.next()),resumed.result().receipt());
                        save(folder.resolve("cut.json"),Map.of("phase",target,"cut",cut,"before",before,"afterUncertainty",observed,"afterRecovery",store.view()));
                    } else assertEquals("OK",apply(store,proposal,events).code());
                    if(command.step()==RecoveryFenceModel.Step.WRITE)stale=request(hot.view().state(),"STALE_OFFER_WRITE");
                }
                var actual=new RecoveryFenceModel.View(JSON.readValue(root.view().state().value(),RecoveryFenceModel.Root.class),JSON.readValue(hot.view().state().value(),RecoveryFenceModel.Hot.class));
                frames.add(new RecoveryFenceModel.Frame(command,"OK",actual));assertEquals(expected.observed(),actual);
            }
            View rootFinal=root.view(),hotFinal=hot.view();
            for(var input:requests)assertEquals("OK",apply(input.store().equals("authority")?root:hot,input.request(),input.store().equals("authority")?rootEvents:hotEvents).code());
            assertEquals("CONFLICT",apply(hot,stale,hotEvents).code());assertEquals(rootFinal,root.view());assertEquals(hotFinal,hot.view());
            var check=new RecoveryFenceChecker().check(frames);assertEquals(RecoveryFenceChecker.Verdict.VALID,check.verdict());
            assertTrue(RecoveryJournal.check(rootInitial,rootEvents).valid());assertTrue(RecoveryJournal.check(hotInitial,hotEvents).valid());
            save(folder.resolve("authority-history.json"),new RecoveryJournalTest.Trace(rootInitial,rootEvents,RecoveryJournal.check(rootInitial,rootEvents)));
            save(folder.resolve("hot-history.json"),new RecoveryJournalTest.Trace(hotInitial,hotEvents,RecoveryJournal.check(hotInitial,hotEvents)));
            save(folder.resolve("handoff.json"),Map.of("frames",frames,"result",check,"finalRoot",rootFinal,"finalHot",hotFinal));
        }
    }
}
