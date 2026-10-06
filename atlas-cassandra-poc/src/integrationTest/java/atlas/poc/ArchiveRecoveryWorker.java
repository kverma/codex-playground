package atlas.poc;

import java.nio.file.*;
import java.time.Duration;
import java.util.*;
import static atlas.poc.DraftProcessClient.save;

/** One persisted coordinator phase per fresh JVM. */
public final class ArchiveRecoveryWorker {
    public static void main(String[] args) throws Exception {
        UUID subject=UUID.fromString(args[0]);Path actor=Path.of(args[1]),archive=Path.of(args[2]),output=Path.of(args[3]);
        int port=Integer.parseInt(args[4]);String boundary=args[5];
        try(var root=new CassandraRecoveryJournal(subject,"phase-authority",port,"dc1","single",Duration.ofSeconds(port==9042?20:3));
            var hot=new CassandraRecoveryJournal(subject,"phase-hot",port,"dc1","single",Duration.ofSeconds(port==9042?20:3))) {
            var driver=new PersistentArchiveRecovery(subject,actor,archive,root,hot);
            var result=driver.advance(point->{if(point.equals(boundary)) {
                try {save(output.resolveSibling(output.getFileName()+".halt"),Map.of("pid",ProcessHandle.current().pid(),"cut",point));}
                catch(Exception e) {throw new RuntimeException(e);}
                Runtime.getRuntime().halt(86);
            }});
            save(output,Map.of("pid",ProcessHandle.current().pid(),"actor",result));
        } catch(Exception e) {save(output,Map.of("pid",ProcessHandle.current().pid(),"error",e.toString()));System.exit(75);}
    }
}
