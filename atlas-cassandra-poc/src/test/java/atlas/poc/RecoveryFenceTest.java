package atlas.poc;

import org.junit.jupiter.api.Test;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.file.*;
import java.util.*;
import static atlas.poc.RecoveryFenceModel.*;
import static atlas.poc.RecoveryFenceChecker.Verdict.*;
import static atlas.poc.TraceAssertions.*;

class RecoveryFenceTest {
    record Evidence(int schedule,Broken broken,boolean triggered,List<Frame> frames,RecoveryFenceChecker.Result result) {}
    static List<List<Command>> schedules(boolean takeover) {
        var recovery=List.of(Step.START,Step.FREEZE,Step.PUBLISH,Step.ACTIVATE);
        var writer=List.of(Step.READ,Step.WRITE);
        List<List<Step>> actors=List.of(recovery,takeover?recovery:writer,writer);
        var result=new ArrayList<List<Command>>();enumerate(actors,new int[3],new ArrayList<>(),result);return result;
    }
    private static void enumerate(List<List<Step>> actors,int[] positions,List<Command> prefix,List<List<Command>> result) {
        boolean finished=true;
        for(int actor=0;actor<actors.size();actor++) {
            int position=positions[actor];if(position==actors.get(actor).size())continue;finished=false;
            prefix.add(new Command(actor,actors.get(actor).get(position)));positions[actor]++;
            enumerate(actors,positions,prefix,result);positions[actor]--;prefix.remove(prefix.size()-1);
        }
        if(finished)result.add(List.copyOf(prefix));
    }
    private Evidence execute(int schedule,List<Command> commands,Broken broken) {
        var model=new RecoveryFenceModel(broken);var frames=new ArrayList<Frame>();
        for(Command c:commands)frames.add(model.execute(c));
        return new Evidence(schedule,broken,model.triggered,frames,new RecoveryFenceChecker().check(frames));
    }
    private void save(String name,Evidence e) throws Exception {
        Path path=Path.of("build/evidence/recovery-fence",name+".json");Files.createDirectories(path.getParent());
        var mapper=new ObjectMapper();mapper.writeValue(path.toFile(),e);
        var replay=mapper.readValue(path.toFile(),Evidence.class);
        assertEquals(e.result(),new RecoveryFenceChecker().check(replay.frames()),"persisted trace must replay identically");
    }
    private void exactMutationBoundary(Evidence e) {
        int index=e.result().checked();assertTrue(index>0);
        Frame bad=e.frames().get(index);View before=e.frames().get(index-1).observed();
        assertEquals("OK",bad.outcome(),"control must execute the unsafe mutation, not merely throw");
        var earlier=e.frames().subList(0,index).stream().filter(f->f.command().actor()==bad.command().actor()).toList();
        switch(e.broken()) {
            case WRITE_WHILE_FROZEN -> assertFalse(before.hot().active(),"must actually write through a closed gate");
            case STALE_WRITE -> {
                assertTrue(before.hot().active());
                var read=earlier.stream().filter(f->f.command().step()==Step.READ).findFirst().orElseThrow();
                assertNotEquals(read.observed().hot().generation(),before.hot().generation());
            }
            case STALE_ROOT -> {
                var start=earlier.stream().filter(f->f.command().step()==Step.START).findFirst().orElseThrow();
                assertNotEquals(start.observed().root().owner(),before.root().owner());
            }
            case STALE_ACTIVATE -> {
                var freeze=earlier.stream().filter(f->f.command().step()==Step.FREEZE).findFirst().orElseThrow();
                assertNotEquals(freeze.observed().hot().generation(),before.hot().generation());
            }
            case DROP_TAIL -> {
                var freeze=earlier.stream().filter(f->f.command().step()==Step.FREEZE).findFirst().orElseThrow();
                assertFalse(freeze.observed().hot().image().receipts().isEmpty());
                assertTrue(bad.observed().root().checkpoint().image().receipts().isEmpty());
            }
            case NONE -> fail("not a mutation witness");
        }
    }
    private void exhaustive(boolean takeover,int expectedCount) throws Exception {
        var schedules=schedules(takeover);assertEquals(expectedCount,schedules.size());int index=0,accepted=0,stale=0,fenced=0;
        for(var commands:schedules) {
            Evidence e=execute(index++,commands,Broken.NONE);save((takeover?"takeover-":"writers-")+e.schedule(),e);
            assertEquals(VALID,e.result().verdict(),e.toString());
            // Check every whole-execution prefix; a fenced prefix is safe, not completed recovery.
            for(int n=0;n<=e.frames().size();n++)assertEquals(VALID,new RecoveryFenceChecker().check(e.frames().subList(0,n)).verdict());
            var end=e.frames().get(e.frames().size()-1).observed();
            assertTrue(end.hot().active(),"uninterrupted newest recovery must resume authoring");
            assertEquals(end.root().owner(),end.hot().epoch());assertEquals(end.root().owner(),end.root().checkpoint().epoch());
            assertTrue(end.hot().image().receipts().containsAll(end.root().checkpoint().image().receipts()),"no accepted checkpoint tail lost");
            for(Frame f:e.frames()) {
                if(f.command().step()==Step.WRITE&&f.outcome().equals("OK"))accepted++;
                if(f.outcome().equals("STALE"))stale++;
                if(f.outcome().equals("FENCED"))fenced++;
            }
        }
        assertTrue(accepted>0);assertTrue(stale>0);assertTrue(fenced>0);
        Path p=Path.of("build/evidence/recovery-fence",takeover?"takeover-summary.json":"writers-summary.json");
        new ObjectMapper().writeValue(p.toFile(),Map.of("schedules",index,"acceptedWrites",accepted,"staleOutcomes",stale,"fencedOutcomes",fenced));
    }
    // BEGIN ATLAS SCENARIO
    /**
     * Goal: Keep accepted offer edits across a recovery fence
     * Boundary: Enumerate all 420 order-preserving schedules of two read/write clients and one four-step recovery handoff
     * Expected: Every accepted price edit remains in the exact receipt chain; writes crossing the hot-state fence reject and uninterrupted recovery resumes authoring.
     */
    @org.junit.jupiter.api.DisplayName("AT-091 | Keep accepted offer edits across a recovery fence")
    // END ATLAS SCENARIO
    @Test void allWriterAndRecoveryInterleavingsPreserveAcceptedOfferEdits() throws Exception { exhaustive(false,420); }
    // BEGIN ATLAS SCENARIO
    /**
     * Goal: Prevent an older recovery worker from undoing a newer recovery
     * Boundary: Enumerate all 3150 order-preserving schedules of two competing recovery handoffs and one offer writer
     * Expected: A stale owner cannot publish or activate over a newer hot-state fence; the newest uninterrupted recovery completes without losing accepted edits.
     */
    @org.junit.jupiter.api.DisplayName("AT-092 | Prevent an older recovery worker from undoing a newer recovery")
    // END ATLAS SCENARIO
    @Test void allRecoveryTakeoverInterleavingsRejectOldOwners() throws Exception { exhaustive(true,3150); }
    // BEGIN ATLAS SCENARIO
    /**
     * Goal: Prove the recovery checker detects unsafe cross-store handoffs
     * Boundary: Run five deliberately broken storage variants against writer and recovery schedules
     * Expected: Each broken fence actually executes and produces an independently rejected saved trace; the identical schedule passes with the correct model.
     */
    @org.junit.jupiter.api.DisplayName("AT-093 | Prove the recovery checker detects unsafe cross-store handoffs")
    // END ATLAS SCENARIO
    @Test void brokenCrossStoreFencesProduceReplayableCounterexamples() throws Exception {
        var all=new ArrayList<>(schedules(false));all.addAll(schedules(true));
        for(Broken broken:Broken.values()) {
            if(broken==Broken.NONE)continue;
            Evidence witness=null;int index=0;
            for(var schedule:all) {
                var candidate=execute(index++,schedule,broken);
                if(candidate.result().verdict()==INVALID) { witness=candidate;break; }
            }
            assertNotNull(witness,"must actually expose "+broken);assertTrue(witness.triggered());
            exactMutationBoundary(witness);save("mutant-"+broken,witness);
            // The same schedule with the correct implementation must be accepted.
            assertEquals(VALID,execute(0,witness.frames().stream().map(Frame::command).toList(),Broken.NONE).result().verdict());
        }
    }
    // BEGIN ATLAS SCENARIO
    /**
     * Goal: Keep an oversized recovery trace from being called verified
     * Boundary: Give the checker a seven-step limit for an eight-step handoff trace
     * Expected: Return INCONCLUSIVE rather than VALID; this result is not a passing proof.
     */
    @org.junit.jupiter.api.DisplayName("AT-094 | Keep an oversized recovery trace from being called verified")
    // END ATLAS SCENARIO
    @Test void crossStoreCheckerBoundCannotPassAsProof() {
        var e=execute(0,schedules(false).get(0),Broken.NONE);
        assertEquals(INCONCLUSIVE,new RecoveryFenceChecker(7).check(e.frames()).verdict());
    }
}
