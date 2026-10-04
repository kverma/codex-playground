package atlas.poc;

import java.nio.charset.StandardCharsets;
import java.util.*;

/** Bounded one-subject, one-option fixture with three independently versioned TermGroups. */
public final class Transactions {
    private Transactions() {}
    public enum Group { ECONOMICS, ELIGIBILITY, ROYALTY }
    public enum Error { CONFLICT, INVALID, KEY_REUSE, INDETERMINATE }
    public static final class Rejected extends RuntimeException {
        public final Error error;
        public Rejected(Error error) { super(error.name()); this.error=error; }
        public Rejected(Throwable cause) { super(cause); this.error=Error.INDETERMINATE; }
    }
    public record Cell(UUID version,String value) {}
    public record Snapshot(UUID generation,long epoch,int budget,int payloadBytes,Map<Group,Cell> cells) {
        public Snapshot { cells=Map.copyOf(cells); }
        public String value(Group group) { return cells.get(group).value(); }
        public String intentId() {
            StringBuilder text=new StringBuilder("atlas-terms-v1|epoch="+epoch);
            for(Group group:Group.values()) text.append('|').append(group).append('=').append(value(group));
            return Intent.hash(text.toString());
        }
    }
    public record Request(UUID operation,long epoch,Map<Group,UUID> reads,Map<Group,String> updates,Long admittedEpoch,Integer admittedBudget) {
        public Request {
            Objects.requireNonNull(operation); reads=Map.copyOf(reads); updates=Map.copyOf(updates);
        }
        public String hash() {
            StringBuilder text=new StringBuilder("atlas-request-v1|"+epoch+"|"+admittedEpoch+"|"+admittedBudget);
            for(Group group:Group.values()) {
                UUID version=reads.get(group); String value=updates.get(group);
                text.append('|').append(group).append(':').append(version).append(':');
                text.append(value==null ? "-" : value.length()+":"+value);
            }
            return Intent.hash(text.toString());
        }
    }
    public record Receipt(UUID operation,String requestHash,Snapshot before,Snapshot after) {}
    public interface Store extends AutoCloseable {
        Snapshot read(); Receipt commit(Request request);
        default Receipt resolve(Request request) { return commit(request); }
        default void close() {}
    }
    public static Snapshot initial() {
        var cells=new EnumMap<Group,Cell>(Group.class);
        cells.put(Group.ECONOMICS,new Cell(UUID.randomUUID(),"500"));
        cells.put(Group.ELIGIBILITY,new Cell(UUID.randomUUID(),"NEW"));
        cells.put(Group.ROYALTY,new Cell(UUID.randomUUID(),"1500"));
        return new Snapshot(UUID.randomUUID(),1,128,bytes(cells),cells);
    }
    public static Set<Group> requiredReads(Request request) {
        var groups=EnumSet.noneOf(Group.class); groups.addAll(request.updates().keySet());
        if(request.admittedEpoch()!=null || request.admittedBudget()!=null) groups.addAll(EnumSet.allOf(Group.class));
        if(groups.contains(Group.ECONOMICS)||groups.contains(Group.ELIGIBILITY)) {
            groups.add(Group.ECONOMICS); groups.add(Group.ELIGIBILITY);
        }
        return groups;
    }
    public static Request edit(UUID operation,Snapshot observed,Map<Group,String> updates) {
        Request shape=new Request(operation,observed.epoch(),Map.of(),updates,null,null);
        var reads=new EnumMap<Group,UUID>(Group.class);
        for(Group group:requiredReads(shape)) reads.put(group,observed.cells().get(group).version());
        return new Request(operation,observed.epoch(),reads,updates,null,null);
    }
    public static Request admit(UUID operation,Snapshot observed,long epoch,int budget) {
        var reads=new EnumMap<Group,UUID>(Group.class);
        for(Group group:Group.values()) reads.put(group,observed.cells().get(group).version());
        return new Request(operation,observed.epoch(),reads,Map.of(),epoch,budget);
    }
    public static int bytes(Map<Group,Cell> cells) {
        int total=0;
        for(Group group:Group.values()) total+=(group.name()+"="+cells.get(group).value()+";").getBytes(StandardCharsets.UTF_8).length;
        return total;
    }
    public static void check(Snapshot current,Request request) {
        boolean admission=request.admittedEpoch()!=null || request.admittedBudget()!=null;
        if(request.epoch()<1 || (!admission && request.updates().isEmpty()) ||
           (admission && (request.admittedEpoch()==null || request.admittedBudget()==null || !request.updates().isEmpty())) ||
           !request.reads().keySet().containsAll(requiredReads(request))) throw new Rejected(Error.INVALID);
        if(current.epoch()!=request.epoch()) throw new Rejected(Error.CONFLICT);
        for(var entry:request.reads().entrySet())
            if(!current.cells().get(entry.getKey()).version().equals(entry.getValue())) throw new Rejected(Error.CONFLICT);
        var cells=new EnumMap<Group,Cell>(current.cells());
        request.updates().forEach((group,value)->cells.put(group,new Cell(current.cells().get(group).version(),value)));
        try {
            int cents=Integer.parseInt(cells.get(Group.ECONOMICS).value());
            int royalty=Integer.parseInt(cells.get(Group.ROYALTY).value());
            String eligibility=cells.get(Group.ELIGIBILITY).value();
            if(cents<0||cents>1_000_000||royalty<0||royalty>10_000||
               !Integer.toString(cents).equals(cells.get(Group.ECONOMICS).value())||
               !Integer.toString(royalty).equals(cells.get(Group.ROYALTY).value())||
               !Set.of("NEW","NEW,CHURNED").contains(eligibility)||
               (eligibility.equals("NEW,CHURNED") && cents>500)) throw new Rejected(Error.INVALID);
        } catch(NumberFormatException e) { throw new Rejected(Error.INVALID); }
        int budget=admission?request.admittedBudget():current.budget();
        if(budget<1||budget>4096 || (admission && request.admittedEpoch()<=current.epoch()) || bytes(cells)>budget)
            throw new Rejected(Error.INVALID);
    }
    public static Snapshot apply(Snapshot current,Request request) {
        check(current,request);
        var cells=new EnumMap<Group,Cell>(current.cells());
        request.updates().forEach((group,value)->cells.put(group,new Cell(UUID.randomUUID(),value)));
        return new Snapshot(UUID.randomUUID(),request.admittedEpoch()==null?current.epoch():request.admittedEpoch(),
            request.admittedBudget()==null?current.budget():request.admittedBudget(),bytes(cells),cells);
    }
    public static Receipt exact(Receipt receipt,Request request) {
        if(!receipt.requestHash().equals(request.hash())) throw new Rejected(Error.KEY_REUSE);
        return receipt;
    }
    public static final class Model implements Store {
        private Snapshot state=initial();
        private final Map<UUID,Receipt> receipts=new HashMap<>();
        public synchronized Snapshot read() { return state; }
        public synchronized Receipt commit(Request request) {
            Receipt old=receipts.get(request.operation());
            if(old!=null) return exact(old,request);
            Snapshot next=apply(state,request);
            Receipt receipt=new Receipt(request.operation(),request.hash(),state,next);
            receipts.put(request.operation(),receipt); state=next; return receipt;
        }
    }
}
