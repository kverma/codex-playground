package atlas.poc;

import java.util.*;
import static atlas.poc.Transactions.*;

/** Separate serial crash-boundary model. No remote archive or distributed fence is implemented. */
public final class ArchiveRecovery {
    private ArchiveRecovery() {}
    public enum Kind { RESERVE, INSTALL, ACCEPT, CLOCK, SEAL, PUBLISH, COPY, CERTIFY, PRUNE, SAVE, RESTORE, CRASH, RECOVER, AUTHORITY, ARCHIVE, DAMAGE, VIEW }
    public enum Mode { NORMAL, BEFORE, AFTER, PARTIAL, VOLATILE }
    public enum Broken { NONE, PRUNE_WITHOUT_COVERAGE, TRUST_ACK, TRUST_PARTIAL, SPLIT_DELETE, RESTORE_ACTIVE, REWIND_AUTHORITY, ACCEPT_CLOSED, IGNORE_CORRUPTION, OMIT_TAIL }
    public record Grant(long sequence,long expiresAt,Request request) {}
    public record Item(Grant grant,Receipt receipt) {}
    public record Blob(Item item,boolean complete) {}
    public record Hot(long allocated,long floor,Snapshot head,Map<Long,Item> rows,Set<Long> sealed,boolean fenced) {
        public Hot { rows=Map.copyOf(rows);sealed=Set.copyOf(sealed); }
    }
    public record Observation(long now,long highWater,long certifiedFloor,Map<Long,Grant> grants,Set<Long> manifests,
                              Map<Long,Blob> archive,Hot hot,boolean authorityAvailable,boolean archiveAvailable) {
        public Observation { grants=Map.copyOf(grants);manifests=Set.copyOf(manifests);archive=Map.copyOf(archive); }
    }
    public record Command(Kind kind,long sequence,Grant grant,String name,Mode mode,boolean available) {}
    public record Outcome(String code,Receipt receipt) {}
    public record Frame(Command command,Outcome outcome,Observation observed) {}
    public static final class Storage {
        final Snapshot genesis;
        Hot hot;
        long now,highWater,certifiedFloor;
        boolean authorityAvailable=true,archiveAvailable=true;
        final Map<Long,Grant> grants=new HashMap<>();
        final Map<Long,String> manifests=new HashMap<>();
        final Map<Long,Blob> archive=new HashMap<>();
        final Set<Long> acknowledgements=new HashSet<>();
        final Map<String,Hot> backups=new HashMap<>();
        public Storage(Snapshot genesis) { this.genesis=genesis;hot=new Hot(0,0,genesis,Map.of(),Set.of(),false); }
    }
    public static class Model {
        final Storage s;
        final Broken broken;
        public boolean triggered;
        public Model(Storage storage) { this(storage,Broken.NONE); }
        public Model(Storage storage,Broken broken) { s=storage;this.broken=broken; }
        public Observation observe() { return new Observation(s.now,s.highWater,s.certifiedFloor,s.grants,s.manifests.keySet(),s.archive,s.hot,s.authorityAvailable,s.archiveAvailable); }
        private Outcome out(String code) { return new Outcome(code,null); }
        private Hot hot(long allocated,long floor,Snapshot head,Map<Long,Item> rows,Set<Long> sealed,boolean fenced) { return new Hot(allocated,floor,head,rows,sealed,fenced); }
        private void fence() { Hot h=s.hot;s.hot=hot(h.allocated(),h.floor(),h.head(),h.rows(),h.sealed(),true); }
        private String digest(Item i) {
            Receipt r=i.receipt();
            return Intent.hash("archive-slot-v1|"+i.grant().sequence()+"|"+i.grant().expiresAt()+"|"+i.grant().request().operation()+"|"+i.grant().request().hash()+"|"+
                (r==null?"CLOSED_UNACCEPTED":r.operation()+"|"+r.requestHash()+"|"+TransactionCodec.encode(r.before())+"|"+TransactionCodec.encode(r.after())));
        }
        private boolean covered(long n) {
            Blob b=s.archive.get(n);
            return s.archiveAvailable&&b!=null&&b.complete()&&Objects.equals(s.manifests.get(n),digest(b.item()))&&Objects.equals(s.grants.get(n),b.item().grant());
        }
        public Frame execute(Command c) {
            Outcome result;
            if(c.mode()==Mode.BEFORE) result=out("UNKNOWN");
            else {
                try { result=step(c); } catch(Rejected e) { result=out(e.error.name()); }
                if(c.mode()==Mode.AFTER&&result.code().equals("OK")) result=out("UNKNOWN");
            }
            return new Frame(c,result,observe());
        }
        private Outcome step(Command c) {
            Hot h=s.hot;long n=c.sequence();
            switch(c.kind()) {
                case VIEW: return out("OK");
                case CLOCK: s.now=n;return out("OK");
                case AUTHORITY: s.authorityAvailable=c.available();return out("OK");
                case ARCHIVE: s.archiveAvailable=c.available();return out("OK");
                case SAVE: s.backups.put(c.name(),h);return out("OK");
                case CRASH: s.acknowledgements.clear();fence();return out("OK");
                case RESTORE: {
                    fence();Hot backup=s.backups.get(c.name());if(backup==null)return out("MISSING");
                    s.hot=hot(backup.allocated(),backup.floor(),backup.head(),backup.rows(),backup.sealed(),true);
                    if(broken==Broken.RESTORE_ACTIVE) { s.hot=backup;triggered=true; }
                    if(broken==Broken.REWIND_AUTHORITY) { s.highWater=backup.allocated();s.certifiedFloor=backup.floor();triggered=true; }
                    return out("OK");
                }
                case DAMAGE: {
                    Blob b=s.archive.get(n);if(b==null)return out("MISSING");
                    if("remove".equals(c.name()))s.archive.remove(n);
                    else { Grant g=b.item().grant();s.archive.put(n,new Blob(new Item(new Grant(g.sequence(),g.expiresAt()+1,g.request()),b.item().receipt()),true)); }
                    return out("OK");
                }
                case RESERVE: {
                    if(h.fenced())return out("FENCED");if(!s.authorityAvailable)return out("UNKNOWN");
                    Grant g=c.grant();if(g==null||g.sequence()!=n||n<1||g.expiresAt()<1)return out("INVALID");
                    Grant prior=s.grants.get(n);if(prior!=null)return out(prior.equals(g)?"OK":"KEY_REUSE");
                    if(n!=s.highWater+1)return out("GAP");
                    if(s.grants.values().stream().anyMatch(x->x.request().operation().equals(g.request().operation())))return out("KEY_REUSE");
                    s.grants.put(n,g);s.highWater=n;return out("OK");
                }
                case INSTALL: {
                    if(h.fenced())return out("FENCED");if(!s.authorityAvailable)return out("UNKNOWN");
                    if(n<=h.floor())return out("CLOSED");Grant g=s.grants.get(n);if(g==null)return out("MISSING");
                    if(h.rows().containsKey(n))return out("OK");if(n!=h.allocated()+1)return out("GAP");
                    var rows=new HashMap<>(h.rows());rows.put(n,new Item(g,null));s.hot=hot(n,h.floor(),h.head(),rows,h.sealed(),false);return out("OK");
                }
                case ACCEPT: {
                    if(h.fenced())return out("FENCED");
                    if(n<=h.floor()) {
                        if(broken!=Broken.ACCEPT_CLOSED)return out("CLOSED");
                        Receipt replay=new Receipt(s.grants.get(n).request().operation(),s.grants.get(n).request().hash(),h.head(),h.head());
                        var resurrected=new HashMap<>(h.rows());resurrected.put(n,new Item(s.grants.get(n),replay));
                        s.hot=hot(h.allocated(),h.floor(),h.head(),resurrected,h.sealed(),false);triggered=true;return new Outcome("OK",replay);
                    }
                    Item i=h.rows().get(n);if(i==null)return out("MISSING");
                    if(i.receipt()!=null)return new Outcome("OK",i.receipt());
                    if(h.sealed().contains(n)||s.now>=i.grant().expiresAt())return out("CLOSED");
                    Receipt receipt=new Receipt(i.grant().request().operation(),i.grant().request().hash(),h.head(),Transactions.apply(h.head(),i.grant().request()));
                    var rows=new HashMap<>(h.rows());rows.put(n,new Item(i.grant(),receipt));s.hot=hot(h.allocated(),h.floor(),receipt.after(),rows,h.sealed(),false);return new Outcome("OK",receipt);
                }
                case SEAL: {
                    if(h.fenced())return out("FENCED");if(n<h.floor()||n>h.allocated())return out("GAP");
                    for(long k=h.floor()+1;k<=n;k++)if(!h.rows().containsKey(k)||s.now<h.rows().get(k).grant().expiresAt())return out("TOO_EARLY");
                    var sealed=new HashSet<>(h.sealed());for(long k=h.floor()+1;k<=n;k++)sealed.add(k);
                    s.hot=hot(h.allocated(),h.floor(),h.head(),h.rows(),sealed,false);return out("OK");
                }
                case PUBLISH: {
                    if(!s.authorityAvailable)return out("UNKNOWN");Item i=h.rows().get(n);
                    if(i==null||!h.sealed().contains(n))return out("NOT_SEALED");
                    String d=digest(i),prior=s.manifests.get(n);if(prior!=null&&!prior.equals(d))return out("CONTRADICTION");
                    s.manifests.put(n,d);return out("OK");
                }
                case COPY: {
                    if(!s.authorityAvailable||!s.archiveAvailable)return out("UNKNOWN");Item i=h.rows().get(n);
                    if(i==null||!h.sealed().contains(n)||!Objects.equals(s.manifests.get(n),digest(i)))return out("NOT_SEALED");
                    Blob prior=s.archive.get(n);if(prior!=null&&prior.complete()&&!prior.item().equals(i))return out("CONTRADICTION");
                    s.acknowledgements.add(n);
                    if(c.mode()!=Mode.VOLATILE)s.archive.put(n,new Blob(i,c.mode()!=Mode.PARTIAL));
                    return out("OK");
                }
                case CERTIFY: {
                    if(!s.authorityAvailable)return out("UNKNOWN");if(n<s.certifiedFloor||n>s.highWater)return out("GAP");
                    for(long k=s.certifiedFloor+1;k<=n;k++) {
                        if(covered(k))continue;
                        if(broken==Broken.TRUST_ACK&&s.acknowledgements.contains(k)) { triggered=true;continue; }
                        if(broken==Broken.TRUST_PARTIAL&&s.archive.containsKey(k)) { triggered=true;continue; }
                        return out("UNCOVERED");
                    }
                    s.certifiedFloor=n;return out("OK");
                }
                case PRUNE: {
                    if(h.fenced())return out("FENCED");if(n<h.floor()||n>h.allocated())return out("GAP");
                    boolean safe=s.authorityAvailable&&s.certifiedFloor>=n;
                    for(long k=h.floor()+1;k<=n;k++)safe&=h.sealed().contains(k)&&covered(k);
                    if(!safe&&broken!=Broken.PRUNE_WITHOUT_COVERAGE)return out("UNCOVERED");
                    if(!safe)triggered=true;
                    var rows=new HashMap<>(h.rows());var sealed=new HashSet<>(h.sealed());
                    for(long k=h.floor()+1;k<=n;k++) { rows.remove(k);sealed.remove(k); }
                    if(broken==Broken.SPLIT_DELETE&&n>h.floor()) { rows=new HashMap<>(h.rows());triggered=true; }
                    s.hot=hot(h.allocated(),n,h.head(),rows,sealed,false);return out("OK");
                }
                case RECOVER: {
                    if(!h.fenced())return out("NOT_FENCED");
                    if(!s.authorityAvailable||!s.archiveAvailable)return out("UNKNOWN");
                    long target=s.highWater;
                    if(broken==Broken.OMIT_TAIL&&s.certifiedFloor<target) { target=s.certifiedFloor;triggered=true; }
                    else if(s.certifiedFloor!=target)return out("UNCOVERED");
                    var pending=new ArrayList<Receipt>();
                    for(long k=1;k<=target;k++) {
                        if(!covered(k)) {
                            if(broken!=Broken.IGNORE_CORRUPTION||!s.archive.containsKey(k))return out("UNCOVERED");
                            triggered=true;
                        }
                        Receipt r=s.archive.get(k).item().receipt();if(r!=null)pending.add(r);
                    }
                    Snapshot head=s.genesis;
                    while(!pending.isEmpty()) {
                        Snapshot current=head;var matches=pending.stream().filter(r->r.before().equals(current)).toList();
                        if(matches.size()!=1)return out("CONTRADICTION");
                        Receipt r=matches.getFirst();pending.remove(r);head=r.after();
                    }
                    s.hot=hot(target,target,head,Map.of(),Set.of(),false);return out("OK");
                }
            }
            throw new IllegalStateException();
        }
    }
}
