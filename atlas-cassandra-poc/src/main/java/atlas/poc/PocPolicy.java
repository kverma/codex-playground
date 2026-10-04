package atlas.poc;

import com.datastax.oss.driver.api.core.*;
import com.datastax.oss.driver.api.core.cql.*;
import java.nio.file.*;
import java.time.Instant;

/** Fail-closed test instrumentation; records observed coordinator, not merely the contact point. */
public final class PocPolicy {
    private PocPolicy() {}
    public static void validate(SimpleStatement statement,boolean mutation) {
        boolean valid=mutation ? statement.getConsistencyLevel()==DefaultConsistencyLevel.QUORUM &&
            statement.getSerialConsistencyLevel()==DefaultConsistencyLevel.SERIAL && Boolean.FALSE.equals(statement.isIdempotent()) :
            statement.getConsistencyLevel()==DefaultConsistencyLevel.SERIAL;
        if(!valid) throw new IllegalStateException("POC consistency policy violated");
    }
    public static ResultSet execute(CqlSession session,SimpleStatement statement,boolean mutation,String expectedDc) {
        validate(statement,mutation);
        ResultSet result=session.execute(statement);
        var coordinator=result.getExecutionInfo().getCoordinator();
        String actual=coordinator==null?null:coordinator.getDatacenter();
        if(!expectedDc.equals(actual)) throw new IllegalStateException("Wrong coordinator DC: expected "+expectedDc+", actual "+actual);
        record(mutation,actual,statement);
        return result;
    }
    private static synchronized void record(boolean mutation,String dc,SimpleStatement statement) {
        try {
            Path path=Path.of("build/evidence/policy.jsonl"); Files.createDirectories(path.getParent());
            Files.writeString(path,"{\"time\":\""+Instant.now()+"\",\"mutation\":"+mutation+",\"dc\":\""+dc+"\",\"regular\":\""+statement.getConsistencyLevel()+"\",\"serial\":\""+statement.getSerialConsistencyLevel()+"\"}\n",StandardOpenOption.CREATE,StandardOpenOption.APPEND);
        } catch(java.io.IOException e) { throw new IllegalStateException("Cannot save policy evidence",e); }
    }
}
