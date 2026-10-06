package atlas.poc;

import java.nio.channels.FileChannel;
import java.nio.file.*;
import java.util.*;
import java.util.function.Consumer;
import static atlas.poc.ArchiveFenceModel.*;
import static atlas.poc.DraftProcessClient.JSON;

/** Bounded coordinator fixture: per-actor local journal, separate Cassandra partitions, local immutable archive. */
final class PersistentArchiveRecovery {
    static final List<Step> PHASES=List.of(Step.START,Step.FREEZE,Step.COPY,Step.VERIFY,Step.CERTIFY,Step.PRUNE,Step.INSTALL,Step.ACTIVATE);
    record Facts(long owner,RecoveryJournal.State frozen,Archive verified,RecoveryJournal.State pruned,RecoveryJournal.State installed) {}
    record Pending(Step phase,String store,RecoveryJournal.Request request,Archive object,Facts next) {}
    record Done(Pending pending,RecoveryJournal.Result result) {}
    record Actor(int version,UUID subject,int actor,int index,Facts facts,Pending pending,List<Done> done) {
        Actor { done=List.copyOf(done); }
    }
    record Envelope(String payload,String digest) {}
    final UUID subject;final Path actorFile,objects;final RecoveryJournal.Store root,hot;
    PersistentArchiveRecovery(UUID subject,Path actorFile,Path objects,RecoveryJournal.Store root,RecoveryJournal.Store hot) {
        this.subject=subject;this.actorFile=actorFile;this.objects=objects;this.root=root;this.hot=hot;
    }
    static byte[] encode(Actor a) throws Exception {
        String payload=JSON.writeValueAsString(a);
        return JSON.writeValueAsBytes(new Envelope(payload,SplitArchiveCheckpoint.hash(payload.getBytes(java.nio.charset.StandardCharsets.UTF_8))));
    }
    static Actor read(Path file,UUID subject) throws Exception {
        Envelope e=JSON.readValue(Files.readAllBytes(file),Envelope.class);
        if(!SplitArchiveCheckpoint.hash(e.payload().getBytes(java.nio.charset.StandardCharsets.UTF_8)).equals(e.digest()))throw new IllegalArgumentException("ACTOR_CHECKSUM");
        Actor a=JSON.readValue(e.payload(),Actor.class);
        if(a.version()!=1||!subject.equals(a.subject())||a.index()<0||a.index()>8||a.done().size()!=a.index()||a.facts()==null)
            throw new IllegalArgumentException("ACTOR_BINDING");
        if(a.pending()!=null&&(a.index()==8||a.pending().phase()!=PHASES.get(a.index())))throw new IllegalArgumentException("ACTOR_PHASE");
        return a;
    }
    static void immutable(Path path,byte[] bytes) throws Exception {
        Files.createDirectories(path.toAbsolutePath().getParent());
        Path tmp=Files.createTempFile(path.toAbsolutePath().getParent(),"archive-",".pending");
        try {
            try(var c=FileChannel.open(tmp,StandardOpenOption.WRITE)) {
                var b=java.nio.ByteBuffer.wrap(bytes);while(b.hasRemaining())c.write(b);c.force(true);
            }
            try { Files.createLink(path,tmp); }
            catch(FileAlreadyExistsException existing) {
                if(!Arrays.equals(bytes,Files.readAllBytes(path)))throw new IllegalArgumentException("IMMUTABLE_MISMATCH");
            }
            try(var d=FileChannel.open(path.toAbsolutePath().getParent(),StandardOpenOption.READ)) { d.force(true); }
        } finally { Files.deleteIfExists(tmp); }
    }
    static void initialize(Path file,UUID subject,int actor) throws Exception {
        if(Files.exists(file))throw new IllegalStateException("actor already initialized");
        immutable(file,encode(new Actor(1,subject,actor,0,new Facts(0,null,null,null,null),null,List.of())));
    }
    Path object(long owner) {return objects.resolve(subject.toString()).resolve(owner+".json");}
    Archive load(long owner) throws Exception {
        Archive a=JSON.readValue(Files.readAllBytes(object(owner)),Archive.class);
        if(a.owner()!=owner)throw new IllegalArgumentException("ARCHIVE_OWNER");return a;
    }
    static RecoveryJournal.State state(Object value) throws Exception {return new RecoveryJournal.State(UUID.randomUUID(),JSON.writeValueAsString(value));}
    static Hot hot(RecoveryJournal.State state) throws Exception {return JSON.readValue(state.value(),Hot.class);}
    static Root root(RecoveryJournal.State state) throws Exception {return JSON.readValue(state.value(),Root.class);}
    Archive expected(Facts f) throws Exception {
        Hot h=hot(f.frozen());var rows=new ArrayList<ArchiveFenceModel.Receipt>();
        if(h.floor()>0) {
            Archive base=load(h.base().owner());
            if(!certificate(base).equals(h.base())||base.sequence()!=h.floor())throw new IllegalArgumentException("ARCHIVE_BASE");
            rows.addAll(base.receipts());
        }
        rows.addAll(h.rows());int n=0,price=500;
        for(var row:rows) {if(row.sequence()!=++n||row.before()!=price)throw new IllegalArgumentException("ARCHIVE_GAP");price=row.after();}
        if(n!=h.sequence()||price!=h.cents())throw new IllegalArgumentException("ARCHIVE_ENDPOINT");
        return new Archive(f.owner(),h.generation(),n,price,rows);
    }
    private Pending write(Step step,String store,RecoveryJournal.State before,Object value,Facts f) throws Exception {
        return new Pending(step,store,new RecoveryJournal.Request(UUID.randomUUID(),before,state(value)),null,f);
    }
    Pending plan(Actor a) throws Exception {
        Facts f=a.facts();Step step=PHASES.get(a.index());Pending p;
        switch(step) {
            case START -> {
                var before=root.view().state();Root r=root(before);long owner=r.owner()+1;
                return write(step,"phase-authority",before,new Root(owner,r.certificate()),new Facts(owner,null,null,null,null));
            }
            case FREEZE -> {
                var before=hot.view().state();Hot h=hot(before);
                if(f.owner()<=h.epoch())throw new IllegalStateException("STALE_OWNER");
                p=write(step,"phase-hot",before,new Hot(f.owner(),h.generation()+1,false,h.sequence(),h.cents(),h.floor(),h.base(),h.rows()),f);
                return new Pending(step,p.store(),p.request(),null,new Facts(f.owner(),p.request().next(),null,null,null));
            }
            case COPY -> {return new Pending(step,"archive",null,expected(f),f);}
            case VERIFY -> {
                Archive actual=load(f.owner());if(!actual.equals(expected(f)))throw new IllegalArgumentException("ARCHIVE_COVERAGE");
                return new Pending(step,"archive",null,actual,new Facts(f.owner(),f.frozen(),actual,null,null));
            }
            case CERTIFY -> {
                var before=root.view().state();Root r=root(before);
                if(r.owner()!=f.owner())throw new IllegalStateException("STALE_OWNER");
                return write(step,"phase-authority",before,new Root(f.owner(),certificate(f.verified())),f);
            }
            case PRUNE -> {
                Hot h=hot(f.frozen());Archive v=f.verified();
                // Exact frozen state, never a newly read guard. Certification is the prior durable Done.
                if(a.done().get(4).result()==null||!a.done().get(4).result().code().equals("OK"))throw new IllegalArgumentException("CERTIFICATE_REQUIRED");
                p=write(step,"phase-hot",f.frozen(),new Hot(h.epoch(),h.generation()+1,false,h.sequence(),h.cents(),v.sequence(),certificate(v),List.of()),f);
                return new Pending(step,p.store(),p.request(),null,new Facts(f.owner(),f.frozen(),v,p.request().next(),null));
            }
            case INSTALL -> {
                Hot h=hot(f.pruned());Archive v=f.verified();
                p=write(step,"phase-hot",f.pruned(),new Hot(f.owner(),h.generation()+1,false,v.sequence(),v.cents(),v.sequence(),certificate(v),List.of()),f);
                return new Pending(step,p.store(),p.request(),null,new Facts(f.owner(),f.frozen(),v,f.pruned(),p.request().next()));
            }
            case ACTIVATE -> {
                Hot h=hot(f.installed());
                return write(step,"phase-hot",f.installed(),new Hot(h.epoch(),h.generation()+1,true,h.sequence(),h.cents(),h.floor(),h.base(),h.rows()),f);
            }
            default -> throw new IllegalArgumentException("not a recovery phase");
        }
    }
    Actor advance(Consumer<String> cut) throws Exception {
        // Serialize only the same actor's local checkpoint, never different owners or storage partitions.
        try(var channel=FileChannel.open(actorFile.resolveSibling(actorFile.getFileName()+".lock"),StandardOpenOption.CREATE,StandardOpenOption.WRITE);
            var lock=channel.lock()) {
            Actor a=read(actorFile,subject);if(a.index()==8)return a;
            if(a.pending()==null) {
                a=new Actor(1,subject,a.actor(),a.index(),a.facts(),plan(a),a.done());
                SplitArchiveCheckpoint.atomic(actorFile,encode(a));
            }
            cut.accept("AFTER_PENDING");Pending p=a.pending();RecoveryJournal.Result result=null;
            if(List.of(Step.CERTIFY,Step.PRUNE,Step.INSTALL,Step.ACTIVATE).contains(p.phase()) &&
                !load(p.next().owner()).equals(p.next().verified()))throw new IllegalArgumentException("ARCHIVE_PROOF_UNAVAILABLE");
            if(p.request()!=null) {
                result=(p.store().equals("phase-authority")?root:hot).apply(p.request());
                if(!result.code().equals("OK"))throw new IllegalStateException("PHASE_CONFLICT:"+result.code());
                if(!result.receipt().equals(new RecoveryJournal.Receipt(p.request(),p.request().expected(),p.request().next())))throw new IllegalStateException("RECEIPT_BINDING");
            } else if(p.phase()==Step.COPY)immutable(object(p.object().owner()),JSON.writeValueAsBytes(p.object()));
            else if(!load(p.object().owner()).equals(p.object()))throw new IllegalArgumentException("ARCHIVE_CHANGED");
            cut.accept("AFTER_EFFECT");var done=new ArrayList<>(a.done());done.add(new Done(p,result));
            Actor next=new Actor(1,subject,a.actor(),a.index()+1,p.next(),null,done);
            SplitArchiveCheckpoint.atomic(actorFile,encode(next));cut.accept("AFTER_LOCAL");return next;
        }
    }
    View view() throws Exception {
        var all=new HashMap<Long,Archive>();Path directory=objects.resolve(subject.toString());
        if(Files.isDirectory(directory))try(var files=Files.list(directory)) {
            for(Path file:files.filter(p->p.getFileName().toString().matches("[0-9]+\\.json")).toList()) {
                Archive a=JSON.readValue(Files.readAllBytes(file),Archive.class);all.put(a.owner(),a);
            }
        }
        return new View(root(root.view().state()),hot(hot.view().state()),all);
    }
}
