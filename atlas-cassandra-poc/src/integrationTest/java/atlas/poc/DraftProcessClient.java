package atlas.poc;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.*;
import java.net.*;
import java.nio.*;
import java.nio.channels.FileChannel;
import java.nio.file.*;
import java.util.UUID;
import static atlas.poc.Retention.*;
import static atlas.poc.Transactions.*;

/** Separate JVM fixture client. One socket request, no automatic transport retry. */
public final class DraftProcessClient {
    record Journal(int version,UUID subject,Draft draft,Issued issued) {}
    record Message(String action,UUID subject,Request template,Draft draft,Issued issued) {}
    record Reply(String code,Draft draft,Issued issued,Receipt receipt) {}
    record Result(long pid,Reply reply,String error) {}
    static final ObjectMapper JSON=new ObjectMapper();
    static void save(Path path,Object value) throws Exception {
        Files.createDirectories(path.toAbsolutePath().getParent());
        Path temp=Files.createTempFile(path.toAbsolutePath().getParent(),"atlas-journal-",".tmp");
        try {
            byte[] bytes=JSON.writeValueAsBytes(value);
            try(var channel=FileChannel.open(temp,StandardOpenOption.WRITE)) {
                ByteBuffer buffer=ByteBuffer.wrap(bytes);while(buffer.hasRemaining())channel.write(buffer);channel.force(true);
            }
            Files.move(temp,path,StandardCopyOption.ATOMIC_MOVE,StandardCopyOption.REPLACE_EXISTING);
        } finally { Files.deleteIfExists(temp); }
    }
    static byte[] frame(DataInputStream input) throws IOException {
        int n=input.readInt();if(n<1||n>1_048_576)throw new IOException("invalid frame size");
        byte[] bytes=input.readNBytes(n);if(bytes.length!=n)throw new EOFException("incomplete response");return bytes;
    }
    static void frame(DataOutputStream output,Object value) throws IOException {
        byte[] bytes=JSON.writeValueAsBytes(value);output.writeInt(bytes.length);output.write(bytes);output.flush();
    }
    public static void main(String[] args) throws Exception {
        String action=args[0];int port=Integer.parseInt(args[1]);Path journal=Path.of(args[2]),result=Path.of(args[3]);
        Message message;Journal saved=null;
        try {
            if(action.equals("prepare"))message=new Message(action,UUID.fromString(args[4]),JSON.readValue(Path.of(args[5]).toFile(),Request.class),null,null);
            else {
                saved=JSON.readValue(journal.toFile(),Journal.class);
                if(saved.version()!=1||saved.subject()==null||saved.draft()==null)throw new IllegalArgumentException("unsupported or incomplete journal");
                if(action.equals("commit")&&saved.issued()==null)throw new IllegalArgumentException("missing issued identity");
                message=new Message(action,saved.subject(),null,saved.draft(),saved.issued());
            }
        } catch(Exception e) { save(result,new Result(ProcessHandle.current().pid(),null,"LOCAL_INVALID: "+e));System.exit(65);return; }
        Reply reply;
        try(var socket=new Socket()) {
            socket.connect(new InetSocketAddress("127.0.0.1",port),5000);socket.setSoTimeout(20000);
            frame(new DataOutputStream(socket.getOutputStream()),message);
            reply=JSON.readValue(frame(new DataInputStream(socket.getInputStream())),Reply.class);
        } catch(IOException e) { save(result,new Result(ProcessHandle.current().pid(),null,"TRANSPORT_UNKNOWN: "+e));System.exit(75);return; }
        if(reply.code().equals("OK")) {
            if(action.equals("prepare"))save(journal,new Journal(1,message.subject(),reply.draft(),null));
            if(action.equals("issue"))save(journal,new Journal(1,saved.subject(),saved.draft(),reply.issued()));
        }
        save(result,new Result(ProcessHandle.current().pid(),reply,null));System.exit(reply.code().equals("OK")?0:2);
    }
}
