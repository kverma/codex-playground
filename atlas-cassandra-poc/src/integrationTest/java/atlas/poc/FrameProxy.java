package atlas.poc;

import java.io.*;
import java.net.*;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.Set;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicReference;

/** Test-only native protocol v4 proxy. Drops exactly one selected QUERY frame/response. */
final class FrameProxy implements AutoCloseable {
    enum Fault { BEFORE_SEND, AFTER_RESPONSE }
    private final ServerSocket listener;
    private final ExecutorService threads = Executors.newVirtualThreadPerTaskExecutor();
    private final Set<Socket> sockets = ConcurrentHashMap.newKeySet();
    private final AtomicReference<Fault> armed = new AtomicReference<>();
    final CountDownLatch injected = new CountDownLatch(1);
    private volatile boolean closed;
    FrameProxy() throws IOException {
        listener = new ServerSocket(0, 10, InetAddress.getLoopbackAddress());
        threads.submit(() -> {
            while (!closed) {
                try {
                    Socket client = listener.accept();
                    Socket server = new Socket("127.0.0.1", 9042);
                    sockets.add(client); sockets.add(server);
                    Set<Integer> dropStreams = ConcurrentHashMap.newKeySet();
                    threads.submit(() -> pipe(client,server,true,dropStreams));
                    threads.submit(() -> pipe(server,client,false,dropStreams));
                } catch (IOException e) { if (!closed) throw new UncheckedIOException(e); }
            }
        });
    }
    int port() { return listener.getLocalPort(); }
    void arm(Fault fault) { armed.set(fault); }
    private void pipe(Socket source, Socket target, boolean request, Set<Integer> drops) {
        try {
            DataInputStream input = new DataInputStream(source.getInputStream());
            OutputStream output = target.getOutputStream();
            while (!closed) {
                byte[] header = input.readNBytes(9);
                if (header.length == 0) return;
                if (header.length != 9) throw new EOFException("partial frame header");
                int length = ByteBuffer.wrap(header,5,4).getInt();
                if (length < 0 || length > 16*1024*1024) throw new IOException("invalid frame size");
                byte[] body = input.readNBytes(length);
                if (body.length != length) throw new EOFException("partial frame body");
                int stream = ByteBuffer.wrap(header,2,2).getShort();
                boolean batch = request && header[4] == 7 && length > 4 &&
                    new String(body,4,length-4,StandardCharsets.UTF_8).stripLeading().startsWith("BEGIN BATCH");
                if (batch) {
                    Fault fault = armed.getAndSet(null);
                    if (fault == Fault.BEFORE_SEND) { injected.countDown(); continue; }
                    if (fault == Fault.AFTER_RESPONSE) drops.add(stream);
                }
                if (!request && drops.remove(stream)) { injected.countDown(); continue; }
                output.write(header); output.write(body); output.flush();
            }
        } catch (IOException e) {
            // Closing either end propagates coordinator death to the client.
        } finally {
            try { source.close(); } catch (IOException ignored) {}
            try { target.close(); } catch (IOException ignored) {}
        }
    }
    public void close() throws IOException {
        closed = true; listener.close();
        for (Socket socket : sockets) socket.close();
        threads.shutdownNow();
    }
}
