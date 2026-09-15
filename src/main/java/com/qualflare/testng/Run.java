package com.qualflare.testng;

import java.util.concurrent.atomic.AtomicBoolean;

/**
 * JVM-scoped run state.
 *
 * <p>Run state lives here, in statics, rather than in listener fields, because the unit
 * that matters for this reporter is the JVM, not the listener instance. Measured on
 * TestNG 7.12.0 under Surefire 3.5.6: {@code IExecutionListener.onExecutionStart} and
 * {@code onExecutionFinish} each fire exactly once per JVM, and exactly one listener
 * instance serves the whole run -- there is no per-rerun re-construction to guard against
 * here the way there is on other platforms.
 *
 * <p>The reason the statics stay is {@code forkCount > 1}. Also measured on 7.12.0:
 * Surefire's {@code rerunFailingTestsCount} does not apply to the TestNG provider at all
 * -- a pom configured with it left a hard-failing test running exactly once, with no
 * second attempt. The only source of retries in this reporter is {@code IRetryAnalyzer},
 * running in-process in the same JVM as the original attempt. So there is no session or
 * plan boundary to survive; what statics buy instead is one accumulator per JVM, so that
 * with {@code forkCount > 1} each fork keeps its own state and writes its own file. That
 * is the directory-merge model {@code qf collect} already uses for pytest-xdist and
 * Vitest shards, not a workaround for anything Surefire does across re-runs.
 */
final class Run {

    private static final Accumulator ACCUMULATOR = new Accumulator();
    private static final AtomicBoolean HOOKED = new AtomicBoolean(false);

    private Run() {}

    static Accumulator accumulator() {
        return ACCUMULATOR;
    }

    /** Installs the shutdown hook exactly once, however many listeners are constructed. */
    static void ensureHook() {
        if (HOOKED.compareAndSet(false, true)) {
            Runtime.getRuntime().addShutdownHook(new Thread(Run::write, "qualflare-write"));
        }
    }

    /**
     * Rewrites the report with everything accumulated so far.
     *
     * <p>Repeatable on purpose. It runs from {@code onExecutionFinish} -- the one call
     * per JVM described above -- and again from the shutdown hook, as a backstop for a
     * JVM that never reaches a clean {@code onExecutionFinish}. Every call rewrites the
     * same file (see {@code ReportWriter}'s per-JVM filename), so an earlier write is just
     * an earlier snapshot and the last one is complete.
     *
     * <p>Synchronized because {@code onExecutionFinish} on the main thread can race the
     * shutdown hook on its own thread, and two writers on one file would truncate the
     * JSON.
     */
    static synchronized void write() {
        // The one place the switch can be honoured for every write path at once: both
        // onExecutionFinish and the shutdown hook come through here. Checked at write time
        // rather than at listener construction so a suite that sets the property in a
        // @BeforeSuite still gets the behaviour it asked for.
        //
        // Accumulation itself is deliberately NOT disabled. It costs a map insert per test,
        // it cannot fail a build, and short-circuiting the listener callbacks instead would
        // mean two code paths to keep honest for no measurable gain.
        if (!Config.enabled()) {
            return;
        }
        if (ACCUMULATOR.isEmpty()) {
            return;
        }
        try {
            ReportWriter.write(ACCUMULATOR.cases());
        } catch (Exception e) {
            // A reporter must never be the reason a build fails.
            System.err.println("[qualflare-testng] could not write the report: " + e);
        }
    }

    /** Test-only: lets a test start from a clean JVM-scoped state. */
    static void resetForTest() {
        ACCUMULATOR.clear();
    }
}
