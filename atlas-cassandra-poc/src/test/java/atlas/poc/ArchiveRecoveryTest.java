package atlas.poc;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import java.nio.file.*;
import java.util.*;
import static atlas.poc.ArchiveRecovery.*;
import static atlas.poc.ArchiveRecoveryChecker.Verdict.*;
import static atlas.poc.Transactions.Group.*;
import static org.junit.jupiter.api.Assertions.*;

class ArchiveRecoveryTest {
    static Command command(Kind kind,long n) { return new Command(kind,n,null,null,Mode.NORMAL,true); }
    static Command mode(Command c,Mode mode) { return new Command(c.kind(),c.sequence(),c.grant(),c.name(),mode,c.available()); }
    static Command named(Kind kind,long n,String name) { return new Command(kind,n,null,name,Mode.NORMAL,true); }
    static Command available(Kind kind,boolean up) { return new Command(kind,0,null,null,Mode.NORMAL,up); }
    static final class Harness {
        final long seed;
        final Transactions.Snapshot root=Transactions.initial();
        final Storage storage=new Storage(root);
        final List<Frame> frames=new ArrayList<>();
        ArchiveRecovery.Model model;
        Harness(long seed) { this(seed,Broken.NONE); }
        Harness(long seed,Broken broken) { this.seed=seed;model=new ArchiveRecovery.Model(storage,broken); }
        Frame run(Command c) { Frame f=model.execute(c);frames.add(f);return f; }
        Frame run(Kind kind,long n) { return run(command(kind,n)); }
        Frame ok(Command c) { Frame f=run(c);assertEquals("OK",f.outcome().code(),c.kind().name());return f; }
        Frame ok(Kind kind,long n) { return ok(command(kind,n)); }
        void code(String expected,Command c) { assertEquals(expected,run(c).outcome().code(),c.kind().name()); }
        void code(String expected,Kind k,long n) { code(expected,command(k,n)); }
        Command reservation(long n,Map<Transactions.Group,String> values) {
            var r=Transactions.edit(new UUID(seed,n),model.observe().hot().head(),values);
            return new Command(Kind.RESERVE,n,new Grant(n,10,r),null,Mode.NORMAL,true);
        }
        void base() {
            ok(named(Kind.SAVE,0,"old"));
            ok(reservation(1,Map.of(ECONOMICS,"400")));ok(Kind.INSTALL,1);
            ok(reservation(2,Map.of(ROYALTY,"2000")));ok(Kind.INSTALL,2);
            ok(Kind.ACCEPT,2);ok(Kind.ACCEPT,1); // Allocation order is not acceptance order.
            ok(reservation(3,Map.of(ROYALTY,"2500")));ok(Kind.INSTALL,3); // Explicit unaccepted slot.
        }
        void seal() { ok(Kind.CLOCK,10);ok(Kind.SEAL,3);for(long n=1;n<=3;n++)ok(Kind.PUBLISH,n); }
        void archive() { for(long n=1;n<=3;n++)ok(Kind.COPY,n);ok(Kind.CERTIFY,3); }
        void prune() { ok(Kind.PRUNE,3); }
        void restore() { ok(named(Kind.RESTORE,0,"old")); }
        ArchiveRecoveryChecker.Evidence evidence() { return new ArchiveRecoveryChecker.Evidence(seed,root,List.copyOf(frames),new ArchiveRecoveryChecker().check(root,frames)); }
        void saveFailure() throws Exception {
            Path p=Path.of("build/evidence/archive-failures","seed-"+seed+".json");Files.createDirectories(p.getParent());
            new ObjectMapper().writerWithDefaultPrettyPrinter().writeValue(p.toFile(),evidence());
        }
        void save(String folder,ArchiveRecoveryChecker.Verdict verdict) throws Exception {
            var evidence=evidence();Path p=Path.of("build/evidence",folder,"seed-"+seed+".json");Files.createDirectories(p.getParent());
            var mapper=new ObjectMapper();mapper.writerWithDefaultPrettyPrinter().writeValue(p.toFile(),evidence);
            assertEquals(verdict,evidence.result().verdict(),evidence.result().toString());
            var replay=mapper.readValue(p.toFile(),ArchiveRecoveryChecker.Evidence.class);
            assertEquals(verdict,new ArchiveRecoveryChecker().check(replay.genesis(),replay.frames()).verdict(),"saved trace replay");
        }
    }
    @Test void restoresOldSnapshotFromCompleteArchiveInAcceptanceOrderAndNeverReopensIds() throws Exception {
        Harness h=new Harness(1);h.base();var expected=h.model.observe().hot().head();h.seal();h.archive();h.prune();h.restore();
        h.code("FENCED",Kind.ACCEPT,1);h.code("FENCED",h.reservation(4,Map.of(ROYALTY,"3000")));
        h.ok(Kind.RECOVER,0);assertEquals(expected,h.model.observe().hot().head());assertEquals(3,h.model.observe().hot().floor());
        h.ok(Kind.CLOCK,0);h.code("CLOSED",Kind.ACCEPT,1);h.code("CLOSED",Kind.ACCEPT,3);h.code("CLOSED",Kind.INSTALL,1);
        h.ok(h.reservation(4,Map.of(ROYALTY,"3000")));h.ok(Kind.INSTALL,4);h.ok(Kind.ACCEPT,4);
        assertEquals(4,h.model.observe().highWater());h.save("archive-contract",VALID);
    }
    @Test void everyCrossStoreBoundaryRecoversExactLostReplies() throws Exception {
        int seed=10;
        for(Kind fault:List.of(Kind.RESERVE,Kind.INSTALL,Kind.ACCEPT,Kind.SEAL,Kind.PUBLISH,Kind.COPY,Kind.CERTIFY,Kind.PRUNE)) {
            for(Mode mode:List.of(Mode.BEFORE,Mode.AFTER)) {
                Harness h=new Harness(seed++);boolean[] injected={false};
                java.util.function.Consumer<Command> send=c->{
                    if(!injected[0]&&c.kind()==fault) { injected[0]=true;h.code("UNKNOWN",mode(c,mode)); }
                    h.ok(c);
                };
                send.accept(h.reservation(1,Map.of(ROYALTY,"2000")));send.accept(command(Kind.INSTALL,1));send.accept(command(Kind.ACCEPT,1));
                send.accept(command(Kind.CLOCK,10));send.accept(command(Kind.SEAL,1));send.accept(command(Kind.PUBLISH,1));send.accept(command(Kind.COPY,1));
                send.accept(command(Kind.CERTIFY,1));send.accept(command(Kind.PRUNE,1));
                assertTrue(injected[0]);assertEquals(1,h.model.observe().hot().floor());h.save("archive-boundaries",VALID);
            }
        }
    }
    @Test void successfulRpcAndPartialObjectCannotAuthorizePruning() throws Exception {
        int seed=30;
        for(Mode fault:List.of(Mode.PARTIAL,Mode.VOLATILE)) {
            Harness h=new Harness(seed++);h.base();h.code("NOT_SEALED",Kind.COPY,1);h.seal();
            h.ok(mode(command(Kind.COPY,1),fault));h.code("UNCOVERED",Kind.CERTIFY,1);h.code("UNCOVERED",Kind.PRUNE,1);
            assertEquals(3,h.model.observe().hot().rows().size());h.archive();
            h.ok(available(Kind.ARCHIVE,false));h.code("UNCOVERED",Kind.PRUNE,3);h.ok(available(Kind.ARCHIVE,true));h.prune();h.save("archive-contract",VALID);
        }
    }
    @Test void crashAfterArchiveBeforePruneRecoversThroughFreshProcessFacade() throws Exception {
        Harness h=new Harness(40);h.base();h.seal();for(long n=1;n<=3;n++)h.ok(Kind.COPY,n);
        var expected=h.model.observe().hot().head();h.ok(Kind.CRASH,0);h.model=new ArchiveRecovery.Model(h.storage);
        h.code("FENCED",Kind.ACCEPT,3);h.ok(Kind.CERTIFY,3);h.ok(Kind.RECOVER,0);h.prune();h.prune();
        assertEquals(expected,h.model.observe().hot().head());assertEquals(0,h.model.observe().hot().rows().size());h.save("archive-contract",VALID);
    }
    @Test void unavailableMissingOrChangedRecoveryFactsKeepAuthoringFenced() throws Exception {
        int seed=50;
        for(String damage:List.of("remove","corrupt")) {
            Harness h=new Harness(seed++);h.base();h.seal();h.archive();h.prune();h.restore();
            h.ok(available(Kind.AUTHORITY,false));h.code("UNKNOWN",Kind.RECOVER,0);h.code("FENCED",Kind.ACCEPT,1);
            h.ok(available(Kind.AUTHORITY,true));h.ok(available(Kind.ARCHIVE,false));h.code("UNKNOWN",Kind.RECOVER,0);
            h.ok(available(Kind.ARCHIVE,true));h.ok(named(Kind.DAMAGE,1,damage));h.code("UNCOVERED",Kind.RECOVER,0);
            assertTrue(h.model.observe().hot().fenced());h.code("FENCED",h.reservation(4,Map.of(ROYALTY,"3000")));h.save("archive-contract",VALID);
        }
    }
    @Test void reservedGapAndUnarchivedAcceptanceCannotDisappearDuringRestore() throws Exception {
        int seed=60;
        for(boolean accepted:List.of(false,true)) {
            Harness h=new Harness(seed++);h.base();h.seal();h.archive();h.prune();h.ok(Kind.CLOCK,0);
            h.ok(h.reservation(4,Map.of(ROYALTY,"3000")));
            if(accepted) { h.ok(Kind.INSTALL,4);h.ok(Kind.ACCEPT,4); }
            h.restore();h.code("UNCOVERED",Kind.RECOVER,0);assertEquals(4,h.model.observe().highWater());
            assertTrue(h.model.observe().hot().fenced());h.save("archive-contract",VALID);
        }
    }
    @Test void nineActualBrokenImplementationsFailAndSavedCounterexamplesReplay() throws Exception {
        for(Broken broken:Broken.values()) {
            if(broken==Broken.NONE)continue;
            Harness h=new Harness(100+broken.ordinal(),broken);h.base();h.seal();
            switch(broken) {
                case PRUNE_WITHOUT_COVERAGE -> h.run(Kind.PRUNE,3);
                case TRUST_ACK,TRUST_PARTIAL -> {
                    for(long n=1;n<=3;n++)h.ok(mode(command(Kind.COPY,n),broken==Broken.TRUST_ACK?Mode.VOLATILE:Mode.PARTIAL));
                    h.run(Kind.CERTIFY,3);h.run(Kind.PRUNE,3);
                }
                case SPLIT_DELETE -> { h.archive();h.run(Kind.PRUNE,3); }
                case RESTORE_ACTIVE,REWIND_AUTHORITY -> { h.archive();h.prune();h.restore(); }
                case ACCEPT_CLOSED -> { h.archive();h.prune();h.run(Kind.ACCEPT,1); }
                case IGNORE_CORRUPTION -> { h.archive();h.prune();h.ok(named(Kind.DAMAGE,1,"corrupt"));h.restore();h.run(Kind.RECOVER,0); }
                case OMIT_TAIL -> {
                    h.archive();h.prune();h.ok(Kind.CLOCK,0);h.ok(h.reservation(4,Map.of(ROYALTY,"3000")));h.ok(Kind.INSTALL,4);h.ok(Kind.ACCEPT,4);
                    h.restore();h.run(Kind.RECOVER,0);
                }
                default -> throw new AssertionError(broken);
            }
            assertTrue(h.model.triggered,broken.name());h.save("archive-mutants",INVALID);
        }
    }
    @Test void oneHundredGeneratedSerialCrashTracesPassWithSeparateProgressChecks() throws Exception {
        for(long seed=1000;seed<1100;seed++) {
            Harness h=new Harness(seed);
            try {
            Random random=new Random(seed);h.ok(named(Kind.SAVE,0,"old"));
            java.util.function.Consumer<Command> send=c->{
                int choice=random.nextInt(3);Mode mode=choice==0?Mode.BEFORE:choice==1?Mode.AFTER:Mode.NORMAL;
                Frame f=h.run(mode(c,mode));if(f.outcome().code().equals("UNKNOWN"))h.ok(c);else assertEquals("OK",f.outcome().code());
            };
            send.accept(h.reservation(1,Map.of(ECONOMICS,random.nextBoolean()?"400":"500")));send.accept(command(Kind.INSTALL,1));
            send.accept(h.reservation(2,Map.of(ROYALTY,Integer.toString(1000+random.nextInt(1500)))));send.accept(command(Kind.INSTALL,2));
            long first=random.nextBoolean()?1:2;send.accept(command(Kind.ACCEPT,first));send.accept(command(Kind.ACCEPT,3-first));
            send.accept(h.reservation(3,Map.of(ROYALTY,"2500")));send.accept(command(Kind.INSTALL,3));
            var expected=h.model.observe().hot().head();h.ok(Kind.CLOCK,10);send.accept(command(Kind.SEAL,3));
            for(long n=1;n<=3;n++)send.accept(command(Kind.PUBLISH,n));
            if(seed%3==0) { h.ok(mode(command(Kind.COPY,1),Mode.PARTIAL));h.code("UNCOVERED",Kind.CERTIFY,1); }
            for(long n=1;n<=3;n++)send.accept(command(Kind.COPY,n));
            if(seed%4==0) { h.ok(Kind.CRASH,0);h.model=new ArchiveRecovery.Model(h.storage); }
            send.accept(command(Kind.CERTIFY,3));
            if(h.model.observe().hot().fenced())h.ok(Kind.RECOVER,0);
            send.accept(command(Kind.PRUNE,3));h.restore();h.code("FENCED",Kind.ACCEPT,1);h.ok(Kind.RECOVER,0);
            assertFalse(h.model.observe().hot().fenced());assertEquals(expected,h.model.observe().hot().head());assertEquals(3,h.model.observe().highWater());
            assertEquals(3,h.model.observe().hot().floor());assertTrue(h.model.observe().hot().rows().isEmpty());h.code("CLOSED",Kind.ACCEPT,3);
            h.save("archive-generated",VALID);
            } catch(AssertionError | Exception failure) {
                try { h.saveFailure(); } catch(Exception saving) { failure.addSuppressed(saving); }
                throw failure;
            }
        }
    }
    @Test void semanticHistorySurvivesPruningAndClockChangesDoNotCreateAnIntent() throws Exception {
        Harness h=new Harness(2000);h.ok(named(Kind.SAVE,0,"old"));h.ok(h.reservation(1,Map.of(ECONOMICS,"400")));h.ok(Kind.INSTALL,1);
        var january=h.ok(Kind.ACCEPT,1).outcome().receipt();h.ok(h.reservation(2,Map.of(ELIGIBILITY,"NEW,CHURNED")));h.ok(Kind.INSTALL,2);
        var february=h.ok(Kind.ACCEPT,2).outcome().receipt();assertNotEquals(january.after().intentId(),february.after().intentId());
        h.ok(Kind.CLOCK,10);h.ok(Kind.SEAL,2);for(long n=1;n<=2;n++) { h.ok(Kind.PUBLISH,n);h.ok(Kind.COPY,n); }
        h.ok(Kind.CERTIFY,2);h.ok(Kind.PRUNE,2);h.restore();h.ok(Kind.RECOVER,0);h.ok(Kind.CLOCK,100);
        assertEquals(february.after().intentId(),h.model.observe().hot().head().intentId());
        assertEquals("NEW",h.model.observe().archive().get(1L).item().receipt().after().value(ELIGIBILITY));
        assertEquals("NEW,CHURNED",h.model.observe().archive().get(2L).item().receipt().after().value(ELIGIBILITY));h.save("archive-contract",VALID);
    }
    @Test void traceBoundIsExplicitlyInconclusive() {
        Harness h=new Harness(3000);h.ok(Kind.VIEW,0);assertEquals(INCONCLUSIVE,new ArchiveRecoveryChecker(0).check(h.root,h.frames).verdict());
    }
}
