package com.qualflare.testng;

import org.testng.IConfigurationListener;
import org.testng.IExecutionListener;
import org.testng.ITestListener;
import org.testng.ITestResult;

import java.io.PrintWriter;
import java.io.StringWriter;

/**
 * The only TestNG-aware lifecycle class. Translates callbacks into accumulator calls and
 * writes the report once, at the end of the JVM's execution.
 *
 * <p>Registered through {@code META-INF/services/org.testng.ITestNGListener}, so adding
 * the dependency is the whole setup. Measured: TestNG wires ServiceLoader listeners in by
 * default -- its own {@code setListenersToSkipFromBeingWiredInViaServiceLoaders} method
 * exists precisely to opt out.
 *
 * <p><b>Unlike qualflare-junit5, there is no multi-session problem here.</b> Measured on
 * TestNG 7.12.0 under Surefire 3.5.6: {@code onExecutionStart}/{@code onExecutionFinish}
 * fire exactly once per JVM, one listener instance serves the whole run, and Surefire's
 * {@code rerunFailingTestsCount} does not apply to the TestNG provider at all -- retries
 * come only from {@code IRetryAnalyzer}, in-process. State is still JVM-scoped (see
 * {@link Run}) so that {@code forkCount > 1} writes one file per JVM, which is the
 * directory-merge model {@code qf collect} already uses.
 */
public final class QualflareListener
        implements ITestListener, IExecutionListener, IConfigurationListener {

    private static Accumulator acc() {
        return Run.accumulator();
    }

    // ---- execution ---------------------------------------------------------------

    @Override
    public void onExecutionStart() {
        Run.ensureHook();
    }

    @Override
    public void onExecutionFinish() {
        Run.write();
    }

    /** Package-private so tests can force a write without waiting for JVM shutdown. */
    void write() {
        Run.write();
    }

    // ---- tests -------------------------------------------------------------------

    @Override
    public void onTestStart(ITestResult result) {
        acc().started(TestKey.of(result), System.nanoTime());
    }

    @Override
    public void onTestSuccess(ITestResult result) {
        record(result, Status.PASSED);
    }

    @Override
    public void onTestFailure(ITestResult result) {
        record(result, Status.of(result));
    }

    @Override
    public void onTestFailedButWithinSuccessPercentage(ITestResult result) {
        record(result, Status.of(result));
    }

    /**
     * TestNG has a dedicated timeout callback, which JUnit does not. Mapping it onto
     * "failed" would throw away the difference between a broken test and a hung one.
     */
    @Override
    public void onTestFailedWithTimeout(ITestResult result) {
        record(result, Status.TIMEOUT);
    }

    /**
     * Two very different things arrive here, told apart by {@code wasRetried()}:
     * a FAILED attempt that will be retried, and a genuine skip. {@link Status#of}
     * owns that decision.
     */
    @Override
    public void onTestSkipped(ITestResult result) {
        if (result.wasRetried()) {
            record(result, Status.of(result));
            return;
        }
        Throwable t = result.getThrowable();
        acc().skipped(TestKey.of(result), suiteOf(result), classOf(result),
                TestKey.displayName(result), legacyOf(result), messageOf(t));
    }

    private void record(ITestResult result, String status) {
        Throwable t = result.getThrowable();
        acc().finished(TestKey.of(result), suiteOf(result), classOf(result),
                TestKey.displayName(result), legacyOf(result),
                status, System.nanoTime(), messageOf(t), traceOf(t));
    }

    // ---- configuration -----------------------------------------------------------

    /**
     * A throwing {@code @BeforeClass} produces this, PLUS a skip for each test it guarded
     * (measured -- unlike JUnit, where the guarded tests emit nothing at all and a broken
     * suite can read green).
     *
     * <p>The guarded skips are recorded by {@link #onTestSkipped} as ordinary skips. This
     * method adds the configuration method itself as an {@code error} case, because a suite
     * whose tests are ALL skipped reads as "nothing ran" rather than "broken". That is the
     * same false-green qualflare-junit5 emits a synthetic case to avoid -- except here the
     * case carries the real method name and the real stack trace.
     *
     * <p>Only FAILING configuration methods produce a case. Reporting successful ones would
     * add setup noise to every suite in the product.
     */
    @Override
    public void onConfigurationFailure(ITestResult result) {
        Throwable t = result.getThrowable();
        String display = "[config] " + classOf(result) + "#" + result.getMethod().getMethodName();
        acc().finished(TestKey.of(result) + "/[qf-config-failure]",
                suiteOf(result), classOf(result), display, legacyOf(result),
                Status.ERROR, System.nanoTime(), messageOf(t), traceOf(t));
    }

    // ---- naming ------------------------------------------------------------------

    /** Cases are grouped by their class, matching how qualflare-junit5 groups them. */
    private static String suiteOf(ITestResult result) {
        return result.getTestClass().getName();
    }

    private static String classOf(ITestResult result) {
        return result.getTestClass().getName();
    }

    /** The JUnit-style flat name, used for matching against previously-seen cases. */
    private static String legacyOf(ITestResult result) {
        return result.getTestClass().getName() + "." + result.getMethod().getMethodName();
    }

    static String messageOf(Throwable t) {
        if (t == null) {
            return "";
        }
        String m = t.getMessage();
        return m == null ? t.getClass().getSimpleName() : m;
    }

    static String traceOf(Throwable t) {
        if (t == null) {
            return "";
        }
        StringWriter w = new StringWriter();
        t.printStackTrace(new PrintWriter(w));
        return w.toString();
    }
}
