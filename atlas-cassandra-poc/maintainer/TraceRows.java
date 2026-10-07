package org.apache.cassandra.distributed.test;

import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.Base64;
import org.apache.cassandra.distributed.shared.AssertUtils;

/** Evidence-only overlay: retain the pinned harness's row comparison. */
public final class TraceRows {
    private static String encode(Object[][] rows) {
        return Base64.getEncoder().encodeToString(Arrays.deepToString(rows).getBytes(StandardCharsets.UTF_8));
    }
    public static void assertRows(Object[][] actual,Object[]... expected) {
        System.out.println("ATLAS_ROWS expected="+encode(expected)+" actual="+encode(actual));
        AssertUtils.assertRows(actual,expected);
    }
}
