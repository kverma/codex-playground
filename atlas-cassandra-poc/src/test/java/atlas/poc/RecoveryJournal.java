package atlas.poc;

import java.util.*;

/** Test-only exact-operation journal. No retention, service authentication or disaster recovery. */
final class RecoveryJournal {
    record State(UUID guard,String value) {}
    record Request(UUID operation,State expected,State next) {}
    record Receipt(Request request,State before,State after) {}
    record Result(String code,Receipt receipt) {}
    record View(State state,Map<UUID,Receipt> receipts) { View { receipts=Map.copyOf(receipts); } }
    interface Store extends AutoCloseable {
        Result apply(Request request) throws Exception;
        View view() throws Exception;
        default void close() throws Exception {}
    }
    enum Broken { NONE, STATE_ONLY, RECEIPT_ONLY, REPLAY_AS_NEW, IGNORE_BINDING }
    static final class Model implements Store {
        State state;final Map<UUID,Receipt> receipts=new HashMap<>();final Broken broken;
        Model(State state) { this(state,Broken.NONE); }
        Model(State state,Broken broken) { this.state=state;this.broken=broken; }
        public View view() { return new View(state,receipts); }
        public Result apply(Request r) {
            Receipt prior=receipts.get(r.operation());
            if(prior!=null&&broken!=Broken.REPLAY_AS_NEW) {
                return !prior.request().equals(r)&&broken!=Broken.IGNORE_BINDING?new Result("KEY_REUSE",null):new Result("OK",prior);
            }
            if(prior!=null) { state=r.next();return new Result("OK",prior); }
            if(!state.equals(r.expected()))return new Result("CONFLICT",null);
            Receipt receipt=new Receipt(r,state,r.next());
            if(broken!=Broken.RECEIPT_ONLY)state=r.next();
            if(broken!=Broken.STATE_ONLY)receipts.put(r.operation(),receipt);
            return new Result("OK",receipt);
        }
    }
    record Event(Request request,Result result,View observed) {}
    record Check(boolean valid,int checked,String reason) {}
    /** Independent complete-view oracle, not calls to Model.apply or its private ledger. */
    static Check check(State genesis,List<Event> events) {
        State current=genesis;var accepted=new HashMap<UUID,Receipt>();int index=0;
        for(Event event:events) {
            Request r=event.request();Receipt saved=accepted.get(r.operation());Result expected;
            if(saved!=null)expected=saved.request().equals(r)?new Result("OK",saved):new Result("KEY_REUSE",null);
            else if(!r.expected().equals(current))expected=new Result("CONFLICT",null);
            else {
                saved=new Receipt(r,current,r.next());accepted.put(r.operation(),saved);current=r.next();expected=new Result("OK",saved);
            }
            if(!expected.equals(event.result()))return new Check(false,index,"exact outcome/receipt mismatch");
            if(!current.equals(event.observed().state())||!accepted.equals(event.observed().receipts()))return new Check(false,index,"atomic state/journal mismatch");
            index++;
        }
        return new Check(true,index,"exact-operation specification");
    }
}
