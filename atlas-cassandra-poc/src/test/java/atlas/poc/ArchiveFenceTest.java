package atlas.poc;

import java.nio.file.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import com.fasterxml.jackson.databind.ObjectMapper;
import static atlas.poc.ArchiveFenceModel.*;
import static atlas.poc.ArchiveFenceChecker.Verdict.*;
import static org.junit.jupiter.api.Assertions.*;

class ArchiveFenceTest {
    static final List<Step> RECOVERY=List.of(Step.START,Step.FREEZE,Step.COPY,Step.VERIFY,Step.CERTIFY,Step.PRUNE,Step.INSTALL,Step.ACTIVATE);
    private static final ObjectMapper JSON=new ObjectMapper();
    record Evidence(int schedule,Broken broken,boolean triggered,List<Frame> frames,ArchiveFenceChecker.Result result) {}
    interface Visitor {void visit(List<Command> commands) throws Exception;}
    static void enumerate(List<List<Step>> actors,int[] positions,List<Command> prefix,Visitor visitor) throws Exception {
        boolean done=true;
        for(int actor=0;actor<actors.size();actor++) {
            int p=positions[actor];if(p==actors.get(actor).size())continue;done=false;
            prefix.add(new Command(actor,actors.get(actor).get(p)));positions[actor]++;
            enumerate(actors,positions,prefix,visitor);positions[actor]--;prefix.remove(prefix.size()-1);
        }
        if(done)visitor.visit(List.copyOf(prefix));
    }
    private Evidence execute(int index,List<Command> commands,Broken broken) {
        var model=new ArchiveFenceModel(broken);var frames=new ArrayList<Frame>();
        for(Command command:commands)frames.add(model.execute(command));
        return new Evidence(index,broken,model.triggered,frames,new ArchiveFenceChecker().check(frames));
    }
    private void save(String name,Evidence evidence) throws Exception {
        Path path=Path.of("build/evidence/archive-fence",name+".json");Files.createDirectories(path.getParent());JSON.writeValue(path.toFile(),evidence);
        Evidence replay=JSON.readValue(path.toFile(),Evidence.class);assertEquals(evidence.result(),new ArchiveFenceChecker().check(replay.frames()));
    }
    static List<Command> seed() {return List.of(new Command(2,Step.READ),new Command(2,Step.WRITE));}
    static List<Command> recovery(int actor) {return RECOVERY.stream().map(s->new Command(actor,s)).toList();}
    private void exhaustive(boolean takeover,int expected) throws Exception {
        List<List<Step>> actors=takeover?List.of(RECOVERY,RECOVERY):List.of(RECOVERY,List.of(Step.READ,Step.WRITE),List.of(Step.READ,Step.WRITE));
        long[] counts=new long[6]; // schedules, prefixes, accepted writes, prunes, stale, fenced
        enumerate(actors,new int[actors.size()],new ArrayList<>(),commands->{
            var plan=new ArrayList<Command>();if(takeover)plan.addAll(seed());plan.addAll(commands);
            var evidence=execute((int)counts[0]++,plan,Broken.NONE);assertEquals(VALID,evidence.result().verdict(),evidence.toString());
            for(int n=0;n<=evidence.frames().size();n++) {assertEquals(VALID,new ArchiveFenceChecker().check(evidence.frames().subList(0,n)).verdict());counts[1]++;}
            View end=evidence.frames().get(evidence.frames().size()-1).observed();
            assertTrue(end.hot().active());assertEquals(end.root().owner(),end.hot().epoch());assertEquals(end.root().owner(),end.root().certificate().owner());
            for(Frame frame:evidence.frames()) {
                if(frame.outcome().equals("OK")&&frame.command().step()==Step.WRITE)counts[2]++;
                if(frame.outcome().equals("OK")&&frame.command().step()==Step.PRUNE)counts[3]++;
                if(frame.outcome().equals("STALE"))counts[4]++;
                if(frame.outcome().equals("FENCED"))counts[5]++;
            }
            save((takeover?"takeover-":"writers-")+evidence.schedule(),evidence);
        });
        assertEquals(expected,counts[0]);assertTrue(counts[2]>0&&counts[3]>0&&counts[4]>0);if(!takeover)assertTrue(counts[5]>0);
        JSON.writeValue(Path.of("build/evidence/archive-fence",takeover?"takeover-summary.json":"writers-summary.json").toFile(),Map.of("schedules",counts[0],"prefixes",counts[1],"acceptedWrites",counts[2],"prunes",counts[3],"stale",counts[4],"fenced",counts[5]));
    }
    // BEGIN ATLAS SCENARIO
    /**
     * Goal: Preserve accepted edits through archive cleanup and installation
     * Boundary: Enumerate 2970 order-preserving schedules of one eight-step recovery and two read/write clients
     * Expected: Every accepted receipt remains reconstructible from certified archive plus actual hot rows and the completed recovery resumes authoring.
     */
    @org.junit.jupiter.api.DisplayName("AT-103 | Preserve accepted edits through archive cleanup and installation")
    // END ATLAS SCENARIO
    @Test void archiveCoverageAndInstallationPreserveEveryInterleavedWriter() throws Exception {exhaustive(false,2970);}
    // BEGIN ATLAS SCENARIO
    /**
     * Goal: Prevent superseded recovery owners from deleting or restoring newer state
     * Boundary: Enumerate 12870 merges of two eight-step recoveries after an accepted edit
     * Expected: Only a correctly bound owner and hot generation can certify, prune, install and activate; the newest uninterrupted recovery completes.
     */
    @org.junit.jupiter.api.DisplayName("AT-104 | Prevent superseded recovery owners from deleting or restoring newer state")
    // END ATLAS SCENARIO
    @Test void competingArchiveRecoveriesCannotPruneOrInstallAcrossFences() throws Exception {exhaustive(true,12870);}
    // BEGIN ATLAS SCENARIO
    /**
     * Goal: Recover both an archived prefix and later hot edits
     * Boundary: Prune and install once, reject a delayed writer, accept a fresh edit and perform another complete recovery
     * Expected: The second archive contains the exact prefix and tail; the old writer never regains access through restored generations.
     */
    @org.junit.jupiter.api.DisplayName("AT-105 | Recover both an archived prefix and later hot edits")
    // END ATLAS SCENARIO
    @Test void prunedPrefixAndUnarchivedTailRemainBoundToFreshInstallGeneration() throws Exception {
        var plan=new ArrayList<>(seed());plan.add(new Command(3,Step.READ));plan.addAll(recovery(0));plan.add(new Command(3,Step.WRITE));
        plan.add(new Command(3,Step.READ));plan.add(new Command(3,Step.WRITE));plan.addAll(recovery(1));
        var evidence=execute(0,plan,Broken.NONE);assertEquals(VALID,evidence.result().verdict());assertEquals(22,evidence.frames().size());
        assertEquals("STALE",evidence.frames().get(11).outcome(),"a saved writer cannot cross install/activation");
        Hot tail=evidence.frames().get(13).observed().hot();assertEquals(1,tail.floor());assertEquals(2,tail.sequence());assertEquals(List.of(new Receipt(2,3,402,403)),tail.rows());
        View end=evidence.frames().get(21).observed();assertTrue(end.hot().active());assertEquals(2,end.hot().floor());assertEquals(403,end.hot().cents());assertTrue(end.hot().rows().isEmpty());
        assertEquals(List.of(new Receipt(1,2,500,402),new Receipt(2,3,402,403)),end.archive().get(2L).receipts());
        for(int n=0;n<=22;n++)assertEquals(VALID,new ArchiveFenceChecker().check(evidence.frames().subList(0,n)).verdict());
        save("archived-prefix-hot-tail",evidence);
    }
    // BEGIN ATLAS SCENARIO
    /**
     * Goal: Keep bad archive copies from authorizing cleanup
     * Boundary: Supply copies missing the last receipt, bound to an older fence or carrying the wrong final price, then let a new owner recover
     * Expected: Verification rejects each copy, cleanup and activation stay blocked, and later valid recovery retains the accepted edit.
     */
    @org.junit.jupiter.api.DisplayName("AT-106 | Keep bad archive copies from authorizing cleanup")
    // END ATLAS SCENARIO
    @Test void incompleteOrMisboundArchiveCopiesBlockCleanupAndCanBeRecovered() throws Exception {
        for(Copy copy:List.of(Copy.OMIT_TAIL,Copy.WRONG_FENCE,Copy.WRONG_PRICE)) {
            var plan=new ArrayList<>(seed());for(Step s:RECOVERY)plan.add(new Command(0,s,s==Step.COPY?copy:Copy.NORMAL));
            plan.addAll(recovery(1));var evidence=execute(0,plan,Broken.NONE);assertEquals(VALID,evidence.result().verdict());
            assertEquals("COVERAGE_MISMATCH",evidence.frames().get(5).outcome());
            for(int index=6;index<10;index++)assertEquals("NOT_READY",evidence.frames().get(index).outcome());
            Hot fenced=evidence.frames().get(9).observed().hot();assertFalse(fenced.active());assertEquals(0,fenced.floor());assertEquals(1,fenced.rows().size());
            Hot recovered=evidence.frames().get(17).observed().hot();assertTrue(recovered.active());assertEquals(402,recovered.cents());assertEquals(1,recovered.floor());assertTrue(recovered.rows().isEmpty());
            save("bad-copy-"+copy,evidence);
        }
    }
    private List<Command> control(Broken broken) {
        var plan=new ArrayList<>(seed());
        if(broken==Broken.VERIFY_BAD_COPY) {
            for(Step step:RECOVERY.subList(0,4))plan.add(new Command(0,step,step==Step.COPY?Copy.OMIT_TAIL:Copy.NORMAL));
        } else if(broken==Broken.CERTIFY_STALE) {
            for(Step step:RECOVERY.subList(0,4))plan.add(new Command(0,step));plan.add(new Command(1,Step.START));plan.add(new Command(0,Step.CERTIFY));
        } else if(broken==Broken.PRUNE_STALE) {
            for(Step step:RECOVERY.subList(0,5))plan.add(new Command(0,step));plan.add(new Command(1,Step.START));plan.add(new Command(1,Step.FREEZE));plan.add(new Command(0,Step.PRUNE));
        } else {
            for(Step step:RECOVERY) {
                if(broken==Broken.PRUNE_UNCERTIFIED&&step==Step.CERTIFY)continue;
                if(broken==Broken.ACTIVATE_WITHOUT_INSTALL&&step==Step.INSTALL)continue;
                plan.add(new Command(0,step));
            }
        }
        return plan;
    }
    // BEGIN ATLAS SCENARIO
    /**
     * Goal: Prove archive recovery checks detect real unsafe transitions
     * Boundary: Execute seven broken verification, certification, pruning, installation and activation variants
     * Expected: Each saved trace fails at its named boundary and the identical command sequence passes on the correct candidate.
     */
    @org.junit.jupiter.api.DisplayName("AT-107 | Prove archive recovery checks detect real unsafe transitions")
    // END ATLAS SCENARIO
    @Test void unsafeArchiveFencesProduceExactReplayableCounterexamples() throws Exception {
        for(Broken broken:Broken.values()) {
            if(broken==Broken.NONE)continue;
            var plan=control(broken);var bad=execute(0,plan,broken);assertTrue(bad.triggered());assertEquals(INVALID,bad.result().verdict());
            int index=bad.result().checked();Frame failure=bad.frames().get(index);assertEquals("OK",failure.outcome());Hot before=bad.frames().get(index-1).observed().hot();
            Step expected=switch(broken) {
                case VERIFY_BAD_COPY -> Step.VERIFY;
                case CERTIFY_STALE -> Step.CERTIFY;
                case PRUNE_UNCERTIFIED,PRUNE_STALE -> Step.PRUNE;
                case INSTALL_OLD_IMAGE,INSTALL_OLD_GENERATION -> Step.INSTALL;
                case ACTIVATE_WITHOUT_INSTALL -> Step.ACTIVATE;
                default -> throw new AssertionError();
            };
            assertEquals(expected,failure.command().step());
            switch(broken) {
                case VERIFY_BAD_COPY -> assertEquals(0,bad.frames().get(index-1).observed().archive().get(1L).receipts().size());
                case CERTIFY_STALE -> assertEquals(2,bad.frames().get(index-1).observed().root().owner());
                case PRUNE_UNCERTIFIED -> assertNull(bad.frames().get(index-1).observed().root().certificate());
                case PRUNE_STALE -> assertEquals(2,before.epoch());
                case INSTALL_OLD_IMAGE -> {assertEquals(402,before.cents());assertEquals(500,failure.observed().hot().cents());assertEquals(0,failure.observed().hot().sequence());}
                case INSTALL_OLD_GENERATION -> {assertTrue(before.generation()>0);assertEquals(0,failure.observed().hot().generation());}
                case ACTIVATE_WITHOUT_INSTALL -> {assertFalse(before.active());assertTrue(failure.observed().hot().active());assertTrue(plan.stream().noneMatch(c->c.step()==Step.INSTALL));}
                default -> throw new AssertionError();
            }
            save("mutant-"+broken,bad);var good=execute(0,plan,Broken.NONE);assertEquals(VALID,good.result().verdict());save("control-"+broken,good);
        }
    }
    // BEGIN ATLAS SCENARIO
    /**
     * Goal: Keep oversized archive recovery traces from being called verified
     * Boundary: Give the independent checker a seven-step limit for an eight-step recovery
     * Expected: Return INCONCLUSIVE rather than a passing proof.
     */
    @org.junit.jupiter.api.DisplayName("AT-108 | Keep oversized archive recovery traces from being called verified")
    // END ATLAS SCENARIO
    @Test void archiveFenceCheckerNeverTreatsAnExceededBoundAsProof() {
        var evidence=execute(0,recovery(0),Broken.NONE);assertEquals(INCONCLUSIVE,new ArchiveFenceChecker(7).check(evidence.frames()).verdict());
    }
}
