package atlas.poc;

import java.util.*;
import static atlas.poc.ArchiveRecovery.*;
import static atlas.poc.Transactions.*;

/** Independent serial specification: retains sealed contents, never trusts implementation digests. */
public final class ArchiveRecoveryChecker {
    public enum Verdict { VALID, INVALID, INCONCLUSIVE }
    public record Result(Verdict verdict,int checked,String reason) {}
    public record Evidence(long seed,Snapshot genesis,List<Frame> frames,Result result) {}
    private static final class BoundExceeded extends RuntimeException {}
    private final int bound;
    public ArchiveRecoveryChecker() { this(512); }
    public ArchiveRecoveryChecker(int bound) { this.bound=bound; }
    public Result check(Snapshot genesis,List<Frame> frames) {
        if(frames.size()>bound)return new Result(Verdict.INCONCLUSIVE,0,"step bound");
        Spec spec=new Spec(genesis);int index=0;
        try {
            for(Frame frame:frames) {
                Command c=frame.command();Outcome expected;
                if(c.mode()==Mode.BEFORE)expected=new Outcome("UNKNOWN",null);
                else {
                    expected=spec.step(c,frame);
                    if(c.mode()==Mode.AFTER&&expected.code().equals("OK"))expected=new Outcome("UNKNOWN",null);
                }
                if(!expected.equals(frame.outcome()))return new Result(Verdict.INVALID,index,"outcome mismatch: "+c.kind()+" expected "+expected.code());
                if(!spec.view().equals(frame.observed()))return new Result(Verdict.INVALID,index,"state mismatch: "+c.kind());
                index++;
            }
            return new Result(Verdict.VALID,index,"serial crash-boundary specification");
        } catch(BoundExceeded e) { return new Result(Verdict.INCONCLUSIVE,index,"commercial oracle bound"); }
        catch(RuntimeException e) { return new Result(Verdict.INVALID,index,"malformed or inconsistent trace: "+e.getClass().getSimpleName()); }
    }
    private static final class Spec {
        final Snapshot root;
        Snapshot head;
        long now,hwm,certified,allocated,floor;
        boolean fenced,authority=true,available=true;
        final Map<Long,Grant> grants=new HashMap<>();
        final Map<Long,Item> live=new HashMap<>(),sealedTruth=new HashMap<>();
        final Map<Long,Blob> objects=new HashMap<>();
        final Set<Long> closed=new HashSet<>();
        final Map<String,Hot> saves=new HashMap<>();
        final List<HistoryChecker.Call> accepted=new ArrayList<>();
        Spec(Snapshot genesis) { root=genesis;head=genesis; }
        Outcome result(String code) { return new Outcome(code,null); }
        Hot hot() { return new Hot(allocated,floor,head,live,closed,fenced); }
        Observation view() { return new Observation(now,hwm,certified,grants,sealedTruth.keySet(),objects,hot(),authority,available); }
        boolean complete(long n) {
            Blob object=objects.get(n);Item truth=sealedTruth.get(n);
            return available&&truth!=null&&object!=null&&object.complete()&&truth.equals(object.item())&&truth.grant().equals(grants.get(n));
        }
        Outcome step(Command c,Frame frame) {
            long n=c.sequence();
            switch(c.kind()) {
                case VIEW: return result("OK");
                case CLOCK: now=n;return result("OK");
                case AUTHORITY: authority=c.available();return result("OK");
                case ARCHIVE: available=c.available();return result("OK");
                case SAVE: saves.put(c.name(),hot());return result("OK");
                case CRASH: fenced=true;return result("OK");
                case RESTORE: {
                    fenced=true;Hot saved=saves.get(c.name());if(saved==null)return result("MISSING");
                    allocated=saved.allocated();floor=saved.floor();head=saved.head();live.clear();live.putAll(saved.rows());closed.clear();closed.addAll(saved.sealed());return result("OK");
                }
                case DAMAGE: {
                    Blob prior=objects.get(n);if(prior==null)return result("MISSING");
                    if("remove".equals(c.name()))objects.remove(n);
                    else { Grant old=prior.item().grant();Grant bad=new Grant(old.sequence(),old.expiresAt()+1,old.request());objects.put(n,new Blob(new Item(bad,prior.item().receipt()),true)); }
                    return result("OK");
                }
                case RESERVE: {
                    if(fenced)return result("FENCED");if(!authority)return result("UNKNOWN");Grant proposed=c.grant();
                    if(proposed==null||proposed.sequence()!=n||n<1||proposed.expiresAt()<1)return result("INVALID");
                    if(grants.containsKey(n))return result(grants.get(n).equals(proposed)?"OK":"KEY_REUSE");
                    if(n!=hwm+1)return result("GAP");
                    for(Grant g:grants.values())if(g.request().operation().equals(proposed.request().operation()))return result("KEY_REUSE");
                    grants.put(n,proposed);hwm=n;return result("OK");
                }
                case INSTALL: {
                    if(fenced)return result("FENCED");if(!authority)return result("UNKNOWN");if(n<=floor)return result("CLOSED");
                    if(!grants.containsKey(n))return result("MISSING");if(live.containsKey(n))return result("OK");if(n!=allocated+1)return result("GAP");
                    allocated=n;live.put(n,new Item(grants.get(n),null));return result("OK");
                }
                case ACCEPT: {
                    if(fenced)return result("FENCED");if(n<=floor)return result("CLOSED");Item item=live.get(n);if(item==null)return result("MISSING");
                    if(item.receipt()!=null)return new Outcome("OK",item.receipt());
                    if(closed.contains(n)||now>=item.grant().expiresAt())return result("CLOSED");Request request=item.grant().request();
                    for(Transactions.Error error:List.of(Transactions.Error.INVALID,Transactions.Error.CONFLICT))
                        if(new HistoryChecker().check(head,List.of(new HistoryChecker.Call(1,2,"WRITE",request,null,null,error))).verdict()==HistoryChecker.Verdict.LINEARIZABLE)return result(error.name());
                    Item observed=frame.observed().hot().rows().get(n);Receipt receipt=observed==null?null:observed.receipt();
                    if(receipt==null)throw new IllegalArgumentException("missing accepted receipt");
                    long ordinal=2L*accepted.size()+1;var writes=new ArrayList<>(accepted);
                    writes.add(new HistoryChecker.Call(ordinal,ordinal+1,"WRITE",request,null,receipt,null));
                    var verdict=new HistoryChecker().check(root,writes).verdict();
                    if(verdict==HistoryChecker.Verdict.INCONCLUSIVE)throw new BoundExceeded();
                    if(verdict!=HistoryChecker.Verdict.LINEARIZABLE)throw new IllegalArgumentException("invalid commercial receipt");
                    accepted.add(writes.getLast());head=receipt.after();live.put(n,new Item(item.grant(),receipt));return new Outcome("OK",receipt);
                }
                case SEAL: {
                    if(fenced)return result("FENCED");if(n<floor||n>allocated)return result("GAP");
                    for(long k=floor+1;k<=n;k++)if(!live.containsKey(k)||now<live.get(k).grant().expiresAt())return result("TOO_EARLY");
                    for(long k=floor+1;k<=n;k++)closed.add(k);return result("OK");
                }
                case PUBLISH: {
                    if(!authority)return result("UNKNOWN");if(!live.containsKey(n)||!closed.contains(n))return result("NOT_SEALED");
                    Item previous=sealedTruth.get(n);if(previous!=null&&!previous.equals(live.get(n)))return result("CONTRADICTION");
                    sealedTruth.put(n,live.get(n));return result("OK");
                }
                case COPY: {
                    if(!authority||!available)return result("UNKNOWN");Item item=live.get(n);
                    if(item==null||!closed.contains(n)||!item.equals(sealedTruth.get(n)))return result("NOT_SEALED");
                    Blob prior=objects.get(n);if(prior!=null&&prior.complete()&&!prior.item().equals(item))return result("CONTRADICTION");
                    if(c.mode()!=Mode.VOLATILE)objects.put(n,new Blob(item,c.mode()!=Mode.PARTIAL));return result("OK");
                }
                case CERTIFY: {
                    if(!authority)return result("UNKNOWN");if(n<certified||n>hwm)return result("GAP");
                    for(long k=certified+1;k<=n;k++)if(!complete(k))return result("UNCOVERED");certified=n;return result("OK");
                }
                case PRUNE: {
                    if(fenced)return result("FENCED");if(n<floor||n>allocated)return result("GAP");if(!authority||certified<n)return result("UNCOVERED");
                    for(long k=floor+1;k<=n;k++)if(!closed.contains(k)||!complete(k))return result("UNCOVERED");
                    for(long k=floor+1;k<=n;k++) { live.remove(k);closed.remove(k); }floor=n;return result("OK");
                }
                case RECOVER: {
                    if(!fenced)return result("NOT_FENCED");if(!authority||!available)return result("UNKNOWN");if(certified!=hwm)return result("UNCOVERED");
                    for(long k=1;k<=hwm;k++)if(!complete(k))return result("UNCOVERED");
                    // Derive the expected final state from validated acceptances, not the model's chain walker.
                    Set<Receipt> archived=new HashSet<>();for(Item i:sealedTruth.values())if(i.receipt()!=null)archived.add(i.receipt());
                    Set<Receipt> committed=new HashSet<>();for(HistoryChecker.Call w:accepted)committed.add(w.receipt());
                    if(!archived.equals(committed))return result("CONTRADICTION");
                    head=accepted.isEmpty()?root:accepted.getLast().receipt().after();allocated=hwm;floor=hwm;live.clear();closed.clear();fenced=false;return result("OK");
                }
            }
            throw new IllegalArgumentException("unknown command");
        }
    }
}
