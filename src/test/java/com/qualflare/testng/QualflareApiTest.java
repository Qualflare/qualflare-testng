package com.qualflare.testng;

import org.testng.Reporter;
import org.testng.annotations.Test;

import java.util.concurrent.atomic.AtomicReference;

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

    /**
     * An exception thrown on a spawned thread dies on that thread: {@code join()} does not
     * propagate it, so the earlier version of this test -- whose real assertion was
     * {@code assertTrue(true)} -- could not fail. Proven: making {@code emit} throw
     * unconditionally failed three other tests and left this one green. The
     * uncaught-exception handler is what makes the claim in the name enforceable.
     *
     * <p>The result is also cleared explicitly, because TestNG's thread-local is an
     * {@code InheritableThreadLocal}: a plain child thread would inherit this test's result
     * and would NOT be out of scope at all.
     */
    @Test
    public void nothingThrowsWhenNoTestIsInScope() throws Exception {
        Run.resetForTest();
        AtomicReference<Throwable> caught = new AtomicReference<>();
        Thread t = new Thread(() -> {
            Reporter.setCurrentTestResult(null);
            // No current test on this thread. This must be inert, not explosive.
            Qualflare.label("feature", "checkout");
            Qualflare.tag("smoke");
            Qualflare.priority(Qualflare.HIGH);
            Qualflare.description("text");
            Qualflare.parameter("sku", "widget");
            Qualflare.maskedParameter("token");
            Qualflare.link("https://example.test/1");
            Qualflare.step("a step", () -> { });
        });
        t.setUncaughtExceptionHandler((thread, e) -> caught.set(e));
        t.start();
        t.join();

        assertNull(caught.get(), "no API call may throw when no test is in scope, but one"
                + " did: " + caught.get());
    }

    @Test
    public void aStepBodyStillRunsWhenNoTestIsInScope() throws Exception {
        boolean[] ran = {false};
        AtomicReference<Throwable> caught = new AtomicReference<>();
        Thread t = new Thread(() -> {
            Reporter.setCurrentTestResult(null);
            Qualflare.step("s", () -> ran[0] = true);
        });
        t.setUncaughtExceptionHandler((thread, e) -> caught.set(e));
        t.start();
        t.join();
        assertNull(caught.get(), String.valueOf(caught.get()));
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
        //
        // The found flag is load-bearing: without it, a rename of maskedParameter would
        // leave the loop body unexecuted and this test would pass vacuously -- green for
        // the exact change that removes the property it exists to protect.
        int found = 0;
        for (java.lang.reflect.Method m : Qualflare.class.getMethods()) {
            if (m.getName().equals("maskedParameter")) {
                found++;
                assertEquals(m.getParameterCount(), 1,
                        "maskedParameter must take only a name, never a value");
            }
        }
        assertEquals(found, 1, "expected exactly one maskedParameter method, found " + found);
    }
}
