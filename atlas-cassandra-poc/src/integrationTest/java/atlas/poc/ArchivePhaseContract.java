package atlas.poc;

import org.junit.jupiter.api.Test;
import java.nio.file.*;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;
import static atlas.poc.ArchiveFenceModel.*;
import static atlas.poc.PersistentArchiveRecovery.*;
import static atlas.poc.DraftProcessClient.*;
import static org.junit.jupiter.api.Assertions.*;

abstract class ArchivePhaseContract {
    abstract boolean three();
    String folder() {return three()?"archive-phase-three":"archive-phase-single";}
    final class Fixture implements AutoCloseable {
        final UUID subject=UUID.randomUUID();final Path dir,objects;
        final CassandraRecoveryJournal root,hot,peerRoot,peerHot;
        final RecoveryJournal.State rootGenesis=state(new Root(0,null));
        final RecoveryJournal.State hotGenesis=state(new Hot(0,0,true,0,500,0,null,List.of()));
        final List<Frame> frames=new ArrayList<>();
        Fixture(String name) throws Exception {
            dir=Path.of("build/evidence",folder(),name);Files.createDirectories(dir);objects=dir.resolve("objects");
            String topology=three()?"three":"single";int peer=three()?9142:9042;String dc=three()?"dc2":"dc1";
            root=new CassandraRecoveryJournal(subject,"phase-authority",9042,"dc1",topology,Duration.ofSeconds(20));
            hot=new CassandraRecoveryJournal(subject,"phase-hot",9042,"dc1",topology,Duration.ofSeconds(20));
            peerRoot=new CassandraRecoveryJournal(subject,"phase-authority",peer,dc,topology,Duration.ofSeconds(20));
            peerHot=new CassandraRecoveryJournal(subject,"phase-hot",peer,dc,topology,Duration.ofSeconds(20));
            root.bootstrap(rootGenesis);hot.bootstrap(hotGenesis);
        }
        Path actor(int id) {return dir.resolve("actor-"+id+".json");}
        PersistentArchiveRecovery driver(int id) {return new PersistentArchiveRecovery(subject,actor(id),objects,id%2==0?root:peerRoot,id%2==0?hot:peerHot);}
        void init(int id) throws Exception {initialize(actor(id),subject,id);}
        void frame(int actor,Step step,String outcome) throws Exception {frames.add(new Frame(new Command(actor,step),outcome,driver(actor).view()));}
        void advance(int id) throws Exception {
            Actor before=read(actor(id),subject);Step step=PHASES.get(before.index());driver(id).advance(p->{});frame(id,step,"OK");
        }
        void complete(int id) throws Exception {while(read(actor(id),subject).index()<8)advance(id);}
        RecoveryJournal.Request edit(int id) throws Exception {
            var before=hot.view().state();Hot h=hot(before);assertTrue(h.active());frame(id,Step.READ,"OK");
            var rows=new ArrayList<>(h.rows());rows.add(new ArchiveFenceModel.Receipt(h.sequence()+1,id,h.cents(),400+id));
            var request=new RecoveryJournal.Request(UUID.randomUUID(),before,state(new Hot(h.epoch(),h.generation()+1,true,h.sequence()+1,400+id,h.floor(),h.base(),rows)));
            assertEquals("OK",hot.apply(request).code());frame(id,Step.WRITE,"OK");return request;
        }
        void audit() throws Exception {
            var result=new ArchiveFenceChecker(32).check(frames);assertEquals(ArchiveFenceChecker.Verdict.VALID,result.verdict(),result.toString());
            assertEquals(root.view(),peerRoot.view());assertEquals(hot.view(),peerHot.view());
            save(dir.resolve("history.json"),Map.of("subject",subject,"frames",frames,"result",result,"rootGenesis",rootGenesis,"hotGenesis",hotGenesis,"root",root.view(),"hot",hot.view()));
        }
        public void close() {peerHot.close();peerRoot.close();hot.close();root.close();}
    }
    static void pause(String point) {if(point.equals("AFTER_PENDING"))throw new IllegalStateException("TEST_PAUSE");}

