package atlas.poc;

import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.file.*;
import java.util.*;
import java.util.function.Consumer;
import static atlas.poc.DraftProcessClient.JSON;
import static atlas.poc.DurableArchiveServer.*;

/** Separate file read/publication boundaries. Root freshness is an explicit external assumption. */
final class SplitArchiveCheckpoint {
    record Root(int version,UUID subject,String authority,String archive) {}
    record ObjectsFile(int version,UUID subject,Map<Long,ArchiveRecovery.Blob> objects) {}
    static String hash(byte[] bytes) throws Exception {
        return HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256").digest(bytes));
    }
    static Path object(Path root,String domain,String hash) {
        if(hash==null||!hash.matches("[0-9a-f]{64}"))throw new IllegalArgumentException("invalid object identity");
        return root.resolveSibling(domain).resolve(hash+".json");
    }
    static Root root(Path path,UUID subject) throws Exception {
        Root r=JSON.readValue(Files.readAllBytes(path),Root.class);
        if(r.version()!=1||!subject.equals(r.subject()))throw new IllegalArgumentException("invalid root");return r;
    }
    static byte[] read(Path root,String domain,String digest) throws Exception {
        byte[] bytes=Files.readAllBytes(object(root,domain,digest));
        if(!hash(bytes).equals(digest))throw new IllegalArgumentException(domain+" content differs from trusted root");return bytes;
    }
    static Facts load(Path path,UUID subject) throws Exception {
        return load(path,subject,root(path,subject));
    }
    static Facts load(Path path,UUID subject,Root r) throws Exception {
        if(r.version()!=1||!subject.equals(r.subject()))throw new IllegalArgumentException("invalid root");
        // Read exact root-referenced bytes: no scanning directories or falling back to older objects.
        Facts authority=DurableArchiveServer.decode(read(path,"authority",r.authority()),subject);
        if(!authority.archive().isEmpty())throw new IllegalArgumentException("archive must not be hidden in authority");
        ObjectsFile archive=JSON.readValue(read(path,"archive",r.archive()),ObjectsFile.class);
        if(archive.version()!=1||!subject.equals(archive.subject())||archive.objects()==null)throw new IllegalArgumentException("invalid archive object");
        return new Facts(1,subject,authority.genesis(),authority.now(),authority.highWater(),authority.certifiedFloor(),
            authority.grants(),authority.manifests(),archive.objects(),authority.backups(),authority.bindings());
    }
    static void atomic(Path path,byte[] bytes) throws Exception {
        Files.createDirectories(path.toAbsolutePath().getParent());Path temp=path.resolveSibling(path.getFileName()+".pending");
        try(var ch=FileChannel.open(temp,StandardOpenOption.CREATE,StandardOpenOption.TRUNCATE_EXISTING,StandardOpenOption.WRITE)) {
            ByteBuffer b=ByteBuffer.wrap(bytes);while(b.hasRemaining())ch.write(b);ch.force(true);
        }
        Files.move(temp,path,StandardCopyOption.ATOMIC_MOVE,StandardCopyOption.REPLACE_EXISTING);
        try(var d=FileChannel.open(path.toAbsolutePath().getParent(),StandardOpenOption.READ)) { d.force(true); }
    }
    static String put(Path root,String domain,byte[] bytes) throws Exception {
        String digest=hash(bytes);Path target=object(root,domain,digest);
        if(Files.exists(target)) {
            if(!Arrays.equals(bytes,Files.readAllBytes(target)))throw new IllegalArgumentException("immutable object damaged");
        } else atomic(target,bytes);
        return digest;
    }
    static void publish(Path path,Facts f,String stage,Consumer<String> cut) throws Exception {
        Root next=stage(path,f,stage,cut);
        atomic(path,JSON.writeValueAsBytes(next));cut.accept(stage+"_AFTER_ROOT");
    }
    static Root stage(Path path,Facts f,String stage,Consumer<String> cut) throws Exception {
        String archive=put(path,"archive",JSON.writeValueAsBytes(new ObjectsFile(1,f.subject(),f.archive())));
        cut.accept(stage+"_AFTER_ARCHIVE");
        Facts authority=new Facts(1,f.subject(),f.genesis(),f.now(),f.highWater(),f.certifiedFloor(),f.grants(),f.manifests(),Map.of(),f.backups(),f.bindings());
        String auth=put(path,"authority",envelope(authority));cut.accept(stage+"_AFTER_AUTHORITY");
        return new Root(1,f.subject(),auth,archive);
    }
}
