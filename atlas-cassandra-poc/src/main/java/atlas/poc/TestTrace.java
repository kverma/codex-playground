package atlas.poc;

import java.io.*;
import java.lang.reflect.Array;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import java.util.zip.GZIPOutputStream;

/** Diagnostic evidence only. Never interprets a timeout as a rejected mutation. */
public final class TestTrace {
    private TestTrace() {}
    private static final InheritableThreadLocal<String> CASE = new InheritableThreadLocal<>();
    private static final Map<String,Writer> OUTPUTS = new HashMap<>();
    public static void begin(String test) { CASE.set(test); }
    public static synchronized void end() {
        String key=CASE.get(); CASE.remove();
        try { Writer w=OUTPUTS.remove(key); if(w!=null) w.close(); }
        catch(IOException e) { throw new UncheckedIOException(e); }
    }
    public static String value(Object v) {
        if(v==null) return "null";
        if(v.getClass().isArray()) {
            List<String> values=new ArrayList<>();
            for(int i=0;i<Array.getLength(v);i++) values.add(value(Array.get(v,i)));
            return values.toString();
        }
        return String.valueOf(v);
    }
    private static String quote(Object v) {
        String s=value(v); StringBuilder b=new StringBuilder("\"");
        for(char c:s.toCharArray()) switch(c) {
            case '"' -> b.append("\\\""); case '\\' -> b.append("\\\\");
            case '\n' -> b.append("\\n"); case '\r' -> b.append("\\r"); case '\t' -> b.append("\\t");
            default -> { if(c<32) b.append(String.format("\\u%04x",(int)c)); else b.append(c); }
        }
        return b.append('"').toString();
    }
    public static synchronized void event(String kind,Object... fields) {
        String key=CASE.get();
        // Worker processes retain their existing durable protocol histories. Do not add
        // buffered diagnostics to their crash/publication boundaries.
        if(key==null) return;
        try {
            Writer w=OUTPUTS.get(key);
            if(w==null) {
                Path p=Path.of("build/evidence/test-traces",key+".jsonl.gz");
                Files.createDirectories(p.getParent());
                w=new BufferedWriter(new OutputStreamWriter(new GZIPOutputStream(Files.newOutputStream(p)),StandardCharsets.UTF_8));
                OUTPUTS.put(key,w);
            }
            StringBuilder b=new StringBuilder("{\"kind\":").append(quote(kind));
            for(int i=0;i<fields.length;i+=2) b.append(',').append(quote(fields[i])).append(':').append(quote(fields[i+1]));
            b.append(",\"thread\":").append(quote(Thread.currentThread().getName())).append("}\n");
            w.write(b.toString());
        } catch(IOException e) { throw new UncheckedIOException(e); }
    }
    public static void assertion(String operation,Object expected,Object actual,Runnable check) {
        String site=StackWalker.getInstance().walk(s->s.filter(f->f.getClassName().startsWith("atlas.poc.") &&
            !f.getClassName().equals(TestTrace.class.getName()) && !f.getClassName().endsWith("TraceAssertions")).findFirst().map(Object::toString).orElse("unknown"));
        try { check.run(); event("assertion","operation",operation,"expected",expected,"actual",actual,"comparison","PASS","site",site); }
        catch(AssertionError e) { event("assertion","operation",operation,"expected",expected,"actual",actual,"comparison","FAIL","site",site); throw e; }
    }
}
