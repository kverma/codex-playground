package atlas.poc;

import java.util.*;
import java.security.MessageDigest;
import java.nio.charset.StandardCharsets;
import static atlas.poc.ArchiveFenceModel.*;

/** Independent ledger specification; never invokes candidate transitions, reconstruction or hashing. */
final class ArchiveFenceChecker {
    enum Verdict { VALID, INVALID, INCONCLUSIVE }
    record Result(Verdict verdict,int checked,String reason) {}
    private final int bound;
    ArchiveFenceChecker() {this(24);}
    ArchiveFenceChecker(int bound) {this.bound=bound;}
    private Certificate manifest(Archive value) {
        var parts=new ArrayList<String>();for(Receipt r:value.receipts())parts.add(String.join(":",Integer.toString(r.sequence()),Integer.toString(r.operation()),Integer.toString(r.before()),Integer.toString(r.after()))+";");
        String payload=String.join("|","archive-cut-v1",Long.toString(value.owner()),Long.toString(value.fence()),Integer.toString(value.sequence()),Integer.toString(value.cents()),String.join("",parts));
        try {return new Certificate(value.owner(),value.fence(),value.sequence(),value.cents(),HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(payload.getBytes(StandardCharsets.UTF_8))));}
        catch(Exception e) {throw new AssertionError(e);}
    }
    Result check(List<Frame> frames) {
        if(frames.size()>bound)return new Result(Verdict.INCONCLUSIVE,0,"step bound");
        long owner=0,epoch=0,generation=0;int cents=500,floor=0;boolean active=true;
        Certificate root=null,base=null;var ledger=new ArrayList<Receipt>();var objects=new HashMap<Long,Archive>();
        var tickets=new HashMap<Integer,Long>();var reads=new HashMap<Integer,Long>();var cuts=new HashMap<Integer,Archive>();
        var checkedCopies=new HashSet<Integer>();var certified=new HashSet<Integer>();
        var pruned=new HashMap<Integer,Long>();var installed=new HashMap<Integer,Long>();int index=0;
        for(Frame frame:frames) {
            Command c=frame.command();int actor=c.actor();String outcome;
            switch(c.step()) {
                case READ -> {outcome=active?"OK":"FENCED";if(active)reads.put(actor,generation);}
                case WRITE -> {
                    outcome=!reads.containsKey(actor)?"NOT_READY":!active?"FENCED":reads.get(actor)!=generation?"STALE":"OK";
                    if(outcome.equals("OK")) {ledger.add(new Receipt(ledger.size()+1,actor,cents,400+actor));cents=400+actor;generation++;}
                }
                case START -> {owner++;tickets.put(actor,owner);outcome="OK";}
                case FREEZE -> {
                    outcome=!tickets.containsKey(actor)?"NOT_READY":tickets.get(actor)<=epoch?"STALE":"OK";
                    if(outcome.equals("OK")) {epoch=tickets.get(actor);generation++;active=false;cuts.put(actor,new Archive(epoch,generation,ledger.size(),cents,ledger));}
                }
                case COPY -> {
                    outcome=!cuts.containsKey(actor)?"NOT_READY":objects.containsKey(tickets.get(actor))?"EXISTS":"OK";
                    if(outcome.equals("OK")) {
                        Archive cut=cuts.get(actor);var receipts=new ArrayList<>(cut.receipts());long fence=cut.fence();int price=cut.cents();
                        switch(c.copy()) {
                            case NORMAL -> {}
                            case OMIT_TAIL -> {if(!receipts.isEmpty())receipts.remove(receipts.size()-1);}
                            case WRONG_FENCE -> fence--;
                            case WRONG_PRICE -> price++;
                        }
                        objects.put(tickets.get(actor),new Archive(cut.owner(),fence,cut.sequence(),price,receipts));
                    }
                }
                case VERIFY -> {
                    outcome=!cuts.containsKey(actor)||!objects.containsKey(tickets.get(actor))?"NOT_READY":!cuts.get(actor).equals(objects.get(tickets.get(actor)))?"COVERAGE_MISMATCH":"OK";
                    if(outcome.equals("OK"))checkedCopies.add(actor);
                }
                case CERTIFY -> {
                    outcome=!checkedCopies.contains(actor)?"NOT_READY":tickets.get(actor)!=owner?"STALE":"OK";
                    if(outcome.equals("OK")) {root=manifest(cuts.get(actor));certified.add(actor);}
                }
                case PRUNE -> {
                    outcome=!certified.contains(actor)?"NOT_READY":active||epoch!=tickets.get(actor)||generation!=cuts.get(actor).fence()?"STALE":"OK";
                    if(outcome.equals("OK")) {floor=cuts.get(actor).sequence();base=manifest(cuts.get(actor));generation++;pruned.put(actor,generation);}
                }
                case INSTALL -> {
                    outcome=!pruned.containsKey(actor)?"NOT_READY":active||epoch!=tickets.get(actor)||generation!=pruned.get(actor)?"STALE":"OK";
                    if(outcome.equals("OK")) {generation++;installed.put(actor,generation);}
                }
                case ACTIVATE -> {
                    outcome=!installed.containsKey(actor)?"NOT_READY":active||epoch!=tickets.get(actor)||generation!=installed.get(actor)?"STALE":"OK";
                    if(outcome.equals("OK")) {generation++;active=true;}
                }
                default -> throw new AssertionError();
            }
            if(!frame.outcome().equals(outcome))return new Result(Verdict.INVALID,index,"outcome at "+c.step());
            View v=frame.observed();Hot h=v.hot();
            if(v.root().owner()!=owner||!Objects.equals(root,v.root().certificate())||!objects.equals(v.archive())||
                h.epoch()!=epoch||h.generation()!=generation||h.active()!=active||h.sequence()!=ledger.size()||h.cents()!=cents||h.floor()!=floor||
                !Objects.equals(base,h.base())||!h.rows().equals(ledger.subList(floor,ledger.size())))return new Result(Verdict.INVALID,index,"state at "+c.step());
            // Accepted history is reconstructed independently from the certified base plus actual remaining rows.
            var reconstructed=new ArrayList<Receipt>();
            if(floor>0) {
                Archive archived=v.archive().get(base.owner());
                if(archived==null||!manifest(archived).equals(base))return new Result(Verdict.INVALID,index,"missing certified coverage");
                reconstructed.addAll(archived.receipts());
            }
            reconstructed.addAll(h.rows());
            if(!reconstructed.equals(ledger))return new Result(Verdict.INVALID,index,"accepted receipt lost");
            index++;
        }
        return new Result(Verdict.VALID,index,"independent archive/fence ledger specification");
    }
}
