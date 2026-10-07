package atlas.poc;
import org.junit.jupiter.api.Tag;
import java.util.UUID;
import static atlas.poc.ArchiveRecovery.*;
@Tag("three") @Tag("Archive")
class ArchiveThreeNodeTest extends ArchiveCassandraContract {
    ArchiveCassandraFixture open(UUID subject,Storage external,Broken broken,boolean peer) {
        return new ArchiveCassandraFixture(subject,external,"127.0.0.1",peer?9142:9042,peer?"dc2":"dc1","three",broken);
    }
    String evidenceFolder() { return "archive-cassandra-three"; }
}
