package org.apache.cassandra.distributed.test;

import java.nio.charset.StandardCharsets;
import java.util.Base64;
import org.junit.Assert;

/** Saves evaluated operands while retaining JUnit4 assertion semantics. */
public final class TraceChecks extends Assert {
    private static String encode(Object value) { return Base64.getEncoder().encodeToString(String.valueOf(value).getBytes(StandardCharsets.UTF_8)); }
    private static void check(String operation,Object expected,Object actual,Runnable compare) {
        System.out.println("ATLAS_CHECK operation="+operation+" expected="+encode(expected)+" actual="+encode(actual));
        compare.run();
    }
    public static void assertEquals(Object expected,Object actual) { check("assertEquals",expected,actual,()->Assert.assertEquals(expected,actual)); }
    public static void assertEquals(String message,Object expected,Object actual) { check("assertEquals",expected,actual,()->Assert.assertEquals(message,expected,actual)); }
    public static void assertEquals(long expected,long actual) { check("assertEquals",expected,actual,()->Assert.assertEquals(expected,actual)); }
    public static void assertEquals(String message,long expected,long actual) { check("assertEquals",expected,actual,()->Assert.assertEquals(message,expected,actual)); }
    public static void assertNotEquals(Object expected,Object actual) { check("assertNotEquals",expected,actual,()->Assert.assertNotEquals(expected,actual)); }
    public static void assertNotEquals(String message,Object expected,Object actual) { check("assertNotEquals",expected,actual,()->Assert.assertNotEquals(message,expected,actual)); }
    public static void assertNotEquals(long expected,long actual) { check("assertNotEquals",expected,actual,()->Assert.assertNotEquals(expected,actual)); }
    public static void assertNotEquals(String message,long expected,long actual) { check("assertNotEquals",expected,actual,()->Assert.assertNotEquals(message,expected,actual)); }
    public static void assertSame(Object expected,Object actual) { check("assertSame",expected,actual,()->Assert.assertSame(expected,actual)); }
    public static void assertSame(String message,Object expected,Object actual) { check("assertSame",expected,actual,()->Assert.assertSame(message,expected,actual)); }
    public static void assertNotSame(Object expected,Object actual) { check("assertNotSame",expected,actual,()->Assert.assertNotSame(expected,actual)); }
    public static void assertNotSame(String message,Object expected,Object actual) { check("assertNotSame",expected,actual,()->Assert.assertNotSame(message,expected,actual)); }
    public static void assertTrue(boolean actual) { check("assertTrue",true,actual,()->Assert.assertTrue(actual)); }
    public static void assertTrue(String message,boolean actual) { check("assertTrue",true,actual,()->Assert.assertTrue(message,actual)); }
    public static void assertFalse(boolean actual) { check("assertFalse",false,actual,()->Assert.assertFalse(actual)); }
    public static void assertFalse(String message,boolean actual) { check("assertFalse",false,actual,()->Assert.assertFalse(message,actual)); }
    public static void assertNull(Object actual) { check("assertNull","null",actual,()->Assert.assertNull(actual)); }
    public static void assertNull(String message,Object actual) { check("assertNull","null",actual,()->Assert.assertNull(message,actual)); }
    public static void assertNotNull(Object actual) { check("assertNotNull","non-null",actual,()->Assert.assertNotNull(actual)); }
    public static void assertNotNull(String message,Object actual) { check("assertNotNull","non-null",actual,()->Assert.assertNotNull(message,actual)); }
}
