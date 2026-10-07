package atlas.poc;

import java.util.*;

public final class Protocol {
    private Protocol() {}
    public record Head(UUID token, Intent intent) {}
    public record Request(UUID operation, UUID expected, Intent intent) {
        public Request { Objects.requireNonNull(operation); Objects.requireNonNull(expected); Objects.requireNonNull(intent); }
        public String hash() { return Intent.hash("request-v1|" + expected + "|" + intent.canonical()); }
    }
    public record Receipt(UUID operation, String requestHash, UUID token, Intent intent) {}
    public static final class Conflict extends RuntimeException {}
    public static final class KeyReuse extends RuntimeException {}
    /** Transport ambiguity: caller must retry the exact request, never infer rollback. */
    public static final class Indeterminate extends RuntimeException {
        public Indeterminate(Throwable cause) { super(cause); }
    }
    public interface Store extends AutoCloseable {
        Head read();
        Receipt commit(Request request);
        default void close() {}
    }
    public static Receipt exact(Receipt receipt, Request request) {
        if (!receipt.requestHash().equals(request.hash())) throw new KeyReuse();
        return receipt;
    }
    /** Serial reference specification; this does not simulate Paxos or replica failures. */
    public static final class Model implements Store {
        private Head head = new Head(UUID.randomUUID(), new Intent(500, false));
        private final Map<UUID, Receipt> receipts = new HashMap<>();
        public synchronized Head read() { return head; }
        public synchronized Receipt commit(Request r) {
            if (receipts.containsKey(r.operation())) return exact(receipts.get(r.operation()), r);
            if (!head.token().equals(r.expected())) throw new Conflict();
            Receipt receipt = new Receipt(r.operation(), r.hash(), UUID.randomUUID(), r.intent());
            receipts.put(r.operation(), receipt);
            head = new Head(receipt.token(), receipt.intent());
            return receipt;
        }
    }
}
