package atlas.poc;
import java.util.UUID;
@org.junit.jupiter.api.Tag("three")
@org.junit.jupiter.api.Tag("Archive")
class CassandraRootThreeTest extends CassandraRootContract {
    CassandraRootAuthority open(UUID subject,boolean peer) { return new CassandraRootAuthority(subject,peer?9142:9042,peer?"dc2":"dc1","three"); }
    String folder() { return "root-cas-three"; }
}
