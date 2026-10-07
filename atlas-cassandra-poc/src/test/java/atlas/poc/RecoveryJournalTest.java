package atlas.poc;

import org.junit.jupiter.api.Test;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.file.*;
import java.util.*;
import static atlas.poc.RecoveryJournal.*;
import static atlas.poc.TraceAssertions.*;

class RecoveryJournalTest {
    record Trace(State genesis,List<Event> events,Check result) {}
    static State state(String value) { return new State(UUID.randomUUID(),value); }
    static Request request(State before,String value) { return new Request(UUID.randomUUID(),before,state(value)); }
    private void save(String name,State initial,List<Event> events,boolean valid) throws Exception {
        var mapper=new ObjectMapper();Path p=Path.of("build/evidence/recovery-journal-model",name+".json");Files.createDirectories(p.getParent());
        Check check=RecoveryJournal.check(initial,events);mapper.writeValue(p.toFile(),new Trace(initial,events,check));
        assertEquals(valid,check.valid());Trace read=mapper.readValue(p.toFile(),Trace.class);
        assertEquals(check,RecoveryJournal.check(read.genesis(),read.events()));
    }
    static Result apply(Store store,Request request,List<Event> events) throws Exception {
        Result r=store.apply(request);events.add(new Event(request,r,store.view()));return r;
    }
    // BEGIN ATLAS SCENARIO
    /**
     * Goal: Replay exact recovery effects without restoring older state
     * Boundary: Model four phase boundaries with before-send and after-effect cuts, then retry serialized requests after later phases
     * Expected: Retries return original receipts without changing newer state and changed requests cannot reuse an accepted operation key.
     */
    @org.junit.jupiter.api.DisplayName("AT-095 | Replay exact recovery effects without restoring older state")
    // END ATLAS SCENARIO
    @Test void exactRecoveryStepsSurviveLostRepliesAndLaterOwnersInModel() throws Exception {
        for(int lost=0;lost<4;lost++)for(boolean after:List.of(false,true)) {
            State initial=state("GENESIS");var store=new Model(initial);var events=new ArrayList<Event>();var originals=new ArrayList<Request>();
            for(int step=0;step<4;step++) {
                Request r=request(store.view().state(),"PHASE-"+step);originals.add(r);
                // A before-send loss changes nothing; an after-effect loss hides the exact returned receipt.
                if(step==lost&&after)apply(store,r,events);
                Request recovered=new ObjectMapper().readValue(new ObjectMapper().writeValueAsBytes(r),Request.class);
                assertEquals("OK",apply(store,recovered,events).code());
            }
            View finalView=store.view();for(Request r:originals)assertEquals("OK",apply(store,r,events).code());
            assertEquals(finalView,store.view(),"old success is historical, not permission to restore older state");
            var old=originals.get(0);assertEquals("KEY_REUSE",apply(store,new Request(old.operation(),old.expected(),state("tampered")),events).code());
            save("cut-"+lost+"-"+after,initial,events,true);
        }
    }
    // BEGIN ATLAS SCENARIO
    /**
     * Goal: Detect broken recovery state and receipt atomicity
     * Boundary: Execute state-only, receipt-only, reapply-old-state and ignore-request-binding mutants
     * Expected: The independent complete-state oracle rejects each actual broken execution and accepts the identical requests on the correct model.
     */
    @org.junit.jupiter.api.DisplayName("AT-096 | Detect broken recovery state and receipt atomicity")
    // END ATLAS SCENARIO
    @Test void journalCheckerRejectsSplitEffectsAndMisboundRetries() throws Exception {
        for(Broken broken:Broken.values()) {
            if(broken==Broken.NONE)continue;
            State initial=state("GENESIS");var store=new Model(initial,broken);var events=new ArrayList<Event>();
            Request first=request(initial,"FIRST");apply(store,first,events);
            if(broken==Broken.REPLAY_AS_NEW) { apply(store,request(store.view().state(),"LATER"),events);apply(store,first,events); }
            if(broken==Broken.IGNORE_BINDING)apply(store,new Request(first.operation(),first.expected(),state("TAMPERED")),events);
            Check rejected=RecoveryJournal.check(initial,events);
            assertEquals(broken==Broken.REPLAY_AS_NEW?2:broken==Broken.IGNORE_BINDING?1:0,rejected.checked(),"exact mutant boundary");
            assertEquals(broken==Broken.IGNORE_BINDING?"exact outcome/receipt mismatch":"atomic state/journal mismatch",rejected.reason());
            save("mutant-"+broken,initial,events,false);
            var correct=new Model(initial);var control=new ArrayList<Event>();
            for(Event event:events)apply(correct,event.request(),control);
            save("control-"+broken,initial,control,true);
        }
    }
}
