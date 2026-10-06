package atlas.poc;

import org.junit.jupiter.api.*;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.TimeUnit;
import static atlas.poc.ArchiveFenceModel.*;
import static atlas.poc.PersistentArchiveRecovery.*;
import static atlas.poc.DraftProcessClient.*;
import static org.junit.jupiter.api.Assertions.*;

@Tag("phase")
class ArchivePhaseProcessTest {
    final ArchivePhaseContract contract=new ArchivePhaseContract() {boolean three(){return false;}};
    final Set<Long> pids=new HashSet<>();
    Map<?,?> worker(ArchivePhaseContract.Fixture f,String label,int port,String cut,int exit) throws Exception {
        Path output=f.dir.resolve(label+".json");
        Process p=new ProcessBuilder(Path.of(System.getProperty("java.home"),"bin","java").toString(),"-cp",System.getProperty("atlas.test.classpath"),
            ArchiveRecoveryWorker.class.getName(),f.subject.toString(),f.actor(0).toString(),f.objects.toString(),output.toString(),Integer.toString(port),cut)
            .redirectErrorStream(true).redirectOutput(f.dir.resolve(label+".log").toFile()).start();
        assertTrue(pids.add(p.pid()));
        try {assertTrue(p.waitFor(60,TimeUnit.SECONDS),"worker deadline");}finally {if(p.isAlive()){p.destroyForcibly();p.waitFor(10,TimeUnit.SECONDS);}}
        assertEquals(exit,p.exitValue());Path report=exit==86?output.resolveSibling(output.getFileName()+".halt"):output;
        var result=JSON.readValue(report.toFile(),Map.class);assertEquals(p.pid(),((Number)result.get("pid")).longValue());
        if(exit==86)assertFalse(Files.exists(output));return result;
    }
    // BEGIN ATLAS SCENARIO
    /**
     * Goal: Resume every archive recovery phase after a worker crash
     * Boundary: Halt fresh workers after pending request persistence, storage effect and local completion for all eight phases
     * Expected: Forty-eight distinct JVMs preserve phase identity and exact receipts; every history retains the edit and resumes authoring.
     */
    @org.junit.jupiter.api.DisplayName("AT-116 | Resume every archive recovery phase after a worker crash")
    // END ATLAS SCENARIO
    @Test void everyArchivePhaseRecoversAfterPersistedRequestAndEffectCrashes() throws Exception {
        for(Step target:PHASES)for(String cut:List.of("AFTER_PENDING","AFTER_EFFECT","AFTER_LOCAL"))try(var f=contract.new Fixture("process-"+target+"-"+cut)) {
            f.edit(2);f.init(0);while(PHASES.get(read(f.actor(0),f.subject).index())!=target)f.advance(0);
            int index=read(f.actor(0),f.subject).index();var beforeRoot=f.root.view();var beforeHot=f.hot.view();
            worker(f,"interrupted",9042,cut,86);Actor interrupted=read(f.actor(0),f.subject);
            var afterRoot=f.root.view();var afterHot=f.hot.view();var afterView=f.driver(0).view();
            Pending operation=cut.equals("AFTER_LOCAL")?interrupted.done().get(index).pending():interrupted.pending();
            if(!cut.equals("AFTER_PENDING")&&operation.request()!=null) {
                var observed=operation.store().equals("phase-authority")?afterRoot:afterHot;
                assertEquals(operation.request().next(),observed.state());
                assertEquals(new RecoveryJournal.Receipt(operation.request(),operation.request().expected(),operation.request().next()),observed.receipts().get(operation.request().operation()));
            }
            if(target==Step.COPY)assertEquals(!cut.equals("AFTER_PENDING"),afterView.archive().containsKey(operation.object().owner()));
            if(cut.equals("AFTER_LOCAL")) {
                assertEquals(index+1,interrupted.index());assertNull(interrupted.pending());
                // Replay the last exact receipt without advancing the actor to a new phase.
                Done last=interrupted.done().get(index);if(last.pending().request()!=null)assertEquals(last.result(),(last.pending().store().equals("phase-authority")?f.root:f.hot).apply(last.pending().request()));
                f.frame(0,target,"OK");
                // Fresh worker resumes the next phase, or observes completion after ACTIVATE.
                worker(f,"resumed",9042,"NONE",0);if(index<7)f.frame(0,PHASES.get(index+1),"OK");
            } else {
                assertEquals(index,interrupted.index());assertNotNull(interrupted.pending());
                if(cut.equals("AFTER_PENDING")) {assertEquals(beforeRoot,f.root.view());assertEquals(beforeHot,f.hot.view());}
                worker(f,"resumed",9042,"NONE",0);Actor resumed=read(f.actor(0),f.subject);
                assertEquals(interrupted.pending(),resumed.done().get(index).pending());f.frame(0,target,"OK");
            }
            f.complete(0);f.audit();
            save(f.dir.resolve("cut.json"),Map.of("phase",target,"cut",cut,"interrupted",interrupted,"finalActor",read(f.actor(0),f.subject),"beforeRoot",beforeRoot,"beforeHot",beforeHot,"afterRoot",afterRoot,"afterHot",afterHot,"afterView",afterView));
        }
        assertEquals(48,pids.size());save(Path.of("build/evidence/archive-phase-single/process-pids.json"),pids);
    }
    // BEGIN ATLAS SCENARIO
    /**
     * Goal: Recover persisted archive phase requests from actual Cassandra reply uncertainty
     * Boundary: Drop each of six mutation phases before send and after a decoded server response, then restart the worker from its disk checkpoint
     * Expected: Twenty-four fresh JVMs preserve exact pending requests, witness the wire cut and recover one immutable effect per phase.
     */
    @org.junit.jupiter.api.DisplayName("AT-117 | Recover persisted archive phase requests from actual Cassandra reply uncertainty")
    // END ATLAS SCENARIO
    @Test void archiveCoordinatorResolvesExactRequestsAfterRealWireLoss() throws Exception {
        for(Step target:List.of(Step.START,Step.FREEZE,Step.CERTIFY,Step.PRUNE,Step.INSTALL,Step.ACTIVATE))
            for(FrameProxy.Fault cut:List.of(FrameProxy.Fault.BEFORE_SEND,FrameProxy.Fault.AFTER_RESPONSE))try(var f=contract.new Fixture("wire-"+target+"-"+cut)) {
                f.edit(2);f.init(0);while(PHASES.get(read(f.actor(0),f.subject).index())!=target)f.advance(0);
                int index=read(f.actor(0),f.subject).index();String store=target==Step.START||target==Step.CERTIFY?"phase-authority":"phase-hot";
                var journal=store.equals("phase-authority")?f.root:f.hot;var before=journal.view();
                try(var proxy=new FrameProxy()) {
                    proxy.arm(cut,CassandraRecoveryJournal.marker(f.subject,store));
                    var failed=worker(f,"interrupted",proxy.port(),"NONE",75);assertTrue(failed.get("error").toString().contains("DriverTimeoutException"));
                    assertTrue(proxy.injected.await(1,TimeUnit.SECONDS));assertEquals(1,proxy.matchingRequests.get());
                    var witness=proxy.witness();assertNotNull(witness);assertEquals(cut,witness.fault());
                    assertEquals(cut==FrameProxy.Fault.AFTER_RESPONSE,witness.forwarded());assertEquals(cut==FrameProxy.Fault.AFTER_RESPONSE?8:-1,witness.responseOpcode());save(f.dir.resolve("wire.json"),witness);
                }
                Actor interrupted=read(f.actor(0),f.subject);assertEquals(index,interrupted.index());var request=interrupted.pending().request();
                var after=journal.view();if(cut==FrameProxy.Fault.BEFORE_SEND)assertEquals(before,after);
                else {assertEquals(request.next(),after.state());assertEquals(new RecoveryJournal.Receipt(request,request.expected(),request.next()),after.receipts().get(request.operation()));}
                worker(f,"resumed",9042,"NONE",0);assertEquals(interrupted.pending(),read(f.actor(0),f.subject).done().get(index).pending());
                f.frame(0,target,"OK");f.complete(0);f.audit();save(f.dir.resolve("cut.json"),Map.of("phase",target,"cut",cut,"before",before,"afterUncertainty",after,"interrupted",interrupted,"finalActor",read(f.actor(0),f.subject)));
            }
        assertEquals(24,pids.size());save(Path.of("build/evidence/archive-phase-single/wire-pids.json"),pids);
    }
}
