package atlas.poc;

import java.time.Clock;
import java.util.*;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import static atlas.poc.Transactions.*;

/** Separate retention candidate; retry identity is a server-issued ticket, not a caller timestamp. */
public final class Retention {
    private Retention() {}
    public static final int CAPACITY=16;
    public static final long LIFETIME_MILLIS=3_600_000;
    public enum Code { INVALID_TICKET, REQUEST_TOO_OLD, CAPACITY, INDETERMINATE }
    public static final class Failure extends RuntimeException {
        public final Code code;
        public Failure(Code code) { super(code.name()); this.code=code; }
        public String outcome() { return "UNKNOWN"; }
    }
    public record Ticket(long sequence,long expiresAt,UUID operation,String requestHash,String signature) {}
    public record Entry(long expiresAt,UUID operation,String requestHash,Receipt receipt) {}
    public record View(UUID generation,long allocated,long floor,long lastExpiry,Snapshot snapshot,Map<Long,Entry> entries) {
        public View { entries=Map.copyOf(entries); }
    }
    public interface Store extends AutoCloseable {
        View view(); Request edit(Map<Group,String> updates); Ticket issue(Request request); Receipt commit(Ticket ticket,Request request); View compact();
        default void close() {}
    }
    public abstract static class Base implements Store {
        protected final UUID subject;
        private final byte[] key;
        private final Clock clock;
        protected Base(UUID subject,byte[] key,Clock clock) {
            if(key.length<32) throw new IllegalArgumentException("ticket key requires 32 bytes");
            this.subject=subject; this.key=key.clone(); this.clock=clock;
        }
        protected abstract boolean swap(View before,View after);
        protected View initialView() { return new View(UUID.randomUUID(),0,0,0,initial(),Map.of()); }
        private String sign(long sequence,long expiry,UUID operation,String hash) {
            try {
                Mac mac=Mac.getInstance("HmacSHA256"); mac.init(new SecretKeySpec(key,"HmacSHA256"));
                return HexFormat.of().formatHex(mac.doFinal(("atlas-ticket-v1|"+subject+"|"+sequence+"|"+expiry+"|"+operation+"|"+hash).getBytes(StandardCharsets.UTF_8)));
            } catch(java.security.GeneralSecurityException e) { throw new IllegalStateException(e); }
        }
        private void verify(Ticket ticket,Request request) {
            if(ticket.sequence()<1||ticket.expiresAt()<1||!request.operation().equals(ticket.operation())||!request.hash().equals(ticket.requestHash())||
                !MessageDigest.isEqual(sign(ticket.sequence(),ticket.expiresAt(),ticket.operation(),ticket.requestHash()).getBytes(StandardCharsets.US_ASCII),ticket.signature().getBytes(StandardCharsets.US_ASCII)))
                throw new Failure(Code.INVALID_TICKET);
        }
        private View next(View before,long allocated,long floor,long expiry,Snapshot snapshot,Map<Long,Entry> entries) {
            return new View(UUID.randomUUID(),allocated,floor,expiry,snapshot,entries);
        }
        private UUID operation(long sequence) { return new UUID(subject.getMostSignificantBits(),sequence); }
        public Request edit(Map<Group,String> updates) {
            View observed=view(); return Transactions.edit(operation(Math.addExact(observed.allocated(),1)),observed.snapshot(),updates);
        }
        private Ticket ticket(long sequence,Entry entry) {
            return new Ticket(sequence,entry.expiresAt(),entry.operation(),entry.requestHash(),sign(sequence,entry.expiresAt(),entry.operation(),entry.requestHash()));
        }
        public Ticket issue(Request request) {
            for(int attempt=0;attempt<32;attempt++) {
                View before=view();
                long proposed=request.operation().getLeastSignificantBits();
                if(proposed<1||!request.operation().equals(operation(proposed))) throw new Failure(Code.INVALID_TICKET);
                if(proposed<=before.floor()) throw new Failure(Code.REQUEST_TOO_OLD);
                Entry prior=before.entries().get(proposed);
                if(prior!=null) {
                    if(!prior.requestHash().equals(request.hash())) throw new Rejected(Transactions.Error.KEY_REUSE);
                    return ticket(proposed,prior);
                }
                if(proposed!=before.allocated()+1) throw new Failure(Code.INVALID_TICKET);
                if(before.allocated()-before.floor()>=CAPACITY) throw new Failure(Code.CAPACITY);
                long sequence=Math.addExact(before.allocated(),1);
                long expiry=Math.max(Math.addExact(clock.millis(),LIFETIME_MILLIS),before.lastExpiry());
                var entries=new HashMap<>(before.entries()); entries.put(sequence,new Entry(expiry,request.operation(),request.hash(),null));
                if(swap(before,next(before,sequence,before.floor(),expiry,before.snapshot(),entries)))
                    return new Ticket(sequence,expiry,request.operation(),request.hash(),sign(sequence,expiry,request.operation(),request.hash()));
            }
            throw new Failure(Code.INDETERMINATE);
        }
        public Receipt commit(Ticket ticket,Request request) {
            verify(ticket,request);
            for(int attempt=0;attempt<32;attempt++) {
                View before=view();
                if(ticket.sequence()<=before.floor()) throw new Failure(Code.REQUEST_TOO_OLD);
                Entry entry=before.entries().get(ticket.sequence());
                if(entry==null||entry.expiresAt()!=ticket.expiresAt()||!entry.operation().equals(ticket.operation())||!entry.requestHash().equals(ticket.requestHash()))
                    throw new Failure(Code.INVALID_TICKET);
                if(entry.receipt()!=null) return entry.receipt();
                if(clock.millis()>=ticket.expiresAt()) throw new Failure(Code.REQUEST_TOO_OLD);
                Snapshot after;
                try { after=apply(before.snapshot(),request); }
                catch(Rejected e) {
                    // Resolve an exact retry that raced between the partition read and validation.
                    if(!view().generation().equals(before.generation())) continue;
                    throw e;
                }
                Receipt receipt=new Receipt(request.operation(),request.hash(),before.snapshot(),after);
                var entries=new HashMap<>(before.entries()); entries.put(ticket.sequence(),new Entry(entry.expiresAt(),entry.operation(),entry.requestHash(),receipt));
                if(swap(before,next(before,before.allocated(),before.floor(),before.lastExpiry(),after,entries))) return receipt;
            }
            throw new Failure(Code.INDETERMINATE);
        }
        public View compact() {
            for(int attempt=0;attempt<32;attempt++) {
                View before=view(); long floor=before.floor(), now=clock.millis();
                // Issuance deadlines are monotonic, so only a contiguous expired prefix is retired.
                while(floor<before.allocated() && before.entries().get(floor+1).expiresAt()<=now) floor++;
                if(floor==before.floor()) return before;
                var entries=new HashMap<>(before.entries()); final long retired=floor; entries.keySet().removeIf(n->n<=retired);
                View after=next(before,before.allocated(),floor,before.lastExpiry(),before.snapshot(),entries);
                if(swap(before,after)) return after;
            }
            throw new Failure(Code.INDETERMINATE);
        }
    }
    public static final class Model extends Base {
        private View state;
        public Model(UUID subject,byte[] key,Clock clock) { super(subject,key,clock); state=initialView(); }
        public synchronized View view() { return state; }
        protected synchronized boolean swap(View before,View after) {
            if(!state.generation().equals(before.generation())) return false;
            state=after; return true;
        }
    }
}
