package atlas.poc;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.security.MessageDigest;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.util.*;
import static atlas.poc.Retention.*;
import static atlas.poc.Transactions.*;
import static atlas.poc.HistoryChecker.Verdict.*;

/** Independent bounded lease/allocator/receipt/floor specification; never calls Retention.Base transitions. */
public final class RetentionChecker {
    public record Call(long start,long end,long now,String kind,Draft draft,Issued issued,Receipt receipt,View view,String error) {}
    public record Evidence(long seed,UUID subject,View initial,List<Call> calls,HistoryChecker.Result result) {}
    private record State(View view,Set<UUID> seenMetadata,List<HistoryChecker.Call> commercial) {}
    private record Node(long selected,State state) {}
    private record Allocation(String error,Issued issued,State next) {}
    private final UUID subject;
    private final byte[] key;
    private final int limit;
    private View initial;
    private int explored;
    private boolean exhausted,unobservedAcceptance;
    private final Set<Node> visited=new HashSet<>();
    private final Map<UUID,Receipt> observedReceipts=new HashMap<>();
    public RetentionChecker(UUID subject,byte[] key) { this(subject,key,100_000); }
    public RetentionChecker(UUID subject,byte[] key,int limit) { this.subject=subject;this.key=key.clone();this.limit=limit; }
    public HistoryChecker.Result check(View initial,List<Call> calls) {
        this.initial=initial; explored=0; exhausted=false; unobservedAcceptance=false; visited.clear(); observedReceipts.clear();
        if(calls.size()>24) return new HistoryChecker.Result(INCONCLUSIVE,0,limit);
        // This profile controls the injected clock between joined phases, never during overlapping calls.
        for(Call a:calls) for(Call b:calls) if(a.start()<b.end()&&b.start()<a.end()&&a.now()!=b.now())
            return new HistoryChecker.Result(INCONCLUSIVE,0,limit);
        for(Call c:calls) {
            boolean shape=switch(c.kind()) {
                case "ISSUE" -> c.draft()!=null&&c.receipt()==null&&c.view()==null&&(c.error()==null)==(c.issued()!=null);
                case "COMMIT" -> c.draft()==null&&c.issued()!=null&&c.view()==null&&(c.error()==null)==(c.receipt()!=null);
                case "VIEW","COMPACT" -> c.draft()==null&&c.issued()==null&&c.receipt()==null&&(c.error()==null)==(c.view()!=null);
                default -> false;
            };
            if(!shape||c.start()<1||c.end()<c.start()) return new HistoryChecker.Result(NON_LINEARIZABLE,0,limit);
            if(c.receipt()!=null) observedReceipts.putIfAbsent(c.receipt().operation(),c.receipt());
            if(c.view()!=null) for(Entry e:c.view().entries().values()) if(e.receipt()!=null) observedReceipts.putIfAbsent(e.operation(),e.receipt());
        }
        for(Call c:calls) if(c.kind().equals("COMMIT")&&"INDETERMINATE".equals(c.error())&&!observedReceipts.containsKey(c.issued().request().operation())) unobservedAcceptance=true;
        boolean valid=search(calls,new State(initial,Set.of(initial.generation()),List.of()),0);
        return new HistoryChecker.Result(valid?LINEARIZABLE:exhausted||unobservedAcceptance?INCONCLUSIVE:NON_LINEARIZABLE,explored,limit);
    }
    private boolean pending(Call c) { return !c.kind().equals("VIEW")&&"INDETERMINATE".equals(c.error()); }
    private boolean search(List<Call> calls,State state,long selected) {
        if(selected==(1L<<calls.size())-1) return true;
        if(++explored>limit) { exhausted=true;return false; }
        if(!visited.add(new Node(selected,state))) return false;
        for(int i=0;i<calls.size();i++) {
            if((selected&(1L<<i))!=0) continue;
            Call c=calls.get(i); boolean blocked=false;
            for(int j=0;j<calls.size();j++) if((selected&(1L<<j))==0&&!pending(calls.get(j))&&calls.get(j).end()<c.start()) { blocked=true;break; }
            if(blocked) continue;
            for(State next:step(state,c)) if(search(calls,next,selected|(1L<<i))) return true;
        }
        return false;
    }
    private List<State> step(State state,Call c) {
        if("INDETERMINATE".equals(c.error())) {
            if(c.kind().equals("VIEW")) return List.of(state);
            State effect=advance(state,new Call(c.start(),c.end(),c.now(),c.kind(),c.draft(),c.issued(),null,null,null),true);
            return effect==null||effect.equals(state)?List.of(state):List.of(state,effect);
        }
        State next=advance(state,c,false); return next==null?List.of():List.of(next);
    }
    private State advance(State state,Call c,boolean hypothetical) {
        View v=state.view();
        switch(c.kind()) {
            case "VIEW": return c.error()==null?bind(state,c.view()):null;
            case "ISSUE": {
                Allocation a=allocate(state,c.draft(),c.now());
                if(c.error()!=null) return c.error().equals(a.error())?state:null;
                if(a.error()!=null||(!hypothetical&&!a.issued().equals(c.issued()))) return null;
                return a.next();
            }
            case "COMPACT": {
                if(c.error()!=null) return null;
                long floor=v.floor();
                while(floor<v.allocated()&&v.entries().get(floor+1).expiresAt()<=c.now()) floor++;
                State next=state;
                if(floor!=v.floor()) {
                    var entries=new HashMap<>(v.entries()); final long retired=floor; entries.keySet().removeIf(n->n<=retired);
                    next=change(state,v.allocated(),floor,v.lastExpiry(),v.snapshot(),entries,state.commercial());
                }
                return hypothetical?next:bind(next,c.view());
            }
            case "COMMIT": {
                Issued issued=c.issued(); Request r=issued.request(); Ticket t=issued.ticket(); Entry e=v.entries().get(t.sequence());
                String error=null;
                if(!validTicket(t,r)) error="INVALID_TICKET";
                else if(t.sequence()<=v.floor()) error="REQUEST_TOO_OLD";
                else if(e==null||e.expiresAt()!=t.expiresAt()||!e.operation().equals(t.operation())||!e.requestHash().equals(t.requestHash())) error="INVALID_TICKET";
                else if(e.receipt()==null&&c.now()>=t.expiresAt()) error="REQUEST_TOO_OLD";
                else if(e.receipt()==null) error=commercialError(v.snapshot(),r);
                if(c.error()!=null) return c.error().equals(error)?state:null;
                if(error!=null) return null;
                Receipt receipt=hypothetical?observedReceipts.get(r.operation()):c.receipt();
                if(e.receipt()!=null) return hypothetical||e.receipt().equals(receipt)?state:null;
                if(receipt==null) return null;
                var commercial=new ArrayList<>(state.commercial()); long ordinal=2L*commercial.size()+1;
                commercial.add(new HistoryChecker.Call(ordinal,ordinal+1,"WRITE",r,null,receipt,null));
                // Reuse the independent commercial oracle, including its full recorded ABA ledger.
                if(new HistoryChecker().check(initial.snapshot(),commercial).verdict()!=LINEARIZABLE) return null;
                var entries=new HashMap<>(v.entries());
                entries.put(t.sequence(),new Entry(e.expiresAt(),e.operation(),e.requestHash(),receipt,e.draftId()));
                return change(state,v.allocated(),v.floor(),v.lastExpiry(),receipt.after(),entries,List.copyOf(commercial));
            }
            default: return null;
        }
    }
    private String commercialError(Snapshot head,Request r) {
        for(Transactions.Error error:List.of(Transactions.Error.INVALID,Transactions.Error.CONFLICT))
            if(new HistoryChecker().check(head,List.of(new HistoryChecker.Call(1,2,"WRITE",r,null,null,error))).verdict()==LINEARIZABLE) return error.name();
        return null;
    }
    private Allocation allocate(State state,Draft d,long now) {
        View v=state.view(); String text="atlas-draft-v1|"+subject+"|"+d.request().operation()+"|"+d.anchor()+"|"+d.expiresAt();
        if(d.anchor()<1||d.expiresAt()<1||!equal(hmac(text+"|"+d.request().hash()),d.signature())) return new Allocation("INVALID_TICKET",null,state);
        String id=sha(text);
        for(var item:v.entries().entrySet()) if(id.equals(item.getValue().draftId())) {
            Entry e=item.getValue();
            if(!e.requestHash().equals(d.request().hash())) return new Allocation("KEY_REUSE",null,state);
            return new Allocation(null,issued(d,item.getKey(),e.expiresAt()),state);
        }
        if(d.anchor()<=v.floor()||now>=d.expiresAt()) return new Allocation("REQUEST_TOO_OLD",null,state);
        if(d.anchor()>v.allocated()+1) return new Allocation("INVALID_TICKET",null,state);
        if(v.allocated()-v.floor()>=16) return new Allocation("CAPACITY",null,state);
        long sequence=v.allocated()+1, expiry=Math.max(d.expiresAt(),v.lastExpiry());
        Issued issued=issued(d,sequence,expiry); var entries=new HashMap<>(v.entries());
        entries.put(sequence,new Entry(expiry,issued.request().operation(),issued.request().hash(),null,id));
        return new Allocation(null,issued,change(state,sequence,v.floor(),expiry,v.snapshot(),entries,state.commercial()));
    }
    private Issued issued(Draft d,long sequence,long expiry) {
        Request original=d.request(); UUID operation=new UUID(subject.getMostSignificantBits(),sequence);
        Request r=new Request(operation,original.epoch(),original.reads(),original.updates(),original.admittedEpoch(),original.admittedBudget());
        return new Issued(r,new Ticket(sequence,expiry,operation,r.hash(),hmac("atlas-ticket-v1|"+subject+"|"+sequence+"|"+expiry+"|"+operation+"|"+r.hash())));
    }
    private boolean validTicket(Ticket t,Request r) {
        return t.sequence()>0&&t.expiresAt()>0&&t.operation().equals(r.operation())&&t.requestHash().equals(r.hash())&&
            equal(hmac("atlas-ticket-v1|"+subject+"|"+t.sequence()+"|"+t.expiresAt()+"|"+t.operation()+"|"+t.requestHash()),t.signature());
    }
    private State change(State s,long allocated,long floor,long expiry,Snapshot snapshot,Map<Long,Entry> entries,List<HistoryChecker.Call> commercial) {
        return new State(new View(null,allocated,floor,expiry,snapshot,entries),s.seenMetadata(),commercial);
    }
    private State bind(State state,View seen) {
        if(seen==null||seen.generation()==null) return null;
        View v=state.view();
        if(v.allocated()!=seen.allocated()||v.floor()!=seen.floor()||v.lastExpiry()!=seen.lastExpiry()||!v.snapshot().equals(seen.snapshot())||!v.entries().equals(seen.entries())) return null;
        if(v.generation()!=null) return v.generation().equals(seen.generation())?state:null;
        if(state.seenMetadata().contains(seen.generation())) return null;
        var known=new HashSet<>(state.seenMetadata()); known.add(seen.generation());
        return new State(seen,Set.copyOf(known),state.commercial());
    }
    private boolean equal(String a,String b) { return b!=null&&MessageDigest.isEqual(a.getBytes(StandardCharsets.US_ASCII),b.getBytes(StandardCharsets.US_ASCII)); }
    private String hmac(String text) {
        try { Mac mac=Mac.getInstance("HmacSHA256");mac.init(new SecretKeySpec(key,"HmacSHA256"));return HexFormat.of().formatHex(mac.doFinal(text.getBytes(StandardCharsets.UTF_8))); }
        catch(java.security.GeneralSecurityException e) { throw new IllegalStateException(e); }
    }
    private String sha(String text) {
        try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(text.getBytes(StandardCharsets.UTF_8))); }
        catch(java.security.GeneralSecurityException e) { throw new IllegalStateException(e); }
    }
    public static void save(Path path,Evidence evidence) throws Exception {
        Files.createDirectories(path.getParent());new ObjectMapper().writerWithDefaultPrettyPrinter().writeValue(path.toFile(),evidence);
    }
}
