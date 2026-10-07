package atlas.poc;

import org.junit.jupiter.api.extension.*;

/** Associates diagnostics with the concrete inherited contract execution. */
public final class TraceExtension implements BeforeEachCallback,AfterEachCallback {
    public void beforeEach(ExtensionContext c) {
        TestTrace.begin(c.getRequiredTestClass().getName()+"--"+c.getRequiredTestMethod().getName());
        TestTrace.event("case","class",c.getRequiredTestClass().getName(),"method",c.getRequiredTestMethod().getName(),"title",c.getDisplayName());
    }
    public void afterEach(ExtensionContext c) {
        try { TestTrace.event("end","outcome",c.getExecutionException().isPresent()?"FAILED":"PASSED","error",c.getExecutionException().map(Object::toString).orElse("")); }
        finally { TestTrace.end(); }
    }
}
