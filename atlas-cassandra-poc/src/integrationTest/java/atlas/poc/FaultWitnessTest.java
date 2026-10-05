package atlas.poc;
import org.junit.jupiter.api.*;
import java.nio.file.*;
import java.util.concurrent.*;
import static org.junit.jupiter.api.Assertions.*;
@Tag("three") @Tag("FaultWitness")
class FaultWitnessTest {
    // BEGIN ATLAS SCENARIO
    /**
     * Goal: Prove that a claimed network fault really isolates a DC
     * Boundary: Run a no-op partition injector and one that blocks only one direction
     * Expected: Reject both invalid fault setups using independent connectivity and packet-counter checks.
     */
    @org.junit.jupiter.api.DisplayName("AT-052 | Prove that a claimed network fault really isolates a DC")
    // END ATLAS SCENARIO
    @Test void rejectsNoOpAndMissingDirectionPartitionFixtures() throws Exception {
        String original=Files.readString(Path.of("scripts/three.sh"));
        String insertion="          sudo nsenter -t \"$pid\" -n iptables -I \"${rule[@]}\"";
        assertTrue(original.contains(insertion));
        String[] broken={original.replace(insertion,"          true # no-op fault"),original.replace(insertion,"          if [ \"$direction\" = OUTPUT ]; then sudo nsenter -t \"$pid\" -n iptables -I \"${rule[@]}\"; fi")};
        String[] failures={"Partition ineffective:","Expected exactly one positive DROP counter: INPUT"};
        for(int i=0;i<broken.length;i++) {
            Path path=Path.of("build/evidence/fault-controls/partition-"+i+".sh"); Files.createDirectories(path.getParent()); Files.writeString(path,broken[i]);
            try {
                var result=run(path.toString(),"partition");
                Files.writeString(path.resolveSibling("partition-"+i+".log"),result.output());
                assertNotEquals(0,result.exit(),"broken injector must fail");
                assertTrue(result.output().contains(failures[i]),result.output());
            } finally {
                var healed=run("scripts/three.sh","heal"); assertEquals(0,healed.exit(),healed.output());
            }
        }
    }
    private record Result(int exit,String output) {}
    private Result run(String path,String action) throws Exception {
        Process process=new ProcessBuilder("bash",path,action).redirectErrorStream(true).start();
        try(var pool=Executors.newVirtualThreadPerTaskExecutor()) {
            var output=pool.submit(()->new String(process.getInputStream().readAllBytes(),java.nio.charset.StandardCharsets.UTF_8));
            if(!process.waitFor(45,TimeUnit.SECONDS)) { process.destroyForcibly(); fail("fault control timed out"); }
            return new Result(process.exitValue(),output.get(5,TimeUnit.SECONDS));
        }
    }
}
