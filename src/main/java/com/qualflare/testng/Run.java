package com.qualflare.testng;

import java.util.concurrent.atomic.AtomicBoolean;

/**
 * JVM-scoped run state.
 *
 * <p>Run state lives here, in statics, rather than in listener fields. The reason is that
 * a listener instance does not reliably span a whole run.
 *
 * <p>Maven Surefire re-runs a failed test in a new {@code TestPlan}. Up to Surefire 3.5.3
 * each of those re-runs also got its own launcher session, and a new session means a new
 * {@code Launcher} and a freshly loaded listener, so a listener keeping state in fields
 * started each re-run empty. Measured on 3.5.3 with {@code rerunFailingTestsCount=3}:
 * three sessions and three listener instances in one JVM. That produced four report files
 * holding 7, 4, 4 and 3 cases, and the flaky test showed up as three separate
 * one-attempt cases instead of one case with three attempts.
 *
 * <p>Surefire 3.5.4 fixed the session scoping. From that version one session covers all
 * re-runs, and one listener instance sees all three plans. Field-held state would work
 * there. It still would not work on 3.5.3 and earlier, which is why the statics stay.
 *
 * <p>One accumulator per JVM, then, and one file per JVM, though that file is written
 * several times -- on every session close and again from the shutdown hook -- each write
 * replacing the last with everything accumulated so far. With {@code forkCount > 1} each
 * JVM has its own accumulator and its own file, which is the directory-merge model
 * {@code qf collect} already uses for pytest-xdist and Vitest shards.
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
     * <p>Repeatable on purpose. It runs on every launcher-session close -- one per re-run
     * on Surefire 3.5.3 and earlier, one per JVM from 3.5.4 -- and again from the shutdown
     * hook. Every call rewrites the same file (see {@code ReportWriter}'s per-JVM
     * filename), so earlier writes are just earlier snapshots and the last one is complete.
     *
     * <p>Synchronized because a session close on the main thread can race the shutdown
     * hook on its own thread, and two writers on one file would truncate the JSON.
     */
    static synchronized void write() {
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
