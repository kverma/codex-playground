package atlas.poc;

import java.util.*;

/** Bounded two-store handoff model, not a production recovery or Cassandra adapter. */
final class RecoveryFenceModel {
    enum Step { READ, WRITE, START, FREEZE, PUBLISH, ACTIVATE }
    enum Broken { NONE, WRITE_WHILE_FROZEN, STALE_WRITE, STALE_ROOT, STALE_ACTIVATE, DROP_TAIL }
    record Command(int actor,Step step) {}
    record Receipt(int operation,int before,int after) {}
    record Image(int cents,List<Receipt> receipts) {
        Image { receipts=List.copyOf(receipts); }
    }
    record Hot(long epoch,long generation,boolean active,Image image) {}
    record Checkpoint(long epoch,Image image) {}
    record Root(long owner,Checkpoint checkpoint) {}
    record View(Root root,Hot hot) {}
    record Frame(Command command,String outcome,View observed) {}
    static final Image GENESIS=new Image(500,List.of());
    private Root root=new Root(0,new Checkpoint(0,GENESIS));
    private Hot hot=new Hot(0,0,true,GENESIS);
    private final Broken broken;
    private final Map<Integer,Hot> reads=new HashMap<>(),frozen=new HashMap<>();
    private final Map<Integer,Long> owners=new HashMap<>();
    private final Set<Integer> published=new HashSet<>();
    boolean triggered;
    RecoveryFenceModel() { this(Broken.NONE); }
    RecoveryFenceModel(Broken broken) { this.broken=broken; }
    View view() { return new View(root,hot); }
    Frame execute(Command c) { return new Frame(c,apply(c),view()); }
    private String apply(Command c) {
        int actor=c.actor();
        switch(c.step()) {
            case READ -> {
                if(!hot.active())return "FENCED";
                reads.put(actor,hot);return "OK";
            }
            case WRITE -> {
                Hot read=reads.get(actor);if(read==null)return "NOT_READY";
                if(!hot.active()&&broken!=Broken.WRITE_WHILE_FROZEN)return "FENCED";
                boolean stale=read.generation()!=hot.generation();
                if(stale&&broken!=Broken.STALE_WRITE&&broken!=Broken.WRITE_WHILE_FROZEN)return "STALE";
                if(stale||!hot.active())triggered=true;
                int target=400+actor;var receipts=new ArrayList<>(hot.image().receipts());
                receipts.add(new Receipt(actor,hot.image().cents(),target));
                hot=new Hot(hot.epoch(),hot.generation()+1,hot.active(),new Image(target,receipts));return "OK";
            }
            case START -> {
                long owner=root.owner()+1;root=new Root(owner,root.checkpoint());owners.put(actor,owner);return "OK";
            }
            case FREEZE -> {
                Long owner=owners.get(actor);if(owner==null)return "NOT_READY";
                if(owner<=hot.epoch())return "STALE";
                // This atomic effect touches the hot partition only. No authority read is hidden here.
                hot=new Hot(owner,hot.generation()+1,false,hot.image());frozen.put(actor,hot);return "OK";
            }
            case PUBLISH -> {
                Hot capture=frozen.get(actor);if(capture==null)return "NOT_READY";
                long owner=owners.get(actor);
                if(root.owner()!=owner&&broken!=Broken.STALE_ROOT)return "STALE";
                if(root.owner()!=owner)triggered=true;
                Image image=capture.image();
                if(broken==Broken.DROP_TAIL&&!image.receipts().isEmpty()) { image=GENESIS;triggered=true; }
                root=new Root(owner,new Checkpoint(owner,image));published.add(actor);return "OK";
            }
            case ACTIVATE -> {
                Hot capture=frozen.get(actor);if(capture==null||!published.contains(actor))return "NOT_READY";
                if((hot.active()||hot.generation()!=capture.generation())&&broken!=Broken.STALE_ACTIVATE)return "STALE";
                if(hot.active()||hot.generation()!=capture.generation())triggered=true;
                // Proof is the earlier successful publication; this CAS touches only the hot partition.
                hot=new Hot(capture.epoch(),hot.generation()+1,true,capture.image());return "OK";
            }
        }
        throw new AssertionError();
    }
}
