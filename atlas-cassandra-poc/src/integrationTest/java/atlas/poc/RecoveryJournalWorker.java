package atlas.poc;

import java.nio.file.*;
import java.time.Duration;
import java.util.UUID;
import static atlas.poc.RecoveryJournal.*;
import static atlas.poc.DraftProcessClient.*;

/** New JVM per attempt, reading the exact persisted proposal. Not an authenticated production service. */
public final class RecoveryJournalWorker {
    record Input(UUID subject,String store,Request request) {}
    record Output(long pid,Result result,String error) {}
    public static void main(String[] args) throws Exception {
        Input input=JSON.readValue(Path.of(args[0]).toFile(),Input.class);Path output=Path.of(args[1]);int port=Integer.parseInt(args[2]);
        try(var journal=new CassandraRecoveryJournal(input.subject(),input.store(),port,"dc1","single",Duration.ofSeconds(port==9042?20:3))) {
            Result result=journal.apply(input.request());
            if(args.length>3&&args[3].equals("halt")) {
                save(output.resolveSibling(output.getFileName()+".halt"),new Output(ProcessHandle.current().pid(),result,"AFTER_EFFECT_BEFORE_REPLY"));
                Runtime.getRuntime().halt(86);
            }
            save(output,new Output(ProcessHandle.current().pid(),result,null));
        } catch(com.datastax.oss.driver.api.core.DriverException e) {
            save(output,new Output(ProcessHandle.current().pid(),null,e.toString()));System.exit(75);
        }
    }
}
