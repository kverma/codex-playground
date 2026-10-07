package atlas.poc;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;

/** Deliberately bounded fixture, not the full Atlas commercial model. */
public record Intent(int cents, boolean churnedEligible) {
    public Intent { if (cents < 0 || cents > 1_000_000) throw new IllegalArgumentException("price budget"); }
    public String canonical() { return "atlas-intent-v1|USD|" + cents + "|new=true|churned=" + churnedEligible; }
    public String revision() { return hash(canonical()); }
    public static String hash(String text) {
        try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(text.getBytes(StandardCharsets.UTF_8))); }
        catch (NoSuchAlgorithmException e) { throw new AssertionError(e); }
    }
}