    // BEGIN ATLAS SCENARIO
    /**
     * Goal: Recover archived and newer offer edits using persisted phase proofs
     * Boundary: Run two complete eight-phase handoffs through separate Cassandra partitions and disk archives, with a fresh driver each phase and another edit between handoffs
     * Expected: Both receipts survive, authoring resumes with fresh guards, delayed edits reject and historical phase requests cannot rewind state.
     */
    @org.junit.jupiter.api.DisplayName("AT-110 | Recover archived and newer offer edits using persisted phase proofs")
    // END ATLAS SCENARIO
    @Test void persistentArchiveCoordinatorPreservesPrefixAndTailAcrossHandoffs() throws Exception {
        try(var f=new Fixture("prefix-tail")) {
            f.edit(2);var old=f.hot.view().state();f.init(0);f.complete(0);
            assertEquals("CONFLICT",f.hot.apply(new RecoveryJournal.Request(UUID.randomUUID(),old,state("STALE"))).code());
            f.edit(3);f.init(1);f.complete(1);f.audit();
            var h=f.driver(1).view().hot();assertTrue(h.active());assertEquals(2,h.floor());assertEquals(403,h.cents());
            assertEquals(2,f.driver(1).load(2).receipts().size());assertTrue(h.rows().isEmpty());
            var before=f.hot.view();for(int id:List.of(0,1))for(Done done:read(f.actor(id),f.subject).done())if(done.pending().request()!=null) {
                var store=done.pending().store().equals("phase-authority")?f.peerRoot:f.peerHot;
                assertEquals(done.result(),store.apply(done.pending().request()));
            }
            assertEquals(before,f.hot.view());
        }
    }
    // BEGIN ATLAS SCENARIO
    /**
     * Goal: Stop an old recovery worker after another owner finishes
     * Boundary: Persist certification, pruning, installation or activation requests, complete a newer recovery through another client, then resume the old exact request
     * Expected: Every old pending mutation conflicts without changing its saved bytes or the newer complete state.
     */
    @org.junit.jupiter.api.DisplayName("AT-111 | Stop an old recovery worker after another owner finishes")
    // END ATLAS SCENARIO
    @Test void supersededPersistedPhasesCannotRefreshTheirCapturedGuards() throws Exception {
        for(Step target:List.of(Step.CERTIFY,Step.PRUNE,Step.INSTALL,Step.ACTIVATE))try(var f=new Fixture("stale-"+target)) {
            f.edit(2);f.init(0);
            while(PHASES.get(read(f.actor(0),f.subject).index())!=target)f.advance(0);
            assertThrows(IllegalStateException.class,()->f.driver(0).advance(ArchivePhaseContract::pause));
            byte[] original=Files.readAllBytes(f.actor(0));f.init(1);f.complete(1);
            var beforeRoot=f.root.view();var beforeHot=f.hot.view();
            var failure=assertThrows(IllegalStateException.class,()->f.driver(0).advance(p->{}));assertTrue(failure.getMessage().startsWith("PHASE_CONFLICT"));
            assertArrayEquals(original,Files.readAllBytes(f.actor(0)));assertEquals(beforeRoot,f.root.view());assertEquals(beforeHot,f.hot.view());
            f.frame(0,target,"STALE");f.audit();save(f.dir.resolve("rejected.json"),Map.of("phase",target,"error",failure.toString(),"actor",read(f.actor(0),f.subject)));
        }
    }
    // BEGIN ATLAS SCENARIO
    /**
     * Goal: Keep uncertain recovery files from authorizing cleanup
     * Boundary: Remove or corrupt the actor checkpoint and archive file before cleanup, then restore the exact saved bytes
     * Expected: Cleanup stays blocked with unchanged Cassandra state and request identity; exact restored proof permits bounded progress.
     */
    @org.junit.jupiter.api.DisplayName("AT-112 | Keep uncertain recovery files from authorizing cleanup")
    // END ATLAS SCENARIO
    @Test void missingOrCorruptRecoveryProofsBlockCleanupWithoutReminting() throws Exception {
        try(var f=new Fixture("proof-failures")) {
            f.edit(2);f.init(0);while(read(f.actor(0),f.subject).index()<5)f.advance(0);
            var before=f.hot.view();byte[] actor=Files.readAllBytes(f.actor(0));Path object=f.driver(0).object(1);byte[] archive=Files.readAllBytes(object);
            Files.writeString(f.actor(0),"{broken");assertThrows(Exception.class,()->f.driver(0).advance(p->{}));assertEquals(before,f.hot.view());
            Envelope envelope=JSON.readValue(actor,Envelope.class);
            Files.write(f.actor(0),JSON.writeValueAsBytes(new Envelope(envelope.payload()+" ",envelope.digest())));
            assertEquals("ACTOR_CHECKSUM",assertThrows(IllegalArgumentException.class,()->f.driver(0).advance(p->{})).getMessage());assertEquals(before,f.hot.view());
            Actor decoded=JSON.readValue(envelope.payload(),Actor.class);
            Files.write(f.actor(0),encode(new Actor(1,UUID.randomUUID(),decoded.actor(),decoded.index(),decoded.facts(),decoded.pending(),decoded.done())));
            assertEquals("ACTOR_BINDING",assertThrows(IllegalArgumentException.class,()->f.driver(0).advance(p->{})).getMessage());assertEquals(before,f.hot.view());
            Files.write(f.actor(0).resolveSibling(f.actor(0).getFileName()+".pending"),actor);
            Files.delete(f.actor(0));assertThrows(Exception.class,()->f.driver(0).advance(p->{}));assertEquals(before,f.hot.view());
            SplitArchiveCheckpoint.atomic(f.actor(0),actor);
            assertThrows(IllegalStateException.class,()->f.driver(0).advance(ArchivePhaseContract::pause));byte[] pending=Files.readAllBytes(f.actor(0));Actor planned=read(f.actor(0),f.subject);
            Files.delete(object);assertThrows(Exception.class,()->f.driver(0).advance(p->{}));assertEquals(before,f.hot.view());assertArrayEquals(pending,Files.readAllBytes(f.actor(0)));
            Files.writeString(object,"{broken");assertThrows(Exception.class,()->f.driver(0).advance(p->{}));assertEquals(before,f.hot.view());assertArrayEquals(pending,Files.readAllBytes(f.actor(0)));
            Archive valid=JSON.readValue(archive,Archive.class);
            Files.write(object,JSON.writeValueAsBytes(new Archive(valid.owner(),valid.fence(),valid.sequence(),valid.cents()+1,valid.receipts())));
            assertEquals("ARCHIVE_PROOF_UNAVAILABLE",assertThrows(IllegalArgumentException.class,()->f.driver(0).advance(p->{})).getMessage());assertEquals(before,f.hot.view());assertArrayEquals(pending,Files.readAllBytes(f.actor(0)));
            Files.delete(object);immutable(object,archive);f.complete(0);f.audit();
            assertEquals(planned.pending(),read(f.actor(0),f.subject).done().get(5).pending());
            save(f.dir.resolve("blocked.json"),Map.of("missingActor",true,"corruptActor",true,"checksumMismatch",true,"subjectMismatch",true,"unpublishedNotSelected",true,"missingArchive",true,"corruptArchive",true,"wrongArchiveEndpoint",true,"before",before,"pendingBytes",Base64.getEncoder().encodeToString(pending)));
        }
    }
    // BEGIN ATLAS SCENARIO
    /**
     * Goal: Resolve competing durable recovery owner requests
     * Boundary: Persist two starts against one authority state, overlap their calls through separate clients and resolve each original request once
     * Expected: One exact request wins and the other conflicts; the winner completes recovery without reminting a guard or losing an edit.
     */
    @org.junit.jupiter.api.DisplayName("AT-113 | Resolve competing durable recovery owner requests")
    // END ATLAS SCENARIO
    @Test void concurrentRecoveryStartsChooseOnePersistedOwner() throws Exception {
        try(var f=new Fixture("start-race");var pool=Executors.newVirtualThreadPerTaskExecutor()) {
            f.edit(2);f.init(0);f.init(1);
            for(int id:List.of(0,1)) {final int actor=id;assertThrows(IllegalStateException.class,()->f.driver(actor).advance(ArchivePhaseContract::pause));}
            var originals=List.of(read(f.actor(0),f.subject),read(f.actor(1),f.subject));
            var ready=new CountDownLatch(2);var release=new CountDownLatch(1);long[] invoked=new long[2],returned=new long[2];String[] errors=new String[2];
            var tasks=new ArrayList<Future<?>>();
            for(int id=0;id<2;id++) {final int actor=id;tasks.add(pool.submit(()->{
                invoked[actor]=System.nanoTime();ready.countDown();
                try {assertTrue(release.await(10,TimeUnit.SECONDS));f.driver(actor).advance(p->{});errors[actor]="";}
                catch(Exception e) {errors[actor]=e.toString();}finally {returned[actor]=System.nanoTime();}
            }));}
            try {assertTrue(ready.await(10,TimeUnit.SECONDS));}finally {release.countDown();}for(var task:tasks)task.get(60,TimeUnit.SECONDS);
            save(f.dir.resolve("initial-race.json"),Map.of("actors",originals,"errors",errors,"invoked",invoked,"returned",returned));
            assertTrue(Math.max(invoked[0],invoked[1])<Math.min(returned[0],returned[1]));
            var results=new ArrayList<RecoveryJournal.Result>();
            for(int id=0;id<2;id++) {
                assertTrue(errors[id].isEmpty()||errors[id].contains("PHASE_CONFLICT:CONFLICT")||errors[id].contains("TimeoutException"),errors[id]);
                results.add(f.root.apply(originals.get(id).pending().request()));
            }
            assertEquals(1,results.stream().filter(r->r.code().equals("OK")).count());int winner=results.get(0).code().equals("OK")?0:1;
            assertEquals("CONFLICT",results.get(1-winner).code());
            // A winner whose reply was unknown must finish the same pending phase, not advance twice.
            if(read(f.actor(winner),f.subject).index()==0)f.driver(winner).advance(p->{});
            f.frame(winner,Step.START,"OK");f.complete(winner);f.audit();
            save(f.dir.resolve("resolved-race.json"),Map.of("results",results,"winner",winner,"view",f.driver(winner).view()));
        }
    }
    // BEGIN ATLAS SCENARIO
    /**
     * Goal: Detect a real stale recovery overwrite caused by refreshing a guard
     * Boundary: Reject an old prune request after takeover, then actually apply its old replacement using an incorrectly refreshed current Cassandra guard
     * Expected: The unsafe mutation really applies and the independent archive ledger rejects its exact boundary; the unchanged-request control preserves newer state.
     */
    @org.junit.jupiter.api.DisplayName("AT-114 | Detect a real stale recovery overwrite caused by refreshing a guard")
    // END ATLAS SCENARIO
    @Test void refreshedGuardMutationReallyOverwritesAndIsRejectedByOracle() throws Exception {
        try(var f=new Fixture("mutant-refresh-guard")) {
            f.edit(2);f.init(0);while(read(f.actor(0),f.subject).index()<5)f.advance(0);
            assertThrows(IllegalStateException.class,()->f.driver(0).advance(ArchivePhaseContract::pause));
            var original=read(f.actor(0),f.subject).pending().request();f.init(1);f.complete(1);
            var before=f.hot.view();assertEquals("CONFLICT",f.hot.apply(original).code());assertEquals(before,f.hot.view());
            // Actual unsafe alternative: preserve the old replacement, silently replace its expected guard.
            var broken=new RecoveryJournal.Request(UUID.randomUUID(),before.state(),original.next());
            assertEquals("OK",f.hot.apply(broken).code());assertEquals(original.next(),f.hot.view().state());
            f.frame(0,Step.PRUNE,"OK");var verdict=new ArchiveFenceChecker(32).check(f.frames);
            assertEquals(ArchiveFenceChecker.Verdict.INVALID,verdict.verdict());assertEquals(f.frames.size()-1,verdict.checked());
            save(f.dir.resolve("mutant.json"),Map.of("subject",f.subject,"original",original,"broken",broken,"before",before,"after",f.hot.view(),"frames",f.frames,"result",verdict));
        }
    }
    // BEGIN ATLAS SCENARIO
    /**
     * Goal: Recover the original phase proof without restoring old state
     * Boundary: Lose a worker after a phase effect, finish a newer owner, then replay the old persisted freeze, certify, prune, install or activate request
     * Expected: The original receipt and captured proof return while complete current Cassandra state and journals remain unchanged.
     */
    @org.junit.jupiter.api.DisplayName("AT-115 | Recover the original phase proof without restoring old state")
    // END ATLAS SCENARIO
    @Test void historicalPhaseRepliesRecoverOriginalProofAfterNewerOwnerFinishes() throws Exception {
        for(Step target:List.of(Step.FREEZE,Step.CERTIFY,Step.PRUNE,Step.INSTALL,Step.ACTIVATE))try(var f=new Fixture("historical-"+target)) {
            f.edit(2);f.init(0);while(PHASES.get(read(f.actor(0),f.subject).index())!=target)f.advance(0);
            assertThrows(IllegalStateException.class,()->f.driver(0).advance(point->{if(point.equals("AFTER_EFFECT"))throw new IllegalStateException("TEST_EFFECT");}));
            Actor pending=read(f.actor(0),f.subject);f.frame(0,target,"OK");f.init(1);f.complete(1);
            var root=f.root.view();var hot=f.hot.view();Actor recovered=f.driver(0).advance(p->{});
            assertEquals(pending.pending().next(),recovered.facts());assertEquals(pending.pending(),recovered.done().get(pending.index()).pending());
            assertEquals(root,f.root.view());assertEquals(hot,f.hot.view());f.audit();
            save(f.dir.resolve("historical.json"),Map.of("pending",pending,"recovered",recovered,"root",root,"hot",hot));
        }
    }
    // BEGIN ATLAS SCENARIO
    /**
     * Goal: Preserve an offer edit racing a persisted recovery freeze
     * Boundary: Overlap a whole-offer edit and freeze prepared against the same hot guard through separate clients, then resolve their original requests
     * Expected: Exactly one takes effect; a winning edit is captured by a new recovery owner, a winning freeze fences the edit, and complete receipt history survives.
     */
    @org.junit.jupiter.api.DisplayName("AT-118 | Preserve an offer edit racing a persisted recovery freeze")
    // END ATLAS SCENARIO
    @Test void offerEditRacingPersistedFreezeHasOneExactWinner() throws Exception {
        try(var f=new Fixture("edit-freeze-race");var pool=Executors.newVirtualThreadPerTaskExecutor()) {
            f.edit(2);f.init(0);f.advance(0);
            var before=f.hot.view().state();Hot h=hot(before);f.frame(3,Step.READ,"OK");
            var rows=new ArrayList<>(h.rows());rows.add(new ArchiveFenceModel.Receipt(h.sequence()+1,3,h.cents(),403));
            var edit=new RecoveryJournal.Request(UUID.randomUUID(),before,state(new Hot(h.epoch(),h.generation()+1,true,h.sequence()+1,403,h.floor(),h.base(),rows)));
            assertThrows(IllegalStateException.class,()->f.driver(0).advance(ArchivePhaseContract::pause));
            var freeze=read(f.actor(0),f.subject).pending().request();assertEquals(edit.expected(),freeze.expected());
            var requests=List.of(edit,freeze);var ready=new CountDownLatch(2);var release=new CountDownLatch(1);
            long[] invoked=new long[2],returned=new long[2];String[] errors=new String[2];RecoveryJournal.Result[] initial=new RecoveryJournal.Result[2];var tasks=new ArrayList<Future<?>>();
            for(int id=0;id<2;id++) {final int i=id;tasks.add(pool.submit(()->{
                invoked[i]=System.nanoTime();ready.countDown();
                try {assertTrue(release.await(10,TimeUnit.SECONDS));initial[i]=(i==0?f.peerHot:f.hot).apply(requests.get(i));errors[i]="";}
                catch(com.datastax.oss.driver.api.core.servererrors.WriteTimeoutException | com.datastax.oss.driver.api.core.servererrors.ReadTimeoutException | com.datastax.oss.driver.api.core.DriverTimeoutException e) {errors[i]=e.toString();}
                catch(Exception e) {throw new RuntimeException(e);}finally {returned[i]=System.nanoTime();}
            }));}
            try {assertTrue(ready.await(10,TimeUnit.SECONDS));}finally {release.countDown();}for(var task:tasks)task.get(60,TimeUnit.SECONDS);
            save(f.dir.resolve("initial-race.json"),Map.of("requests",requests,"initial",initial,"errors",errors,"invoked",invoked,"returned",returned));
            assertTrue(Math.max(invoked[0],invoked[1])<Math.min(returned[0],returned[1]));
            var editResult=f.hot.apply(edit);var freezeResult=f.peerHot.apply(freeze);assertNotEquals(editResult.code().equals("OK"),freezeResult.code().equals("OK"));
            if(initial[0]!=null)assertEquals(initial[0],editResult);if(initial[1]!=null)assertEquals(initial[1],freezeResult);
            if(editResult.code().equals("OK")) {
                assertEquals("CONFLICT",freezeResult.code());f.frame(3,Step.WRITE,"OK");
                assertThrows(IllegalStateException.class,()->f.driver(0).advance(p->{}));
                // Failed planning CAS made no logical FREEZE; a new owner captures the accepted edit.
                f.init(1);f.complete(1);
            } else {
                assertEquals("CONFLICT",editResult.code());assertEquals("OK",freezeResult.code());
                f.driver(0).advance(p->{});f.frame(0,Step.FREEZE,"OK");f.frame(3,Step.WRITE,"FENCED");f.complete(0);
            }
            f.audit();save(f.dir.resolve("resolved-race.json"),Map.of("edit",editResult,"freeze",freezeResult,"view",f.driver(0).view()));
        }
    }
    // BEGIN ATLAS SCENARIO
    /**
     * Goal: Prevent old recovery work from crossing a newer freeze
     * Boundary: Race persisted prune, install and activate requests against a new owner freeze using the same original hot guard through separate clients
     * Expected: One exact mutation wins; the losing request stays stale, a valid owner completes recovery and the complete accepted edit history is conserved.
     */
    @org.junit.jupiter.api.DisplayName("AT-119 | Prevent old recovery work from crossing a newer freeze")
    // END ATLAS SCENARIO
    @Test void cleanupInstallAndActivationRaceNewFreezeWithoutCrossingItsFence() throws Exception {
        for(Step phase:List.of(Step.PRUNE,Step.INSTALL,Step.ACTIVATE))try(var f=new Fixture("maintenance-race-"+phase);var pool=Executors.newVirtualThreadPerTaskExecutor()) {
            f.edit(2);f.init(0);while(PHASES.get(read(f.actor(0),f.subject).index())!=phase)f.advance(0);
            assertThrows(IllegalStateException.class,()->f.driver(0).advance(ArchivePhaseContract::pause));
            f.init(1);f.advance(1);assertThrows(IllegalStateException.class,()->f.driver(1).advance(ArchivePhaseContract::pause));
            var requests=List.of(read(f.actor(0),f.subject).pending().request(),read(f.actor(1),f.subject).pending().request());
            assertEquals(requests.get(0).expected(),requests.get(1).expected());
            var ready=new CountDownLatch(2);var release=new CountDownLatch(1);long[] invoked=new long[2],returned=new long[2];
            String[] errors=new String[2];RecoveryJournal.Result[] initial=new RecoveryJournal.Result[2];var tasks=new ArrayList<Future<?>>();
            for(int id=0;id<2;id++) {final int i=id;tasks.add(pool.submit(()->{
                invoked[i]=System.nanoTime();ready.countDown();
                try {assertTrue(release.await(10,TimeUnit.SECONDS));initial[i]=(i==0?f.hot:f.peerHot).apply(requests.get(i));errors[i]="";}
                catch(com.datastax.oss.driver.api.core.servererrors.WriteTimeoutException | com.datastax.oss.driver.api.core.servererrors.ReadTimeoutException | com.datastax.oss.driver.api.core.DriverTimeoutException e) {errors[i]=e.toString();}
                catch(Exception e) {throw new RuntimeException(e);}finally {returned[i]=System.nanoTime();}
            }));}
            try {assertTrue(ready.await(10,TimeUnit.SECONDS));}finally {release.countDown();}for(var task:tasks)task.get(60,TimeUnit.SECONDS);
            save(f.dir.resolve("initial-race.json"),Map.of("phase",phase,"requests",requests,"initial",initial,"errors",errors,"invoked",invoked,"returned",returned));
            assertTrue(Math.max(invoked[0],invoked[1])<Math.min(returned[0],returned[1]));
            var old=f.hot.apply(requests.get(0));var newer=f.peerHot.apply(requests.get(1));
            assertNotEquals(old.code().equals("OK"),newer.code().equals("OK"));
            if(initial[0]!=null)assertEquals(initial[0],old);if(initial[1]!=null)assertEquals(initial[1],newer);
            if(old.code().equals("OK")) {
                assertEquals("CONFLICT",newer.code());f.driver(0).advance(p->{});f.frame(0,phase,"OK");
                assertThrows(IllegalStateException.class,()->f.driver(1).advance(p->{}));f.init(2);f.complete(2);
            } else {
                assertEquals("CONFLICT",old.code());assertEquals("OK",newer.code());f.driver(1).advance(p->{});f.frame(1,Step.FREEZE,"OK");
                assertThrows(IllegalStateException.class,()->f.driver(0).advance(p->{}));f.frame(0,phase,"STALE");f.complete(1);
            }
            f.audit();save(f.dir.resolve("resolved-race.json"),Map.of("old",old,"newer",newer,"view",f.driver(0).view()));
        }
    }
}
