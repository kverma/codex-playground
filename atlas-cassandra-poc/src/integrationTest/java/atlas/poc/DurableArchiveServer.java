package atlas.poc;

import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.file.*;
import java.util.*;
import static atlas.poc.ArchiveRecovery.*;
import static atlas.poc.Retention.*;
import static atlas.poc.DraftProcessClient.*;

/** One request per real server JVM. Local single-writer checkpoint contract, not an Atlas service. */
public final class DurableArchiveServer {
    record Facts(int version,UUID subject,Transactions.Snapshot genesis,long now,long highWater,long certifiedFloor,
                 Map<Long,Grant> grants,Map<Long,String> manifests,Map<Long,Blob> archive,Map<String,Hot> backups,
                 Map<String,Issued> bindings) {
        static Facts capture(UUID subject,Storage s,Map<String,Issued> bindings) {
            return new Facts(1,subject,s.genesis,s.now,s.highWater,s.certifiedFloor,
                Map.copyOf(s.grants),Map.copyOf(s.manifests),Map.copyOf(s.archive),Map.copyOf(s.backups),Map.copyOf(bindings));
        }
        Storage storage() {
            Storage s=new Storage(genesis);s.now=now;s.highWater=highWater;s.certifiedFloor=certifiedFloor;
            s.grants.putAll(grants);s.manifests.putAll(manifests);s.archive.putAll(archive);s.backups.putAll(backups);return s;
        }
    }
    record Envelope(int version,byte[] payload,String sha256) {}
    record Input(UUID subject,Message message,Command command,String cut) {}
    record Report(long pid,String phase,List<Frame> frames,Facts facts,Reply reply,Hot hot) {}
    static byte[] envelope(Facts facts) throws Exception {
        byte[] payload=JSON.writeValueAsBytes(facts);
        return JSON.writeValueAsBytes(new Envelope(1,payload,HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256").digest(payload))));
    }
    static Facts load(Path path,UUID subject) throws Exception {
        return decode(Files.readAllBytes(path),subject);
    }
    static Facts decode(byte[] bytes,UUID subject) throws Exception {
        Envelope e=JSON.readValue(bytes,Envelope.class);
        if(e.version()!=1||e.payload()==null||!Objects.equals(e.sha256(),HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256").digest(e.payload()))))
            throw new IllegalArgumentException("CHECKPOINT_INVALID: version or checksum");
        Facts f=JSON.readValue(e.payload(),Facts.class);
        if(f.version()!=1||!subject.equals(f.subject())||f.genesis()==null||f.highWater()<0||f.certifiedFloor()<0||f.certifiedFloor()>f.highWater()
                ||f.grants().size()!=f.highWater()||f.bindings().size()!=f.highWater())throw new IllegalArgumentException("CHECKPOINT_INVALID: authority or bindings");
        Set<Long> bound=new HashSet<>();
        for(Issued i:f.bindings().values()) {
            Ticket t=i.ticket();Grant g=f.grants().get(t.sequence());
            if(!bound.add(t.sequence())||g==null||t.sequence()<1||t.sequence()>f.highWater()||g.sequence()!=t.sequence()
                    ||g.expiresAt()!=t.expiresAt()||!g.request().equals(i.request())||!t.operation().equals(i.request().operation())
                    ||!t.requestHash().equals(i.request().hash()))throw new IllegalArgumentException("CHECKPOINT_INVALID: grant binding");
        }
        return f;
    }
    static void publish(Path path,Facts facts,Runnable beforeMove,Runnable afterMove) throws Exception {
        Path temp=path.resolveSibling(path.getFileName()+".pending");
        try(var ch=FileChannel.open(temp,StandardOpenOption.CREATE,StandardOpenOption.TRUNCATE_EXISTING,StandardOpenOption.WRITE)) {
            ByteBuffer bytes=ByteBuffer.wrap(envelope(facts));while(bytes.hasRemaining())ch.write(bytes);ch.force(true);
        }
        beforeMove.run();Files.move(temp,path,StandardCopyOption.ATOMIC_MOVE,StandardCopyOption.REPLACE_EXISTING);
        try(var directory=FileChannel.open(path.toAbsolutePath().getParent(),StandardOpenOption.READ)) { directory.force(true); }
        afterMove.run();
    }
    public static void main(String[] args) throws Exception {
        Path state=Path.of(args[0]),input=Path.of(args[1]),report=Path.of(args[2]);
        Input in=JSON.readValue(input.toFile(),Input.class);Facts loaded;
        boolean authorityMode=args.length>3&&args[3].equals("authority");
        boolean split=authorityMode||(args.length>3&&args[3].equals("split"));
        CassandraRootAuthority authority=authorityMode?new CassandraRootAuthority(in.subject()):null;
        CassandraRootAuthority.Version[] rootVersion=new CassandraRootAuthority.Version[1];
        // A missing/corrupt checkpoint never bootstraps an existing subject or opens Cassandra.
        try {
            if(authorityMode) { rootVersion[0]=authority.read();loaded=SplitArchiveCheckpoint.load(state,in.subject(),rootVersion[0].root()); }
            else loaded=split?SplitArchiveCheckpoint.load(state,in.subject()):load(state,in.subject());
        }
        catch(com.datastax.oss.driver.api.core.DriverException e) {
            save(report,Map.of("pid",ProcessHandle.current().pid(),"phase","AUTHORITY_UNKNOWN","error",e.toString()));System.exit(75);return;
        }
        catch(Exception e) { save(report,Map.of("pid",ProcessHandle.current().pid(),"phase","CHECKPOINT_INVALID","error",e.toString()));System.exit(65);return; }
        Storage external=loaded.storage();Map<String,Issued> bindings=new HashMap<>(loaded.bindings());
        var contract=new ArchiveCassandraTest();var h=contract.new Scenario("durable-worker",in.subject(),external,Broken.NONE);
        RetentionClock clock=new RetentionClock();clock.advance(loaded.now()-clock.millis());
        class Runtime {
            Facts facts() { return Facts.capture(in.subject(),external,bindings); }
            void witness(String phase,Reply reply) {
                try { save(report,new Report(ProcessHandle.current().pid(),phase,List.copyOf(h.frames),facts(),reply,h.store.read().hot())); }
                catch(Exception e) { throw new IllegalStateException(e); }
            }
            void cut(String phase) { if(phase.equals(in.cut())) { witness(phase,null);java.lang.Runtime.getRuntime().halt(86); } }
            void checkpoint(String stage) {
                try {
                    if(authorityMode) {
                        var next=SplitArchiveCheckpoint.stage(state,facts(),stage,this::cut);
                        if(!authority.compareAndSet(rootVersion[0],next))throw new IllegalStateException("STALE_AUTHORITY");
                        rootVersion[0]=authority.read();cut(stage+"_AFTER_ROOT_CAS");
                        SplitArchiveCheckpoint.atomic(state,JSON.writeValueAsBytes(next));
                    } else if(split)SplitArchiveCheckpoint.publish(state,facts(),stage,this::cut);
                    else publish(state,facts(),()->cut(stage+"_BEFORE_MOVE"),()->cut(stage+"_AFTER_MOVE"));
                }
                catch(Exception e) { throw new IllegalStateException(e); }
            }
        }
        Runtime runtime=new Runtime();
        h.observer=f->{
            runtime.cut(f.command().kind()+"_AFTER_DB");
            // Reservation and draft binding are one checkpoint; never publish the unbound reservation.
            if(f.command().kind()!=Kind.RESERVE)runtime.checkpoint(f.command().kind().name());
        };
        var service=new SignedArchiveService(h,clock,bindings,()->runtime.checkpoint("BIND"));
        Reply reply;
        try {
            if(in.command()!=null) {
                Frame frame=h.run(in.command());reply=new Reply(frame.outcome().code(),null,null,frame.outcome().receipt());
            } else {
                Message m=in.message();if(!in.subject().equals(m.subject()))throw new Failure(Code.INVALID_TICKET);
                reply=switch(m.action()) {
                    case "prepare" -> new Reply("OK",service.prepare(m.template()),null,null);
                    case "issue" -> new Reply("OK",null,service.issueDraft(m.draft()),null);
                    case "commit" -> new Reply("OK",null,null,service.commit(m.issued().ticket(),m.issued().request()));
                    default -> throw new IllegalArgumentException("unknown action");
                };
            }
        } catch(Failure e) { reply=new Reply(e.code.name(),null,null,null); }
        catch(Transactions.Rejected e) { reply=new Reply(e.error.name(),null,null,null); }
        catch(SignedArchiveService.StateFailure e) { reply=new Reply(e.code,null,null,null); }
        runtime.witness("REPLY",reply);h.store.close();if(authority!=null)authority.close();System.exit(reply.code().equals("OK")?0:2);
    }
}
