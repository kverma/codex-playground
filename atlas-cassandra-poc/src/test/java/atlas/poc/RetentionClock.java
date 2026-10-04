package atlas.poc;
import java.time.*;
import java.util.concurrent.atomic.AtomicLong;
public final class RetentionClock extends Clock {
    private final AtomicLong millis=new AtomicLong(1_800_000_000_000L);
    public ZoneId getZone() { return ZoneOffset.UTC; }
    public Clock withZone(ZoneId zone) { if(!zone.equals(ZoneOffset.UTC)) throw new IllegalArgumentException(); return this; }
    public Instant instant() { return Instant.ofEpochMilli(millis()); }
    public long millis() { return millis.get(); }
    public void advance(long delta) { millis.addAndGet(delta); }
}
