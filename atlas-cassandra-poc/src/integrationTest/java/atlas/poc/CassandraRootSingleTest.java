package atlas.poc;
import java.util.UUID;
class CassandraRootSingleTest extends CassandraRootContract {
    CassandraRootAuthority open(UUID subject,boolean peer) { return new CassandraRootAuthority(subject); }
    String folder() { return "root-cas-single"; }
}
