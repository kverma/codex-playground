package atlas.poc;

import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.function.Executable;
import java.util.function.Supplier;

/** Delegates comparisons to JUnit unchanged and saves evaluated operands. */
public final class TraceAssertions extends Assertions {
    private TraceAssertions() {}
    public static void assertEquals(Object expected, Object actual) { TestTrace.assertion("assertEquals",expected,actual,()->Assertions.assertEquals(expected,actual)); }
    public static void assertEquals(Object expected, Object actual, String message) { TestTrace.assertion("assertEquals",expected,actual,()->Assertions.assertEquals(expected,actual, message)); }
    public static void assertEquals(Object expected, Object actual, Supplier<String> messageSupplier) { TestTrace.assertion("assertEquals",expected,actual,()->Assertions.assertEquals(expected,actual, messageSupplier)); }
    public static void assertEquals(byte expected, byte actual) { TestTrace.assertion("assertEquals",expected,actual,()->Assertions.assertEquals(expected,actual)); }
    public static void assertEquals(byte expected, byte actual, String message) { TestTrace.assertion("assertEquals",expected,actual,()->Assertions.assertEquals(expected,actual, message)); }
    public static void assertEquals(byte expected, byte actual, Supplier<String> messageSupplier) { TestTrace.assertion("assertEquals",expected,actual,()->Assertions.assertEquals(expected,actual, messageSupplier)); }
    public static void assertEquals(byte expected, Byte actual) { TestTrace.assertion("assertEquals",expected,actual,()->Assertions.assertEquals(expected,actual)); }
    public static void assertEquals(byte expected, Byte actual, String message) { TestTrace.assertion("assertEquals",expected,actual,()->Assertions.assertEquals(expected,actual, message)); }
    public static void assertEquals(byte expected, Byte actual, Supplier<String> messageSupplier) { TestTrace.assertion("assertEquals",expected,actual,()->Assertions.assertEquals(expected,actual, messageSupplier)); }
    public static void assertEquals(Byte expected, byte actual) { TestTrace.assertion("assertEquals",expected,actual,()->Assertions.assertEquals(expected,actual)); }
    public static void assertEquals(Byte expected, byte actual, String message) { TestTrace.assertion("assertEquals",expected,actual,()->Assertions.assertEquals(expected,actual, message)); }
    public static void assertEquals(Byte expected, byte actual, Supplier<String> messageSupplier) { TestTrace.assertion("assertEquals",expected,actual,()->Assertions.assertEquals(expected,actual, messageSupplier)); }
    public static void assertEquals(Byte expected, Byte actual) { TestTrace.assertion("assertEquals",expected,actual,()->Assertions.assertEquals(expected,actual)); }
    public static void assertEquals(Byte expected, Byte actual, String message) { TestTrace.assertion("assertEquals",expected,actual,()->Assertions.assertEquals(expected,actual, message)); }
    public static void assertEquals(Byte expected, Byte actual, Supplier<String> messageSupplier) { TestTrace.assertion("assertEquals",expected,actual,()->Assertions.assertEquals(expected,actual, messageSupplier)); }
    public static void assertEquals(short expected, short actual) { TestTrace.assertion("assertEquals",expected,actual,()->Assertions.assertEquals(expected,actual)); }
    public static void assertEquals(short expected, short actual, String message) { TestTrace.assertion("assertEquals",expected,actual,()->Assertions.assertEquals(expected,actual, message)); }
    public static void assertEquals(short expected, short actual, Supplier<String> messageSupplier) { TestTrace.assertion("assertEquals",expected,actual,()->Assertions.assertEquals(expected,actual, messageSupplier)); }
    public static void assertEquals(short expected, Short actual) { TestTrace.assertion("assertEquals",expected,actual,()->Assertions.assertEquals(expected,actual)); }
    public static void assertEquals(short expected, Short actual, String message) { TestTrace.assertion("assertEquals",expected,actual,()->Assertions.assertEquals(expected,actual, message)); }
    public static void assertEquals(short expected, Short actual, Supplier<String> messageSupplier) { TestTrace.assertion("assertEquals",expected,actual,()->Assertions.assertEquals(expected,actual, messageSupplier)); }
    public static void assertEquals(Short expected, short actual) { TestTrace.assertion("assertEquals",expected,actual,()->Assertions.assertEquals(expected,actual)); }
    public static void assertEquals(Short expected, short actual, String message) { TestTrace.assertion("assertEquals",expected,actual,()->Assertions.assertEquals(expected,actual, message)); }
    public static void assertEquals(Short expected, short actual, Supplier<String> messageSupplier) { TestTrace.assertion("assertEquals",expected,actual,()->Assertions.assertEquals(expected,actual, messageSupplier)); }
    public static void assertEquals(Short expected, Short actual) { TestTrace.assertion("assertEquals",expected,actual,()->Assertions.assertEquals(expected,actual)); }
    public static void assertEquals(Short expected, Short actual, String message) { TestTrace.assertion("assertEquals",expected,actual,()->Assertions.assertEquals(expected,actual, message)); }
    public static void assertEquals(Short expected, Short actual, Supplier<String> messageSupplier) { TestTrace.assertion("assertEquals",expected,actual,()->Assertions.assertEquals(expected,actual, messageSupplier)); }
    public static void assertEquals(char expected, char actual) { TestTrace.assertion("assertEquals",expected,actual,()->Assertions.assertEquals(expected,actual)); }
    public static void assertEquals(char expected, char actual, String message) { TestTrace.assertion("assertEquals",expected,actual,()->Assertions.assertEquals(expected,actual, message)); }
    public static void assertEquals(char expected, char actual, Supplier<String> messageSupplier) { TestTrace.assertion("assertEquals",expected,actual,()->Assertions.assertEquals(expected,actual, messageSupplier)); }
    public static void assertEquals(char expected, Character actual) { TestTrace.assertion("assertEquals",expected,actual,()->Assertions.assertEquals(expected,actual)); }
    public static void assertEquals(char expected, Character actual, String message) { TestTrace.assertion("assertEquals",expected,actual,()->Assertions.assertEquals(expected,actual, message)); }
    public static void assertEquals(char expected, Character actual, Supplier<String> messageSupplier) { TestTrace.assertion("assertEquals",expected,actual,()->Assertions.assertEquals(expected,actual, messageSupplier)); }
    public static void assertEquals(Character expected, char actual) { TestTrace.assertion("assertEquals",expected,actual,()->Assertions.assertEquals(expected,actual)); }
    public static void assertEquals(Character expected, char actual, String message) { TestTrace.assertion("assertEquals",expected,actual,()->Assertions.assertEquals(expected,actual, message)); }
    public static void assertEquals(Character expected, char actual, Supplier<String> messageSupplier) { TestTrace.assertion("assertEquals",expected,actual,()->Assertions.assertEquals(expected,actual, messageSupplier)); }
    public static void assertEquals(Character expected, Character actual) { TestTrace.assertion("assertEquals",expected,actual,()->Assertions.assertEquals(expected,actual)); }
    public static void assertEquals(Character expected, Character actual, String message) { TestTrace.assertion("assertEquals",expected,actual,()->Assertions.assertEquals(expected,actual, message)); }
    public static void assertEquals(Character expected, Character actual, Supplier<String> messageSupplier) { TestTrace.assertion("assertEquals",expected,actual,()->Assertions.assertEquals(expected,actual, messageSupplier)); }
    public static void assertEquals(int expected, int actual) { TestTrace.assertion("assertEquals",expected,actual,()->Assertions.assertEquals(expected,actual)); }
    public static void assertEquals(int expected, int actual, String message) { TestTrace.assertion("assertEquals",expected,actual,()->Assertions.assertEquals(expected,actual, message)); }
    public static void assertEquals(int expected, int actual, Supplier<String> messageSupplier) { TestTrace.assertion("assertEquals",expected,actual,()->Assertions.assertEquals(expected,actual, messageSupplier)); }
    public static void assertEquals(int expected, Integer actual) { TestTrace.assertion("assertEquals",expected,actual,()->Assertions.assertEquals(expected,actual)); }
    public static void assertEquals(int expected, Integer actual, String message) { TestTrace.assertion("assertEquals",expected,actual,()->Assertions.assertEquals(expected,actual, message)); }
    public static void assertEquals(int expected, Integer actual, Supplier<String> messageSupplier) { TestTrace.assertion("assertEquals",expected,actual,()->Assertions.assertEquals(expected,actual, messageSupplier)); }
    public static void assertEquals(Integer expected, int actual) { TestTrace.assertion("assertEquals",expected,actual,()->Assertions.assertEquals(expected,actual)); }
    public static void assertEquals(Integer expected, int actual, String message) { TestTrace.assertion("assertEquals",expected,actual,()->Assertions.assertEquals(expected,actual, message)); }
    public static void assertEquals(Integer expected, int actual, Supplier<String> messageSupplier) { TestTrace.assertion("assertEquals",expected,actual,()->Assertions.assertEquals(expected,actual, messageSupplier)); }
    public static void assertEquals(Integer expected, Integer actual) { TestTrace.assertion("assertEquals",expected,actual,()->Assertions.assertEquals(expected,actual)); }
    public static void assertEquals(Integer expected, Integer actual, String message) { TestTrace.assertion("assertEquals",expected,actual,()->Assertions.assertEquals(expected,actual, message)); }
    public static void assertEquals(Integer expected, Integer actual, Supplier<String> messageSupplier) { TestTrace.assertion("assertEquals",expected,actual,()->Assertions.assertEquals(expected,actual, messageSupplier)); }
    public static void assertEquals(long expected, long actual) { TestTrace.assertion("assertEquals",expected,actual,()->Assertions.assertEquals(expected,actual)); }
    public static void assertEquals(long expected, long actual, String message) { TestTrace.assertion("assertEquals",expected,actual,()->Assertions.assertEquals(expected,actual, message)); }
    public static void assertEquals(long expected, long actual, Supplier<String> messageSupplier) { TestTrace.assertion("assertEquals",expected,actual,()->Assertions.assertEquals(expected,actual, messageSupplier)); }
    public static void assertEquals(long expected, Long actual) { TestTrace.assertion("assertEquals",expected,actual,()->Assertions.assertEquals(expected,actual)); }
    public static void assertEquals(long expected, Long actual, String message) { TestTrace.assertion("assertEquals",expected,actual,()->Assertions.assertEquals(expected,actual, message)); }
    public static void assertEquals(long expected, Long actual, Supplier<String> messageSupplier) { TestTrace.assertion("assertEquals",expected,actual,()->Assertions.assertEquals(expected,actual, messageSupplier)); }
    public static void assertEquals(Long expected, long actual) { TestTrace.assertion("assertEquals",expected,actual,()->Assertions.assertEquals(expected,actual)); }
    public static void assertEquals(Long expected, long actual, String message) { TestTrace.assertion("assertEquals",expected,actual,()->Assertions.assertEquals(expected,actual, message)); }
    public static void assertEquals(Long expected, long actual, Supplier<String> messageSupplier) { TestTrace.assertion("assertEquals",expected,actual,()->Assertions.assertEquals(expected,actual, messageSupplier)); }
    public static void assertEquals(Long expected, Long actual) { TestTrace.assertion("assertEquals",expected,actual,()->Assertions.assertEquals(expected,actual)); }
    public static void assertEquals(Long expected, Long actual, String message) { TestTrace.assertion("assertEquals",expected,actual,()->Assertions.assertEquals(expected,actual, message)); }
    public static void assertEquals(Long expected, Long actual, Supplier<String> messageSupplier) { TestTrace.assertion("assertEquals",expected,actual,()->Assertions.assertEquals(expected,actual, messageSupplier)); }
    public static void assertEquals(float expected, float actual) { TestTrace.assertion("assertEquals",expected,actual,()->Assertions.assertEquals(expected,actual)); }
    public static void assertEquals(float expected, float actual, String message) { TestTrace.assertion("assertEquals",expected,actual,()->Assertions.assertEquals(expected,actual, message)); }
    public static void assertEquals(float expected, float actual, Supplier<String> messageSupplier) { TestTrace.assertion("assertEquals",expected,actual,()->Assertions.assertEquals(expected,actual, messageSupplier)); }
    public static void assertEquals(float expected, Float actual) { TestTrace.assertion("assertEquals",expected,actual,()->Assertions.assertEquals(expected,actual)); }
    public static void assertEquals(float expected, Float actual, String message) { TestTrace.assertion("assertEquals",expected,actual,()->Assertions.assertEquals(expected,actual, message)); }
    public static void assertEquals(float expected, Float actual, Supplier<String> messageSupplier) { TestTrace.assertion("assertEquals",expected,actual,()->Assertions.assertEquals(expected,actual, messageSupplier)); }
    public static void assertEquals(Float expected, float actual) { TestTrace.assertion("assertEquals",expected,actual,()->Assertions.assertEquals(expected,actual)); }
    public static void assertEquals(Float expected, float actual, String message) { TestTrace.assertion("assertEquals",expected,actual,()->Assertions.assertEquals(expected,actual, message)); }
    public static void assertEquals(Float expected, float actual, Supplier<String> messageSupplier) { TestTrace.assertion("assertEquals",expected,actual,()->Assertions.assertEquals(expected,actual, messageSupplier)); }
    public static void assertEquals(Float expected, Float actual) { TestTrace.assertion("assertEquals",expected,actual,()->Assertions.assertEquals(expected,actual)); }
    public static void assertEquals(Float expected, Float actual, String message) { TestTrace.assertion("assertEquals",expected,actual,()->Assertions.assertEquals(expected,actual, message)); }
    public static void assertEquals(Float expected, Float actual, Supplier<String> messageSupplier) { TestTrace.assertion("assertEquals",expected,actual,()->Assertions.assertEquals(expected,actual, messageSupplier)); }
    public static void assertEquals(double expected, double actual) { TestTrace.assertion("assertEquals",expected,actual,()->Assertions.assertEquals(expected,actual)); }
    public static void assertEquals(double expected, double actual, String message) { TestTrace.assertion("assertEquals",expected,actual,()->Assertions.assertEquals(expected,actual, message)); }
    public static void assertEquals(double expected, double actual, Supplier<String> messageSupplier) { TestTrace.assertion("assertEquals",expected,actual,()->Assertions.assertEquals(expected,actual, messageSupplier)); }
    public static void assertEquals(double expected, Double actual) { TestTrace.assertion("assertEquals",expected,actual,()->Assertions.assertEquals(expected,actual)); }
    public static void assertEquals(double expected, Double actual, String message) { TestTrace.assertion("assertEquals",expected,actual,()->Assertions.assertEquals(expected,actual, message)); }
    public static void assertEquals(double expected, Double actual, Supplier<String> messageSupplier) { TestTrace.assertion("assertEquals",expected,actual,()->Assertions.assertEquals(expected,actual, messageSupplier)); }
    public static void assertEquals(Double expected, double actual) { TestTrace.assertion("assertEquals",expected,actual,()->Assertions.assertEquals(expected,actual)); }
    public static void assertEquals(Double expected, double actual, String message) { TestTrace.assertion("assertEquals",expected,actual,()->Assertions.assertEquals(expected,actual, message)); }
    public static void assertEquals(Double expected, double actual, Supplier<String> messageSupplier) { TestTrace.assertion("assertEquals",expected,actual,()->Assertions.assertEquals(expected,actual, messageSupplier)); }
    public static void assertEquals(Double expected, Double actual) { TestTrace.assertion("assertEquals",expected,actual,()->Assertions.assertEquals(expected,actual)); }
    public static void assertEquals(Double expected, Double actual, String message) { TestTrace.assertion("assertEquals",expected,actual,()->Assertions.assertEquals(expected,actual, message)); }
    public static void assertEquals(Double expected, Double actual, Supplier<String> messageSupplier) { TestTrace.assertion("assertEquals",expected,actual,()->Assertions.assertEquals(expected,actual, messageSupplier)); }
    public static void assertNotEquals(Object expected, Object actual) { TestTrace.assertion("assertNotEquals",expected,actual,()->Assertions.assertNotEquals(expected,actual)); }
    public static void assertNotEquals(Object expected, Object actual, String message) { TestTrace.assertion("assertNotEquals",expected,actual,()->Assertions.assertNotEquals(expected,actual, message)); }
    public static void assertNotEquals(Object expected, Object actual, Supplier<String> messageSupplier) { TestTrace.assertion("assertNotEquals",expected,actual,()->Assertions.assertNotEquals(expected,actual, messageSupplier)); }
    public static void assertNotEquals(byte expected, byte actual) { TestTrace.assertion("assertNotEquals",expected,actual,()->Assertions.assertNotEquals(expected,actual)); }
    public static void assertNotEquals(byte expected, byte actual, String message) { TestTrace.assertion("assertNotEquals",expected,actual,()->Assertions.assertNotEquals(expected,actual, message)); }
    public static void assertNotEquals(byte expected, byte actual, Supplier<String> messageSupplier) { TestTrace.assertion("assertNotEquals",expected,actual,()->Assertions.assertNotEquals(expected,actual, messageSupplier)); }
    public static void assertNotEquals(byte expected, Byte actual) { TestTrace.assertion("assertNotEquals",expected,actual,()->Assertions.assertNotEquals(expected,actual)); }
    public static void assertNotEquals(byte expected, Byte actual, String message) { TestTrace.assertion("assertNotEquals",expected,actual,()->Assertions.assertNotEquals(expected,actual, message)); }
    public static void assertNotEquals(byte expected, Byte actual, Supplier<String> messageSupplier) { TestTrace.assertion("assertNotEquals",expected,actual,()->Assertions.assertNotEquals(expected,actual, messageSupplier)); }
    public static void assertNotEquals(Byte expected, byte actual) { TestTrace.assertion("assertNotEquals",expected,actual,()->Assertions.assertNotEquals(expected,actual)); }
    public static void assertNotEquals(Byte expected, byte actual, String message) { TestTrace.assertion("assertNotEquals",expected,actual,()->Assertions.assertNotEquals(expected,actual, message)); }
    public static void assertNotEquals(Byte expected, byte actual, Supplier<String> messageSupplier) { TestTrace.assertion("assertNotEquals",expected,actual,()->Assertions.assertNotEquals(expected,actual, messageSupplier)); }
    public static void assertNotEquals(Byte expected, Byte actual) { TestTrace.assertion("assertNotEquals",expected,actual,()->Assertions.assertNotEquals(expected,actual)); }
    public static void assertNotEquals(Byte expected, Byte actual, String message) { TestTrace.assertion("assertNotEquals",expected,actual,()->Assertions.assertNotEquals(expected,actual, message)); }
    public static void assertNotEquals(Byte expected, Byte actual, Supplier<String> messageSupplier) { TestTrace.assertion("assertNotEquals",expected,actual,()->Assertions.assertNotEquals(expected,actual, messageSupplier)); }
    public static void assertNotEquals(short expected, short actual) { TestTrace.assertion("assertNotEquals",expected,actual,()->Assertions.assertNotEquals(expected,actual)); }
    public static void assertNotEquals(short expected, short actual, String message) { TestTrace.assertion("assertNotEquals",expected,actual,()->Assertions.assertNotEquals(expected,actual, message)); }
    public static void assertNotEquals(short expected, short actual, Supplier<String> messageSupplier) { TestTrace.assertion("assertNotEquals",expected,actual,()->Assertions.assertNotEquals(expected,actual, messageSupplier)); }
    public static void assertNotEquals(short expected, Short actual) { TestTrace.assertion("assertNotEquals",expected,actual,()->Assertions.assertNotEquals(expected,actual)); }
    public static void assertNotEquals(short expected, Short actual, String message) { TestTrace.assertion("assertNotEquals",expected,actual,()->Assertions.assertNotEquals(expected,actual, message)); }
    public static void assertNotEquals(short expected, Short actual, Supplier<String> messageSupplier) { TestTrace.assertion("assertNotEquals",expected,actual,()->Assertions.assertNotEquals(expected,actual, messageSupplier)); }
    public static void assertNotEquals(Short expected, short actual) { TestTrace.assertion("assertNotEquals",expected,actual,()->Assertions.assertNotEquals(expected,actual)); }
    public static void assertNotEquals(Short expected, short actual, String message) { TestTrace.assertion("assertNotEquals",expected,actual,()->Assertions.assertNotEquals(expected,actual, message)); }
    public static void assertNotEquals(Short expected, short actual, Supplier<String> messageSupplier) { TestTrace.assertion("assertNotEquals",expected,actual,()->Assertions.assertNotEquals(expected,actual, messageSupplier)); }
    public static void assertNotEquals(Short expected, Short actual) { TestTrace.assertion("assertNotEquals",expected,actual,()->Assertions.assertNotEquals(expected,actual)); }
    public static void assertNotEquals(Short expected, Short actual, String message) { TestTrace.assertion("assertNotEquals",expected,actual,()->Assertions.assertNotEquals(expected,actual, message)); }
    public static void assertNotEquals(Short expected, Short actual, Supplier<String> messageSupplier) { TestTrace.assertion("assertNotEquals",expected,actual,()->Assertions.assertNotEquals(expected,actual, messageSupplier)); }
    public static void assertNotEquals(char expected, char actual) { TestTrace.assertion("assertNotEquals",expected,actual,()->Assertions.assertNotEquals(expected,actual)); }
    public static void assertNotEquals(char expected, char actual, String message) { TestTrace.assertion("assertNotEquals",expected,actual,()->Assertions.assertNotEquals(expected,actual, message)); }
    public static void assertNotEquals(char expected, char actual, Supplier<String> messageSupplier) { TestTrace.assertion("assertNotEquals",expected,actual,()->Assertions.assertNotEquals(expected,actual, messageSupplier)); }
    public static void assertNotEquals(char expected, Character actual) { TestTrace.assertion("assertNotEquals",expected,actual,()->Assertions.assertNotEquals(expected,actual)); }
    public static void assertNotEquals(char expected, Character actual, String message) { TestTrace.assertion("assertNotEquals",expected,actual,()->Assertions.assertNotEquals(expected,actual, message)); }
    public static void assertNotEquals(char expected, Character actual, Supplier<String> messageSupplier) { TestTrace.assertion("assertNotEquals",expected,actual,()->Assertions.assertNotEquals(expected,actual, messageSupplier)); }
    public static void assertNotEquals(Character expected, char actual) { TestTrace.assertion("assertNotEquals",expected,actual,()->Assertions.assertNotEquals(expected,actual)); }
    public static void assertNotEquals(Character expected, char actual, String message) { TestTrace.assertion("assertNotEquals",expected,actual,()->Assertions.assertNotEquals(expected,actual, message)); }
    public static void assertNotEquals(Character expected, char actual, Supplier<String> messageSupplier) { TestTrace.assertion("assertNotEquals",expected,actual,()->Assertions.assertNotEquals(expected,actual, messageSupplier)); }
    public static void assertNotEquals(Character expected, Character actual) { TestTrace.assertion("assertNotEquals",expected,actual,()->Assertions.assertNotEquals(expected,actual)); }
    public static void assertNotEquals(Character expected, Character actual, String message) { TestTrace.assertion("assertNotEquals",expected,actual,()->Assertions.assertNotEquals(expected,actual, message)); }
    public static void assertNotEquals(Character expected, Character actual, Supplier<String> messageSupplier) { TestTrace.assertion("assertNotEquals",expected,actual,()->Assertions.assertNotEquals(expected,actual, messageSupplier)); }
    public static void assertNotEquals(int expected, int actual) { TestTrace.assertion("assertNotEquals",expected,actual,()->Assertions.assertNotEquals(expected,actual)); }
    public static void assertNotEquals(int expected, int actual, String message) { TestTrace.assertion("assertNotEquals",expected,actual,()->Assertions.assertNotEquals(expected,actual, message)); }
    public static void assertNotEquals(int expected, int actual, Supplier<String> messageSupplier) { TestTrace.assertion("assertNotEquals",expected,actual,()->Assertions.assertNotEquals(expected,actual, messageSupplier)); }
    public static void assertNotEquals(int expected, Integer actual) { TestTrace.assertion("assertNotEquals",expected,actual,()->Assertions.assertNotEquals(expected,actual)); }
    public static void assertNotEquals(int expected, Integer actual, String message) { TestTrace.assertion("assertNotEquals",expected,actual,()->Assertions.assertNotEquals(expected,actual, message)); }
    public static void assertNotEquals(int expected, Integer actual, Supplier<String> messageSupplier) { TestTrace.assertion("assertNotEquals",expected,actual,()->Assertions.assertNotEquals(expected,actual, messageSupplier)); }
    public static void assertNotEquals(Integer expected, int actual) { TestTrace.assertion("assertNotEquals",expected,actual,()->Assertions.assertNotEquals(expected,actual)); }
    public static void assertNotEquals(Integer expected, int actual, String message) { TestTrace.assertion("assertNotEquals",expected,actual,()->Assertions.assertNotEquals(expected,actual, message)); }
    public static void assertNotEquals(Integer expected, int actual, Supplier<String> messageSupplier) { TestTrace.assertion("assertNotEquals",expected,actual,()->Assertions.assertNotEquals(expected,actual, messageSupplier)); }
    public static void assertNotEquals(Integer expected, Integer actual) { TestTrace.assertion("assertNotEquals",expected,actual,()->Assertions.assertNotEquals(expected,actual)); }
    public static void assertNotEquals(Integer expected, Integer actual, String message) { TestTrace.assertion("assertNotEquals",expected,actual,()->Assertions.assertNotEquals(expected,actual, message)); }
    public static void assertNotEquals(Integer expected, Integer actual, Supplier<String> messageSupplier) { TestTrace.assertion("assertNotEquals",expected,actual,()->Assertions.assertNotEquals(expected,actual, messageSupplier)); }
    public static void assertNotEquals(long expected, long actual) { TestTrace.assertion("assertNotEquals",expected,actual,()->Assertions.assertNotEquals(expected,actual)); }
    public static void assertNotEquals(long expected, long actual, String message) { TestTrace.assertion("assertNotEquals",expected,actual,()->Assertions.assertNotEquals(expected,actual, message)); }
    public static void assertNotEquals(long expected, long actual, Supplier<String> messageSupplier) { TestTrace.assertion("assertNotEquals",expected,actual,()->Assertions.assertNotEquals(expected,actual, messageSupplier)); }
    public static void assertNotEquals(long expected, Long actual) { TestTrace.assertion("assertNotEquals",expected,actual,()->Assertions.assertNotEquals(expected,actual)); }
    public static void assertNotEquals(long expected, Long actual, String message) { TestTrace.assertion("assertNotEquals",expected,actual,()->Assertions.assertNotEquals(expected,actual, message)); }
    public static void assertNotEquals(long expected, Long actual, Supplier<String> messageSupplier) { TestTrace.assertion("assertNotEquals",expected,actual,()->Assertions.assertNotEquals(expected,actual, messageSupplier)); }
    public static void assertNotEquals(Long expected, long actual) { TestTrace.assertion("assertNotEquals",expected,actual,()->Assertions.assertNotEquals(expected,actual)); }
    public static void assertNotEquals(Long expected, long actual, String message) { TestTrace.assertion("assertNotEquals",expected,actual,()->Assertions.assertNotEquals(expected,actual, message)); }
    public static void assertNotEquals(Long expected, long actual, Supplier<String> messageSupplier) { TestTrace.assertion("assertNotEquals",expected,actual,()->Assertions.assertNotEquals(expected,actual, messageSupplier)); }
    public static void assertNotEquals(Long expected, Long actual) { TestTrace.assertion("assertNotEquals",expected,actual,()->Assertions.assertNotEquals(expected,actual)); }
    public static void assertNotEquals(Long expected, Long actual, String message) { TestTrace.assertion("assertNotEquals",expected,actual,()->Assertions.assertNotEquals(expected,actual, message)); }
    public static void assertNotEquals(Long expected, Long actual, Supplier<String> messageSupplier) { TestTrace.assertion("assertNotEquals",expected,actual,()->Assertions.assertNotEquals(expected,actual, messageSupplier)); }
    public static void assertNotEquals(float expected, float actual) { TestTrace.assertion("assertNotEquals",expected,actual,()->Assertions.assertNotEquals(expected,actual)); }
    public static void assertNotEquals(float expected, float actual, String message) { TestTrace.assertion("assertNotEquals",expected,actual,()->Assertions.assertNotEquals(expected,actual, message)); }
    public static void assertNotEquals(float expected, float actual, Supplier<String> messageSupplier) { TestTrace.assertion("assertNotEquals",expected,actual,()->Assertions.assertNotEquals(expected,actual, messageSupplier)); }
    public static void assertNotEquals(float expected, Float actual) { TestTrace.assertion("assertNotEquals",expected,actual,()->Assertions.assertNotEquals(expected,actual)); }
    public static void assertNotEquals(float expected, Float actual, String message) { TestTrace.assertion("assertNotEquals",expected,actual,()->Assertions.assertNotEquals(expected,actual, message)); }
    public static void assertNotEquals(float expected, Float actual, Supplier<String> messageSupplier) { TestTrace.assertion("assertNotEquals",expected,actual,()->Assertions.assertNotEquals(expected,actual, messageSupplier)); }
    public static void assertNotEquals(Float expected, float actual) { TestTrace.assertion("assertNotEquals",expected,actual,()->Assertions.assertNotEquals(expected,actual)); }
    public static void assertNotEquals(Float expected, float actual, String message) { TestTrace.assertion("assertNotEquals",expected,actual,()->Assertions.assertNotEquals(expected,actual, message)); }
    public static void assertNotEquals(Float expected, float actual, Supplier<String> messageSupplier) { TestTrace.assertion("assertNotEquals",expected,actual,()->Assertions.assertNotEquals(expected,actual, messageSupplier)); }
    public static void assertNotEquals(Float expected, Float actual) { TestTrace.assertion("assertNotEquals",expected,actual,()->Assertions.assertNotEquals(expected,actual)); }
    public static void assertNotEquals(Float expected, Float actual, String message) { TestTrace.assertion("assertNotEquals",expected,actual,()->Assertions.assertNotEquals(expected,actual, message)); }
    public static void assertNotEquals(Float expected, Float actual, Supplier<String> messageSupplier) { TestTrace.assertion("assertNotEquals",expected,actual,()->Assertions.assertNotEquals(expected,actual, messageSupplier)); }
    public static void assertNotEquals(double expected, double actual) { TestTrace.assertion("assertNotEquals",expected,actual,()->Assertions.assertNotEquals(expected,actual)); }
    public static void assertNotEquals(double expected, double actual, String message) { TestTrace.assertion("assertNotEquals",expected,actual,()->Assertions.assertNotEquals(expected,actual, message)); }
    public static void assertNotEquals(double expected, double actual, Supplier<String> messageSupplier) { TestTrace.assertion("assertNotEquals",expected,actual,()->Assertions.assertNotEquals(expected,actual, messageSupplier)); }
    public static void assertNotEquals(double expected, Double actual) { TestTrace.assertion("assertNotEquals",expected,actual,()->Assertions.assertNotEquals(expected,actual)); }
    public static void assertNotEquals(double expected, Double actual, String message) { TestTrace.assertion("assertNotEquals",expected,actual,()->Assertions.assertNotEquals(expected,actual, message)); }
    public static void assertNotEquals(double expected, Double actual, Supplier<String> messageSupplier) { TestTrace.assertion("assertNotEquals",expected,actual,()->Assertions.assertNotEquals(expected,actual, messageSupplier)); }
    public static void assertNotEquals(Double expected, double actual) { TestTrace.assertion("assertNotEquals",expected,actual,()->Assertions.assertNotEquals(expected,actual)); }
    public static void assertNotEquals(Double expected, double actual, String message) { TestTrace.assertion("assertNotEquals",expected,actual,()->Assertions.assertNotEquals(expected,actual, message)); }
    public static void assertNotEquals(Double expected, double actual, Supplier<String> messageSupplier) { TestTrace.assertion("assertNotEquals",expected,actual,()->Assertions.assertNotEquals(expected,actual, messageSupplier)); }
    public static void assertNotEquals(Double expected, Double actual) { TestTrace.assertion("assertNotEquals",expected,actual,()->Assertions.assertNotEquals(expected,actual)); }
    public static void assertNotEquals(Double expected, Double actual, String message) { TestTrace.assertion("assertNotEquals",expected,actual,()->Assertions.assertNotEquals(expected,actual, message)); }
    public static void assertNotEquals(Double expected, Double actual, Supplier<String> messageSupplier) { TestTrace.assertion("assertNotEquals",expected,actual,()->Assertions.assertNotEquals(expected,actual, messageSupplier)); }
    public static void assertTrue(boolean actual) { TestTrace.assertion("assertTrue",true,actual,()->Assertions.assertTrue(actual)); }
    public static void assertTrue(boolean actual, String message) { TestTrace.assertion("assertTrue",true,actual,()->Assertions.assertTrue(actual, message)); }
    public static void assertTrue(boolean actual, Supplier<String> messageSupplier) { TestTrace.assertion("assertTrue",true,actual,()->Assertions.assertTrue(actual, messageSupplier)); }
    public static void assertFalse(boolean actual) { TestTrace.assertion("assertFalse",false,actual,()->Assertions.assertFalse(actual)); }
    public static void assertFalse(boolean actual, String message) { TestTrace.assertion("assertFalse",false,actual,()->Assertions.assertFalse(actual, message)); }
    public static void assertFalse(boolean actual, Supplier<String> messageSupplier) { TestTrace.assertion("assertFalse",false,actual,()->Assertions.assertFalse(actual, messageSupplier)); }
    public static void assertNull(Object actual) { TestTrace.assertion("assertNull","null",actual,()->Assertions.assertNull(actual)); }
    public static void assertNull(Object actual, String message) { TestTrace.assertion("assertNull","null",actual,()->Assertions.assertNull(actual, message)); }
    public static void assertNull(Object actual, Supplier<String> messageSupplier) { TestTrace.assertion("assertNull","null",actual,()->Assertions.assertNull(actual, messageSupplier)); }
    public static void assertNotNull(Object actual) { TestTrace.assertion("assertNotNull","non-null",actual,()->Assertions.assertNotNull(actual)); }
    public static void assertNotNull(Object actual, String message) { TestTrace.assertion("assertNotNull","non-null",actual,()->Assertions.assertNotNull(actual, message)); }
    public static void assertNotNull(Object actual, Supplier<String> messageSupplier) { TestTrace.assertion("assertNotNull","non-null",actual,()->Assertions.assertNotNull(actual, messageSupplier)); }
    public static void assertSame(Object expected, Object actual) { TestTrace.assertion("assertSame",expected,actual,()->Assertions.assertSame(expected,actual)); }
    public static void assertSame(Object expected, Object actual, String message) { TestTrace.assertion("assertSame",expected,actual,()->Assertions.assertSame(expected,actual, message)); }
    public static void assertSame(Object expected, Object actual, Supplier<String> messageSupplier) { TestTrace.assertion("assertSame",expected,actual,()->Assertions.assertSame(expected,actual, messageSupplier)); }
    public static void assertArrayEquals(byte[] expected, byte[] actual) { TestTrace.assertion("assertArrayEquals",expected,actual,()->Assertions.assertArrayEquals(expected,actual)); }
    public static void assertArrayEquals(byte[] expected, byte[] actual, String message) { TestTrace.assertion("assertArrayEquals",expected,actual,()->Assertions.assertArrayEquals(expected,actual, message)); }
    public static void assertArrayEquals(byte[] expected, byte[] actual, Supplier<String> messageSupplier) { TestTrace.assertion("assertArrayEquals",expected,actual,()->Assertions.assertArrayEquals(expected,actual, messageSupplier)); }
    public static <T extends Throwable> T assertThrows(Class<T> expected, Executable body) {
        T result=Assertions.assertThrows(expected,body);
        TestTrace.assertion("assertThrows",expected.getName(),result.getClass().getName()+": "+result.getMessage(),()->{});
        return result;
    }
    public static <T> T assertInstanceOf(Class<T> expected,Object actual) {
        T result=Assertions.assertInstanceOf(expected,actual);
        TestTrace.assertion("assertInstanceOf",expected.getName(),actual.getClass().getName(),()->{});
        return result;
    }
    public static void assertDoesNotThrow(Executable body) {
        Assertions.assertDoesNotThrow(body);
        TestTrace.assertion("assertDoesNotThrow","no exception","no exception",()->{});
    }
    public static <T extends Throwable> T assertThrows(Class<T> expected, Executable body, String message) {
        T result=Assertions.assertThrows(expected,body, message);
        TestTrace.assertion("assertThrows",expected.getName(),result.getClass().getName()+": "+result.getMessage(),()->{});
        return result;
    }
    public static <T> T assertInstanceOf(Class<T> expected,Object actual, String message) {
        T result=Assertions.assertInstanceOf(expected,actual, message);
        TestTrace.assertion("assertInstanceOf",expected.getName(),actual.getClass().getName(),()->{});
        return result;
    }
    public static void assertDoesNotThrow(Executable body, String message) {
        Assertions.assertDoesNotThrow(body, message);
        TestTrace.assertion("assertDoesNotThrow","no exception","no exception",()->{});
    }
    public static <T extends Throwable> T assertThrows(Class<T> expected, Executable body, Supplier<String> messageSupplier) {
        T result=Assertions.assertThrows(expected,body, messageSupplier);
        TestTrace.assertion("assertThrows",expected.getName(),result.getClass().getName()+": "+result.getMessage(),()->{});
        return result;
    }
    public static <T> T assertInstanceOf(Class<T> expected,Object actual, Supplier<String> messageSupplier) {
        T result=Assertions.assertInstanceOf(expected,actual, messageSupplier);
        TestTrace.assertion("assertInstanceOf",expected.getName(),actual.getClass().getName(),()->{});
        return result;
    }
    public static void assertDoesNotThrow(Executable body, Supplier<String> messageSupplier) {
        Assertions.assertDoesNotThrow(body, messageSupplier);
        TestTrace.assertion("assertDoesNotThrow","no exception","no exception",()->{});
    }
}
