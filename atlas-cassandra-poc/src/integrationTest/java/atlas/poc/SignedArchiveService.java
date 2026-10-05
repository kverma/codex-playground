package atlas.poc;

import java.util.*;
import static atlas.poc.Retention.*;
import static atlas.poc.Transactions.*;
import static atlas.poc.ArchiveRecovery.*;

/** Serial integration fixture. Draft bindings and authority survive clients, not this server JVM. */
final class SignedArchiveService extends Retention.Base {
    static final class StateFailure extends RuntimeException {
        final String code;StateFailure(String code) { super(code);this.code=code; }
    }
    private final ArchiveCassandraContract.Scenario h;
    private final RetentionClock clock;
    private final Map<String,Issued> bindings=new HashMap<>();
    private final Map<Long,String> draftIds=new HashMap<>();
    SignedArchiveService(ArchiveCassandraContract.Scenario h,RetentionClock clock) {
        super(h.subject,RetentionContract.KEY,clock);this.h=h;this.clock=clock;
    }
    private void active() { if(h.store.read().hot().fenced())throw new StateFailure("FENCED"); }
    private Frame run(Command command) {
        Frame f=h.run(command);String code=f.outcome().code();
        if(!code.equals("OK"))throw new StateFailure(code.equals("CLOSED")?"REQUEST_TOO_OLD":code);
        return f;
    }
    public synchronized View view() {
        var persisted=h.store.read();Hot hot=persisted.hot();var entries=new HashMap<Long,Entry>();
        hot.rows().forEach((n,item)->entries.put(n,new Entry(item.grant().expiresAt(),item.grant().request().operation(),
            item.grant().request().hash(),item.receipt(),draftIds.get(n))));
        long expiry=h.external.grants.values().stream().mapToLong(Grant::expiresAt).max().orElse(0);
        return new View(persisted.guard(),hot.allocated(),hot.floor(),expiry,hot.head(),entries);
    }
    public synchronized Draft prepare(Request request) { active();return super.prepare(request); }
    public synchronized Issued issueDraft(Draft draft) {
        active();
        if(draft.anchor()<1||draft.expiresAt()<1||!matches(signDraft(draft),draft.signature()))throw new Failure(Code.INVALID_TICKET);
        String id=draftId(draft);Issued prior=bindings.get(id);View before=view();
        if(prior!=null && prior.ticket().sequence()>before.floor()) {
            if(!prior.request().hash().equals(draft.request().hash()))throw new Rejected(Transactions.Error.KEY_REUSE);
            run(h.cmd(Kind.INSTALL,prior.ticket().sequence()));return prior;
        }
        if(draft.anchor()<=before.floor()||clock.millis()>=draft.expiresAt())throw new Failure(Code.REQUEST_TOO_OLD);
        if(draft.anchor()>before.allocated()+1)throw new Failure(Code.INVALID_TICKET);
        if(before.allocated()-before.floor()>=CAPACITY)throw new Failure(Code.CAPACITY);
        long sequence=h.external.highWater+1,expiry=Math.max(draft.expiresAt(),before.lastExpiry());
        Request allocated=allocatedRequest(draft,operation(sequence));
        Ticket ticket=new Ticket(sequence,expiry,allocated.operation(),allocated.hash(),sign(sequence,expiry,allocated.operation(),allocated.hash()));
        Issued issued=new Issued(allocated,ticket);
        run(new Command(Kind.RESERVE,sequence,new Grant(sequence,expiry,allocated),null,Mode.NORMAL,true));
        bindings.put(id,issued);draftIds.put(sequence,id);
        run(h.cmd(Kind.INSTALL,sequence));return issued;
    }
    public synchronized Receipt commit(Ticket ticket,Request request) {
        verify(ticket,request);active();
        if(ticket.sequence()<=h.store.read().hot().floor())throw new Failure(Code.REQUEST_TOO_OLD);
        Grant grant=h.external.grants.get(ticket.sequence());
        if(grant==null||grant.expiresAt()!=ticket.expiresAt()||!grant.request().equals(request))throw new Failure(Code.INVALID_TICKET);
        return run(h.cmd(Kind.ACCEPT,ticket.sequence())).outcome().receipt();
    }
    protected boolean swap(View before,View after) { throw new UnsupportedOperationException("use explicit archive commands"); }
    public Ticket issue(Request request) { throw new UnsupportedOperationException("signed drafts only"); }
    public View compact() { throw new UnsupportedOperationException("archive coverage must precede pruning"); }
}
