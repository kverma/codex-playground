package atlas.poc;
import java.util.UUID;
import java.time.Duration;
@org.junit.jupiter.api.Tag("three") @org.junit.jupiter.api.Tag("Archive")
class RecoveryJournalThreeTest extends RecoveryJournalContract {
    CassandraRecoveryJournal open(UUID subject,String store,boolean peer) { return new CassandraRecoveryJournal(subject,store,peer?9142:9042,peer?"dc2":"dc1","three",Duration.ofSeconds(20)); }
    String folder() { return "recovery-journal-three"; }
}
