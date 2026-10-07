package atlas.poc;

import java.io.*;
import java.net.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicReference;
import static atlas.poc.DraftProcessClient.*;
import static atlas.poc.Retention.*;

/** Loopback transport fixture, not the Atlas API. Records actual commands before dropping replies. */
final class DraftProcessServer implements AutoCloseable {
    final List<Map<String,Object>> events=Collections.synchronizedList(new ArrayList<>());
    final List<RetentionChecker.Call> calls=Collections.synchronizedList(new ArrayList<>());
    final View initial;
    final SignedArchiveService service;
    private final UUID subject;
    private final RetentionClock clock;
    private final ServerSocket listener;
    private final ExecutorService executor=Executors.newVirtualThreadPerTaskExecutor();
    private final Future<?> loop;
    private final AtomicReference<String> drop=new AtomicReference<>();
    volatile boolean audit=true,closed;
    private volatile long sequence;
    DraftProcessServer(ArchiveCassandraContract.Scenario h,RetentionClock clock) throws IOException {
        subject=h.subject;this.clock=clock;service=new SignedArchiveService(h,clock);initial=service.view();
        listener=new ServerSocket(0,10,InetAddress.getLoopbackAddress());
        loop=executor.submit(()->{
            while(!closed)try(var socket=listener.accept()) {
                socket.setSoTimeout(20000);
                Message message=JSON.readValue(frame(new DataInputStream(socket.getInputStream())),Message.class);
                Reply reply=execute(message);
                String armed=drop.get();
                boolean dropped=reply.code().equals("OK") && message.action().equals(armed) && drop.compareAndSet(armed,null);
                events.add(Map.of("message",message,"reply",reply,"dropped",dropped));
                if(!dropped)frame(new DataOutputStream(socket.getOutputStream()),reply);
            } catch(IOException e) { if(!closed)throw new UncheckedIOException(e); }
        });
    }
    int port() { return listener.getLocalPort(); }
    void loseNextReply(String action) { if(!drop.compareAndSet(null,action))throw new IllegalStateException("already armed"); }
    private Reply execute(Message m) {
        Issued issued=null;Transactions.Receipt receipt=null;String error=null;Draft draft=null;
        long start=++sequence;
        try {
            if(!subject.equals(m.subject()))throw new Failure(Code.INVALID_TICKET);
            switch(m.action()) {
                case "prepare" -> draft=service.prepare(m.template());
                case "issue" -> issued=service.issueDraft(m.draft());
                case "commit" -> receipt=service.commit(m.issued().ticket(),m.issued().request());
                default -> throw new IllegalArgumentException("unknown fixture action");
            }
        } catch(Failure e) { error=e.code.name(); }
        catch(Transactions.Rejected e) { error=e.error.name(); }
        catch(SignedArchiveService.StateFailure e) { error=e.code; }
        long end=++sequence;
        if(audit && m.action().equals("issue"))calls.add(new RetentionChecker.Call(start,end,clock.millis(),"ISSUE",m.draft(),issued,null,null,error));
        if(audit && m.action().equals("commit"))calls.add(new RetentionChecker.Call(start,end,clock.millis(),"COMMIT",null,m.issued(),receipt,null,error));
        return new Reply(error==null?"OK":error,draft,issued,receipt);
    }
    void observe() { calls.add(new RetentionChecker.Call(++sequence,++sequence,clock.millis(),"VIEW",null,null,null,service.view(),null)); }
    public void close() throws Exception {
        closed=true;listener.close();try { loop.get(10,TimeUnit.SECONDS); } finally { executor.shutdownNow(); }
    }
}
