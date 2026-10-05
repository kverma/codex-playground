package atlas.poc;

import java.util.function.*;
import static atlas.poc.Retention.*;

/** Test-harness recovery policy shared by shallow controls and real Cassandra histories. */
final class HealedReads {
    record Attempt(int dc,int attempt,View view,String error,String cause) {}
    @FunctionalInterface interface Readiness { void check() throws Exception; }
    static View read(Supplier<View> read,int dc,Readiness readiness,Consumer<Attempt> evidence) throws Exception {
        Failure last=null;
        for(int attempt=1;attempt<=3;attempt++) {
            try {
                View view=read.get();evidence.accept(new Attempt(dc,attempt,view,null,null));return view;
            } catch(Failure e) {
                last=e;
                String cause=e.getCause()==null?e.toString():e.getCause().toString();
                evidence.accept(new Attempt(dc,attempt,null,e.code.name(),cause));
                if(e.code!=Code.INDETERMINATE)throw e;
                // A previous membership snapshot is not a lease on future availability.
                if(attempt<3)readiness.check();
            }
        }
        throw new AssertionError("dc"+dc+" must return an authoritative healed view within three attempts",last);
    }
}
