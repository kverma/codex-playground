package atlas.poc;
import java.util.UUID;
import java.time.Duration;
class RecoveryJournalSingleTest extends RecoveryJournalContract {
    CassandraRecoveryJournal open(UUID subject,String store,boolean peer) { return new CassandraRecoveryJournal(subject,store,9042,"dc1","single",Duration.ofSeconds(20)); }
    String folder() { return "recovery-journal-single"; }
}
