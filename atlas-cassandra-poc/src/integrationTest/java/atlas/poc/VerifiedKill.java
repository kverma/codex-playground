package atlas.poc;
import java.util.*;
import java.nio.file.*;
import java.util.concurrent.TimeUnit;
import com.fasterxml.jackson.databind.ObjectMapper;
import static org.junit.jupiter.api.Assertions.*;
final class VerifiedKill {
    private VerifiedKill() {}
    static void kill(List<String> compose,String service) throws Exception {
        String container=command(concat(compose,List.of("ps","-q",service))).strip(); assertFalse(container.isBlank());
        String before=command(List.of("docker","inspect","-f","{{.State.Running}}|{{.State.Pid}}|{{.State.StartedAt}}",container)).strip();
        assertTrue(before.startsWith("true|"),"target must be running before fault");
        command(concat(compose,List.of("kill","-s","SIGKILL",service)));
        String after=command(List.of("docker","inspect","-f","{{.State.Running}}|{{.State.ExitCode}}|{{.State.OOMKilled}}|{{.State.Pid}}",container)).strip();
        assertEquals("false|137|false|0",after,"verify SIGKILL stopped the targeted process, without OOM");
        var event=Map.of("time",java.time.Instant.now().toString(),"service",service,"container",container,"before",before,"after",after);
        Path path=Path.of("build/evidence/verified-kills.jsonl"); Files.createDirectories(path.getParent());
        Files.writeString(path,new ObjectMapper().writeValueAsString(event)+"\n",StandardOpenOption.CREATE,StandardOpenOption.APPEND);
    }
    private static List<String> concat(List<String> first,List<String> second) { var result=new ArrayList<>(first); result.addAll(second); return result; }
    private static String command(List<String> args) throws Exception {
        Process process=new ProcessBuilder(args).redirectErrorStream(true).start();
        try(var pool=java.util.concurrent.Executors.newVirtualThreadPerTaskExecutor()) {
            var output=pool.submit(()->new String(process.getInputStream().readAllBytes(),java.nio.charset.StandardCharsets.UTF_8));
            if(!process.waitFor(30,TimeUnit.SECONDS)) { process.destroyForcibly(); fail("kill witness command timeout"); }
            String result=output.get(5,TimeUnit.SECONDS); assertEquals(0,process.exitValue(),result); return result;
        }
    }
}
