package atlas.poc;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import static atlas.poc.Transactions.*;
import atlas.poc.Transactions.Error;

/** Bounded exhaustive search of real-time-consistent serial orders against an independent specification. */
public final class HistoryChecker {
    public enum Verdict { LINEARIZABLE, NON_LINEARIZABLE, INCONCLUSIVE }
    public record Call(long start,long end,String kind,Request request,Snapshot read,Receipt receipt,Error error) {}
    public record Result(Verdict verdict,int explored,int limit) {}
    public record Evidence(long seed,Snapshot initial,List<Call> calls,Result result) {}
    private record State(Snapshot head,Map<UUID,Receipt> receipts) {}
    private final int limit;
    private int explored;
    private boolean exhausted;
    private final Set<String> visited=new HashSet<>();
    public HistoryChecker() { this(100_000); }
    public HistoryChecker(int limit) { this.limit=limit; }
    public Result check(Snapshot initial,List<Call> calls) {
        explored=0; exhausted=false; visited.clear();
        if(calls.size()>20) return new Result(Verdict.INCONCLUSIVE,0,limit);
        for(Call call:calls) {
            boolean write=call.kind().equals("WRITE"), read=call.kind().equals("READ");
            if(call.start()<1||call.end()<call.start()||(!write&&!read)||
               (write && (call.request()==null||call.read()!=null||(call.error()==null)==(call.receipt()==null)))||
               (read && (call.request()!=null||call.receipt()!=null||(call.error()==null)==(call.read()==null))))
                return new Result(Verdict.NON_LINEARIZABLE,0,limit);
        }
        Map<UUID,Receipt> observed=new HashMap<>();
        for(Call call:calls) if(call.receipt()!=null) observed.putIfAbsent(call.receipt().operation(),call.receipt());
        boolean valid=search(calls,observed,new State(initial,Map.of()),0);
        return new Result(valid?Verdict.LINEARIZABLE:exhausted?Verdict.INCONCLUSIVE:Verdict.NON_LINEARIZABLE,explored,limit);
    }
    private boolean search(List<Call> calls,Map<UUID,Receipt> observed,State state,long selected) {
        if(selected==(1L<<calls.size())-1) return true;
        if(++explored>limit) { exhausted=true; return false; }
        String key=selected+"|"+TransactionCodec.encode(state.head())+"|"+state.receipts().entrySet().stream()
            .sorted(Map.Entry.comparingByKey()).map(entry->entry.getKey()+":"+entry.getValue().requestHash()+":"+
                TransactionCodec.encode(entry.getValue().before())+":"+TransactionCodec.encode(entry.getValue().after())).toList();
        if(!visited.add(key)) return false;
        for(int i=0;i<calls.size();i++) {
            if((selected&(1L<<i))!=0) continue;
            Call call=calls.get(i); boolean blocked=false;
            for(int j=0;j<calls.size();j++) {
                // A timed-out write remains pending until recovery; its physical request may execute late.
                long end=calls.get(j).error()==Error.INDETERMINATE && calls.get(j).kind().equals("WRITE")?Long.MAX_VALUE:calls.get(j).end();
                if((selected&(1L<<j))==0 && end<call.start()) { blocked=true; break; }
            }
            if(blocked) continue;
            for(State next:step(state,call,observed)) if(search(calls,observed,next,selected|(1L<<i))) return true;
        }
        return false;
    }
    private List<State> step(State state,Call call,Map<UUID,Receipt> observed) {
        if(call.kind().equals("READ")) {
            return call.error()==Error.INDETERMINATE || (call.error()==null && state.head().equals(call.read()))?List.of(state):List.of();
        }
        if(!call.kind().equals("WRITE")||call.request()==null) return List.of();
        Request request=call.request(); Receipt prior=state.receipts().get(request.operation());
        Error expected=prior!=null?(prior.requestHash().equals(request.hash())?null:Error.KEY_REUSE):classify(state.head(),request);
        if(call.error()==Error.INDETERMINATE) {
            Receipt eventual=observed.get(request.operation());
            State accepted=expected==null && eventual!=null?accept(state,request,eventual):null;
            return accepted==null?List.of(state):List.of(state,accepted);
        }
        if(call.error()!=null) return call.error()==expected?List.of(state):List.of();
        if(expected!=null||call.receipt()==null) return List.of();
        State accepted=accept(state,request,call.receipt()); return accepted==null?List.of():List.of(accepted);
    }
    // Independent admission oracle: deliberately does not call Transactions.check/apply/requiredReads.
    private Error classify(Snapshot head,Request request) {
        boolean admin=request.admittedEpoch()!=null||request.admittedBudget()!=null;
        Set<Group> required=new HashSet<>(request.updates().keySet());
        if(admin) required.addAll(List.of(Group.values()));
        if(required.contains(Group.ECONOMICS)||required.contains(Group.ELIGIBILITY)) required.addAll(List.of(Group.ECONOMICS,Group.ELIGIBILITY));
        if(request.epoch()<1 || (!admin && request.updates().isEmpty()) ||
           (admin && (request.admittedEpoch()==null||request.admittedBudget()==null||!request.updates().isEmpty())) ||
           !request.reads().keySet().containsAll(required)) return Error.INVALID;
        if(request.epoch()!=head.epoch()) return Error.CONFLICT;
        for(var entry:request.reads().entrySet()) if(!head.cells().get(entry.getKey()).version().equals(entry.getValue())) return Error.CONFLICT;
        var values=new EnumMap<Group,String>(Group.class);
        for(Group group:Group.values()) values.put(group,request.updates().getOrDefault(group,head.value(group)));
        try {
            String economics=values.get(Group.ECONOMICS), royalty=values.get(Group.ROYALTY), eligible=values.get(Group.ELIGIBILITY);
            if(!economics.matches("0|[1-9][0-9]{0,6}")||!royalty.matches("0|[1-9][0-9]{0,4}")) return Error.INVALID;
            int cents=Integer.parseInt(economics), basisPoints=Integer.parseInt(royalty);
            if(cents>1_000_000||basisPoints>10_000||(!eligible.equals("NEW")&&!eligible.equals("NEW,CHURNED"))||
               (eligible.equals("NEW,CHURNED")&&cents>500)) return Error.INVALID;
        } catch(RuntimeException e) { return Error.INVALID; }
        int budget=admin?request.admittedBudget():head.budget();
        if(budget<1||budget>4096||(admin && request.admittedEpoch()<=head.epoch())||countBytes(values)>budget) return Error.INVALID;
        return null;
    }
    private int countBytes(Map<Group,String> values) {
        int bytes=0;
        for(Group group:Group.values()) bytes+=(group.name()+"="+values.get(group)+";").getBytes(StandardCharsets.UTF_8).length;
        return bytes;
    }
    private State accept(State state,Request request,Receipt receipt) {
        if(!receipt.operation().equals(request.operation())||!receipt.requestHash().equals(request.hash())) return null;
        Receipt prior=state.receipts().get(request.operation());
        if(prior!=null) return prior.equals(receipt)?state:null;
        Snapshot before=state.head(), after=receipt.after();
        if(!before.equals(receipt.before())||after==null||after.generation()==null||after.generation().equals(before.generation())||reusedGeneration(state,after.generation())||
           after.epoch()!=(request.admittedEpoch()==null?before.epoch():request.admittedEpoch())||
           after.budget()!=(request.admittedBudget()==null?before.budget():request.admittedBudget())||after.cells().size()!=3) return null;
        var values=new EnumMap<Group,String>(Group.class);
        for(Group group:Group.values()) {
            Cell actual=after.cells().get(group), old=before.cells().get(group);
            if(actual==null||actual.version()==null||!actual.value().equals(request.updates().getOrDefault(group,old.value()))) return null;
            if(request.updates().containsKey(group)?actual.version().equals(old.version())||reusedVersion(state,group,actual.version()):!actual.version().equals(old.version())) return null;
            values.put(group,actual.value());
        }
        if(after.payloadBytes()!=countBytes(values)) return null;
        var receipts=new HashMap<>(state.receipts()); receipts.put(request.operation(),receipt);
        return new State(after,Map.copyOf(receipts));
    }
    private boolean reusedGeneration(State state,UUID generation) {
        return state.receipts().values().stream().anyMatch(r->r.before().generation().equals(generation)||r.after().generation().equals(generation));
    }
    private boolean reusedVersion(State state,Group group,UUID version) {
        return state.receipts().values().stream().anyMatch(r->r.before().cells().get(group).version().equals(version)||r.after().cells().get(group).version().equals(version));
    }
    /** Greedy 1-minimal reduction with causal closure, not a globally smallest counterexample. */
    public List<Call> shrink(Snapshot initial,List<Call> calls) {
        var reduced=new ArrayList<>(calls); boolean changed;
        do {
            changed=false;
            for(int i=0;i<reduced.size();i++) {
                var candidate=new ArrayList<>(reduced); candidate.remove(i);
                if(closed(initial,candidate) && check(initial,candidate).verdict()==Verdict.NON_LINEARIZABLE) {
                    reduced=candidate; changed=true; break;
                }
            }
        } while(changed);
        return List.copyOf(reduced);
    }
    private boolean closed(Snapshot initial,List<Call> calls) {
        Set<UUID> generations=new HashSet<>(Set.of(initial.generation()));
        Set<UUID> versions=new HashSet<>(); initial.cells().values().forEach(cell->versions.add(cell.version()));
        for(Call call:calls) if(call.receipt()!=null) {
            generations.add(call.receipt().after().generation());
            call.receipt().after().cells().values().forEach(cell->versions.add(cell.version()));
        }
        for(Call call:calls) {
            if(call.read()!=null && !generations.contains(call.read().generation())) return false;
            if(call.receipt()!=null && !generations.contains(call.receipt().before().generation())) return false;
            if(call.request()!=null && !versions.containsAll(call.request().reads().values())) return false;
        }
        return true;
    }
    public static void save(Path path,Evidence evidence) throws Exception {
        Files.createDirectories(path.getParent());
        new ObjectMapper().writerWithDefaultPrettyPrinter().writeValue(path.toFile(),evidence);
    }
}
