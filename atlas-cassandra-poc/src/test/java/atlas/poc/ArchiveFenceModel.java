package atlas.poc;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.*;

/** Test-only three-store protocol. Archive ACK assumes immutable complete objects, not a provider guarantee. */
final class ArchiveFenceModel {
    enum Step { READ, WRITE, START, FREEZE, COPY, VERIFY, CERTIFY, PRUNE, INSTALL, ACTIVATE, LOSE_ARCHIVE }
    enum Copy { NORMAL, OMIT_TAIL, WRONG_FENCE, WRONG_PRICE }
    enum Broken { NONE, VERIFY_BAD_COPY, CERTIFY_STALE, PRUNE_UNCERTIFIED, PRUNE_STALE, INSTALL_OLD_IMAGE, INSTALL_OLD_GENERATION, ACTIVATE_WITHOUT_INSTALL }
    record Command(int actor,Step step,Copy copy) { Command(int actor,Step step) {this(actor,step,Copy.NORMAL);} }
    record Receipt(int sequence,int operation,int before,int after) {}
    record Archive(long owner,long fence,int sequence,int cents,List<Receipt> receipts) { Archive {receipts=List.copyOf(receipts);} }
    record Certificate(long owner,long fence,int sequence,int cents,String digest) {}
    record Hot(long epoch,long generation,boolean active,int sequence,int cents,int floor,Certificate base,List<Receipt> rows) { Hot {rows=List.copyOf(rows);} }
    record Root(long owner,Certificate certificate) {}
    record View(Root root,Hot hot,Map<Long,Archive> archive) { View {archive=Map.copyOf(archive);} }
    record Frame(Command command,String outcome,View observed) {}
    private Root root=new Root(0,null);
    private Hot hot=new Hot(0,0,true,0,500,0,null,List.of());
    private final Map<Long,Archive> archive=new HashMap<>();
    private final Map<Integer,Long> tickets=new HashMap<>(),reads=new HashMap<>(),pruned=new HashMap<>(),installed=new HashMap<>();
    private final Map<Integer,Hot> captures=new HashMap<>();
    private final Map<Integer,Archive> verified=new HashMap<>();
    private final Set<Integer> certified=new HashSet<>();
    private final Broken broken;
    boolean triggered;
    ArchiveFenceModel() {this(Broken.NONE);}
    ArchiveFenceModel(Broken broken) {this.broken=broken;}
    View view() {return new View(root,hot,archive);}
    Frame execute(Command command) {return new Frame(command,apply(command),view());}
    static Certificate certificate(Archive a) {
        String text="archive-cut-v1|"+a.owner()+"|"+a.fence()+"|"+a.sequence()+"|"+a.cents()+"|";
        for(Receipt r:a.receipts())text+=r.sequence()+":"+r.operation()+":"+r.before()+":"+r.after()+";";
        try {return new Certificate(a.owner(),a.fence(),a.sequence(),a.cents(),HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(text.getBytes(StandardCharsets.UTF_8))));}
        catch(Exception e) {throw new AssertionError(e);}
    }
    private Archive expected(int actor) {
        Hot capture=captures.get(actor);var all=new ArrayList<Receipt>();
        if(capture.floor()>0) {
            Archive base=archive.get(capture.base().owner());
            if(base==null||!certificate(base).equals(capture.base())||base.sequence()!=capture.floor())throw new IllegalStateException("invalid immutable base");
            all.addAll(base.receipts());
        }
        all.addAll(capture.rows());
        int sequence=0,cents=500;
        for(Receipt r:all) {if(r.sequence()!=++sequence||r.before()!=cents)throw new IllegalStateException("coverage gap");cents=r.after();}
        if(sequence!=capture.sequence()||cents!=capture.cents())throw new IllegalStateException("coverage endpoint");
        return new Archive(tickets.get(actor),capture.generation(),sequence,cents,all);
    }
    private boolean current(int actor,long generation) {return !hot.active()&&hot.epoch()==tickets.get(actor)&&hot.generation()==generation;}
    private String apply(Command command) {
        int actor=command.actor();
        switch(command.step()) {
            case READ -> {if(!hot.active())return "FENCED";reads.put(actor,hot.generation());return "OK";}
            case WRITE -> {
                if(!reads.containsKey(actor))return "NOT_READY";
                if(!hot.active())return "FENCED";
                if(reads.get(actor)!=hot.generation())return "STALE";
                var rows=new ArrayList<>(hot.rows());int sequence=hot.sequence()+1,cents=400+actor;
                rows.add(new Receipt(sequence,actor,hot.cents(),cents));
                hot=new Hot(hot.epoch(),hot.generation()+1,true,sequence,cents,hot.floor(),hot.base(),rows);return "OK";
            }
            case START -> {long ticket=root.owner()+1;root=new Root(ticket,root.certificate());tickets.put(actor,ticket);return "OK";}
            case FREEZE -> {
                Long ticket=tickets.get(actor);if(ticket==null)return "NOT_READY";if(ticket<=hot.epoch())return "STALE";
                hot=new Hot(ticket,hot.generation()+1,false,hot.sequence(),hot.cents(),hot.floor(),hot.base(),hot.rows());captures.put(actor,hot);return "OK";
            }
            case COPY -> {
                if(!captures.containsKey(actor))return "NOT_READY";
                long key=tickets.get(actor);if(archive.containsKey(key))return "EXISTS";
                Archive a=expected(actor);
                if(command.copy()==Copy.OMIT_TAIL&&!a.receipts().isEmpty())a=new Archive(a.owner(),a.fence(),a.sequence(),a.cents(),a.receipts().subList(0,a.receipts().size()-1));
                if(command.copy()==Copy.WRONG_FENCE)a=new Archive(a.owner(),a.fence()-1,a.sequence(),a.cents(),a.receipts());
                if(command.copy()==Copy.WRONG_PRICE)a=new Archive(a.owner(),a.fence(),a.sequence(),a.cents()+1,a.receipts());
                archive.put(key,a);return "OK";
            }
            case VERIFY -> {
                if(!captures.containsKey(actor)||!archive.containsKey(tickets.get(actor)))return "NOT_READY";
                Archive actual=archive.get(tickets.get(actor));boolean good=actual.equals(expected(actor));
                if(!good&&broken!=Broken.VERIFY_BAD_COPY)return "COVERAGE_MISMATCH";
                if(!good)triggered=true;verified.put(actor,actual);return "OK";
            }
            case LOSE_ARCHIVE -> {
                // Explicit environment fault: violates the retained immutable-object premise.
                if(!tickets.containsKey(actor)||!archive.containsKey(tickets.get(actor)))return "NOT_READY";
                archive.remove(tickets.get(actor));return "OK";
            }
            case CERTIFY -> {
                if(!verified.containsKey(actor))return "NOT_READY";
                if(root.owner()!=tickets.get(actor)&&broken!=Broken.CERTIFY_STALE)return "STALE";
                if(root.owner()!=tickets.get(actor))triggered=true;
                root=new Root(root.owner(),certificate(verified.get(actor)));certified.add(actor);return "OK";
            }
            case PRUNE -> {
                if(!verified.containsKey(actor))return "NOT_READY";
                if(!certified.contains(actor)&&broken!=Broken.PRUNE_UNCERTIFIED)return "NOT_READY";
                if(!current(actor,captures.get(actor).generation())&&broken!=Broken.PRUNE_STALE)return "STALE";
                if(!certified.contains(actor)||!current(actor,captures.get(actor).generation()))triggered=true;
                Archive a=verified.get(actor);var rows=hot.rows().stream().filter(r->r.sequence()>a.sequence()).toList();
                hot=new Hot(hot.epoch(),hot.generation()+1,hot.active(),hot.sequence(),hot.cents(),a.sequence(),certificate(a),rows);pruned.put(actor,hot.generation());return "OK";
            }
            case INSTALL -> {
                if(!pruned.containsKey(actor))return "NOT_READY";
                if(!current(actor,pruned.get(actor)))return "STALE";
                Archive a=verified.get(actor);int cents=a.cents(),sequence=a.sequence();long generation=hot.generation()+1;
                if(broken==Broken.INSTALL_OLD_IMAGE) {cents=500;sequence=0;triggered=true;}
                if(broken==Broken.INSTALL_OLD_GENERATION) {generation=0;triggered=true;}
                hot=new Hot(tickets.get(actor),generation,false,sequence,cents,a.sequence(),certificate(a),List.of());installed.put(actor,generation);return "OK";
            }
            case ACTIVATE -> {
                Long version=installed.get(actor);
                if(version==null&&broken==Broken.ACTIVATE_WITHOUT_INSTALL&&pruned.containsKey(actor)) {version=pruned.get(actor);triggered=true;}
                if(version==null)return "NOT_READY";if(!current(actor,version))return "STALE";
                hot=new Hot(hot.epoch(),hot.generation()+1,true,hot.sequence(),hot.cents(),hot.floor(),hot.base(),hot.rows());return "OK";
            }
        }
        throw new AssertionError();
    }
}
