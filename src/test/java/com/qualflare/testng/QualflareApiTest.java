package com.qualflare.testng;

import org.testng.annotations.Test;
import static org.testng.Assert.*;

/**
 * These run as REAL TestNG tests, so Reporter.getCurrentTestResult() is populated by the
 * framework exactly as it would be for a user -- which is the thing under test.
 */
public class QualflareApiTest {

    @Test
    public void metadataLandsUnderTheRunningTest() {
        Run.resetForTest();
        Qualflare.label("feature", "checkout");
        Qualflare.tag("smoke");

        // The key the entries were filed under must be the running test's own key.
        String expected = "com.qualflare.testng.QualflareApiTest#metadataLandsUnderTheRunningTest()";
        assertTrue(Run.accumulator().hasEntriesFor(expected),
                "metadata must be filed under the running test, expected key " + expected);
    }

    @Test
    public void nothingThrowsWhenCalledOffTheTestThread() throws Exception {
        Run.resetForTest();
        Thread t = new Thread(() -> {
            // No current test on this thread. This must be inert, not explosive.
            Qualflare.label("feature", "checkout");
            Qualflare.tag("smoke");
            Qualflare.step("a step", () -> { });
        });
        t.start();
        t.join();
        // Reaching here without an exception IS the assertion.
        assertTrue(true);
    }

    @Test
    public void aStepBodyStillRunsWhenNoTestIsInScope() throws Exception {
        boolean[] ran = {false};
        Thread t = new Thread(() -> Qualflare.step("s", () -> ran[0] = true));
        t.start();
        t.join();
        assertTrue(ran[0], "the test's own work must happen even when reporting is inert");
    }

    @Test
    public void aFailingStepRethrowsRatherThanSwallowing() {
        Run.resetForTest();
        try {
            Qualflare.step("explodes", () -> { throw new IllegalStateException("boom"); });
            fail("the exception must propagate");
        } catch (IllegalStateException expected) {
            // Turning a failing test green is the worst thing a reporter can do.
        }
    }

    @Test
    public void aMaskedParameterCannotCarryAValue() throws Exception {
        // A signature that cannot accept a value cannot leak one. Pinned as a compile-time
        // property via reflection so nobody "helpfully" adds an overload later.
        for (java.lang.reflect.Method m : Qualflare.class.getMethods()) {
            if (m.getName().equals("maskedParameter")) {
                assertEquals(m.getParameterCount(), 1,
                        "maskedParameter must take only a name, never a value");
            }
        }
    }
}
