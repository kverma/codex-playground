package atlas.poc;

import java.util.*;
import static atlas.poc.RecoveryFenceModel.*;

/** Independent step specification: no calls to model transitions, caches, or view(). */
final class RecoveryFenceChecker {
    enum Verdict { VALID, INVALID, INCONCLUSIVE }
    record Result(Verdict verdict,int checked,String reason) {}
    private final int bound;
    RecoveryFenceChecker() { this(16); }
    RecoveryFenceChecker(int bound) { this.bound=bound; }
    Result check(List<Frame> frames) {
        if(frames.size()>bound)return new Result(Verdict.INCONCLUSIVE,0,"step bound");
        long owner=0,epoch=0,revision=0,checkpointEpoch=0;
        int cents=500;boolean active=true;
        List<Receipt> ledger=new ArrayList<>(),checkpointLedger=new ArrayList<>();int checkpointCents=500;
        Map<Integer,Long> readVersions=new HashMap<>(),tickets=new HashMap<>(),cuts=new HashMap<>();
        Map<Integer,List<Receipt>> cutLedgers=new HashMap<>();Map<Integer,Integer> cutPrices=new HashMap<>();
        Set<Integer> published=new HashSet<>();
        int checked=0;
        for(Frame f:frames) {
            int actor=f.command().actor();String expected;
            switch(f.command().step()) {
                case READ -> {
                    expected=active?"OK":"FENCED";if(active)readVersions.put(actor,revision);
                }
                case WRITE -> {
                    Long version=readVersions.get(actor);
                    expected=version==null?"NOT_READY":!active?"FENCED":version!=revision?"STALE":"OK";
                    if(expected.equals("OK")) {
                        int target=400+actor;ledger.add(new Receipt(actor,cents,target));cents=target;revision++;
                    }
                }
                case START -> { owner++;tickets.put(actor,owner);expected="OK"; }
                case FREEZE -> {
                    Long ticket=tickets.get(actor);
                    expected=ticket==null?"NOT_READY":ticket<=epoch?"STALE":"OK";
                    if(expected.equals("OK")) {
                        epoch=ticket;revision++;active=false;cuts.put(actor,revision);
                        cutLedgers.put(actor,List.copyOf(ledger));cutPrices.put(actor,cents);
                    }
                }
                case PUBLISH -> {
                    expected=!cuts.containsKey(actor)?"NOT_READY":tickets.get(actor)!=owner?"STALE":"OK";
                    if(expected.equals("OK")) {
                        checkpointEpoch=tickets.get(actor);checkpointCents=cutPrices.get(actor);
                        checkpointLedger=new ArrayList<>(cutLedgers.get(actor));published.add(actor);
                    }
                }
                case ACTIVATE -> {
                    expected=!published.contains(actor)?"NOT_READY":active||!Objects.equals(cuts.get(actor),revision)?"STALE":"OK";
                    if(expected.equals("OK")) { active=true;revision++; }
                }
                default -> throw new AssertionError();
            }
            if(!expected.equals(f.outcome()))return new Result(Verdict.INVALID,checked,"outcome mismatch at "+f.command());
            View seen=f.observed();Hot h=seen.hot();Root r=seen.root();
            if(r.owner()!=owner||r.checkpoint().epoch()!=checkpointEpoch||r.checkpoint().image().cents()!=checkpointCents||
               !r.checkpoint().image().receipts().equals(checkpointLedger)||h.epoch()!=epoch||h.generation()!=revision||h.active()!=active||
               h.image().cents()!=cents||!h.image().receipts().equals(ledger))
                return new Result(Verdict.INVALID,checked,"state mismatch at "+f.command());
            checked++;
        }
        return new Result(Verdict.VALID,checked,"independent per-store handoff specification");
    }
}
