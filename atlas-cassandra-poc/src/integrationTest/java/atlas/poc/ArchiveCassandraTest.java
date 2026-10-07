package atlas.poc;
import java.util.UUID;
import static atlas.poc.ArchiveRecovery.*;
class ArchiveCassandraTest extends ArchiveCassandraContract {
    ArchiveCassandraFixture open(UUID subject,Storage external,Broken broken,boolean peer) {
        return new ArchiveCassandraFixture(subject,external,"127.0.0.1",9042,"dc1","single",broken);
    }
    String evidenceFolder() { return "archive-cassandra-single"; }
}
