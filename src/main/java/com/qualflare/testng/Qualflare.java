package com.qualflare.testng;

import org.testng.ITestResult;
import org.testng.Reporter;

import java.nio.file.Path;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * The author-facing API. Optional: without it every result, status, duration, error and
 * retry is still reported; only the metadata needs a test to attach to.
 *
 * <p>Nothing here can fail a test. No method throws, none returns an error, and every call
 * is inert when no test is in scope.
 *
 * <p>Unlike qualflare-junit5 this needs no extension and no registration, because
 * {@code Reporter.getCurrentTestResult()} is a thread-local TestNG maintains itself.
 */
public final class Qualflare {

    public static final String ISSUE = "issue";
    public static final String TMS = "tms";
    public static final String CUSTOM = "custom";

    public static final String HIGH = "high";
    public static final String MEDIUM = "medium";
    public static final String LOW = "low";

    private static final AtomicBoolean WARNED = new AtomicBoolean(false);

    private Qualflare() {}

    public static void label(String name, String value) {
        emit(Keys.LABEL, kv(name, value));
    }

    public static void tag(String... tags) {
        if (tags == null) {
            return;
        }
        for (String t : tags) {
            emit(Keys.TAG, str(t));
        }
    }

    public static void link(String url) {
        link(url, ISSUE, url);
    }

    public static void link(String url, String type, String name) {
        emit(Keys.LINK, str(url) + Keys.SEP + str(type) + Keys.SEP + str(name));
    }

    public static void priority(String priority) {
        emit(Keys.PRIORITY, str(priority));
    }

    public static void description(String text) {
        emit(Keys.DESCRIPTION, str(text));
    }

    public static void parameter(String name, String value) {
        emit(Keys.PARAMETER, kv(name, value));
    }

    /**
     * Takes no value at all. {@code masked} is only a display hint the server does not act
     * on, so withholding the value here is the only thing that actually keeps a secret out
     * of the report: a signature that cannot accept one cannot leak one.
     */
    public static void maskedParameter(String name) {
        emit(Keys.MASKED_PARAMETER, str(name));
    }

    /**
     * Attaches a file on disk to the running test.
     *
     * <p>Explicit rather than implicit, because TestNG has no equivalent of the JUnit
     * Platform's {@code fileEntryPublished}: there is no framework callback that hands a
     * reporter a published file, so the only way a file can reach the report is a call
     * here.
     *
     * <p>Read immediately, not at the end of the run. A screenshot written to a temp
     * directory the test then deletes would otherwise be gone by the time the report is
     * written. PNG, JPEG and GIF are copied into the report directory and referenced by
     * name; everything else is base64-inlined against a per-run budget.
     *
     * <p>An unreadable or missing file is dropped silently, and nothing here throws. A
     * broken path must never be the reason somebody's build goes red -- the test already
     * passed or failed on its own merits.
     *
     * @param name     the label shown on the case; null becomes an empty name
     * @param file     the file to attach; missing, unreadable and null are all dropped
     * @param mimeType the media type, e.g. {@code "image/png"}; drives inline-vs-copy
     */
    public static void attachment(String name, Path file, String mimeType) {
        ITestResult r = current();
        if (r == null) {
            warnDropped();
            return;
        }
        try {
            Attachments.Attachment a = Attachments.of(name, file, mimeType);
            if (a == null) {
                return; // unreadable: already dropped by Attachments, nothing to report
            }
            Run.accumulator().attachment(TestKey.of(r), a);
        } catch (RuntimeException ignored) {
            // Fire and forget. An attachment problem must never fail somebody's run.
        }
    }

    public static void step(String name, Runnable body) {
        if (body == null) {
            return;
        }
        if (current() == null) {
            body.run(); // inert, but the test's own work still has to happen
            return;
        }
        long started = System.nanoTime();
        emit(Keys.STEP_START, str(name));
        try {
            body.run();
        } catch (Throwable t) {
            closeStep(Status.FAILED, System.nanoTime() - started, messageOf(t));
            throw t; // never swallow: turning a failing test green is the worst thing a reporter can do
        }
        closeStep(Status.PASSED, System.nanoTime() - started, "");
    }

    private static void closeStep(String status, long nanos, String error) {
        emit(Keys.STEP_STOP, status + Keys.SEP + nanos + Keys.SEP + str(error));
    }

    // ---- plumbing ---------------------------------------------------------------

    private static ITestResult current() {
        try {
            return Reporter.getCurrentTestResult();
        } catch (RuntimeException e) {
            return null;
        }
    }

    /**
     * Dropped with a warning rather than guessed at. Attaching this to whichever test runs
     * next is silent wrong data, which is worse than no data.
     *
     * <p>The thread-local is per-thread by design, so a call made from a thread the test
     * spawned finds nothing. That is the correct answer: the reporter cannot know which
     * test that thread belongs to.
     */
    private static void emit(String key, String value) {
        ITestResult r = current();
        if (r == null) {
            warnDropped();
            return;
        }
        try {
            Run.accumulator().entry(TestKey.of(r), key, value);
        } catch (RuntimeException ignored) {
            // Fire and forget. A metadata problem must never fail somebody's run.
        }
    }

    /** Once per JVM, not once per call, so a loop in a helper cannot drown the log. */
    private static void warnDropped() {
        if (WARNED.compareAndSet(false, true)) {
            System.err.println("[qualflare-testng] metadata call outside a running test was"
                    + " dropped. Calls must be made on the test's own thread.");
        }
    }

    private static String kv(String name, String value) {
        return str(name) + Keys.SEP + str(value);
    }

    private static String str(Object o) {
        return o == null ? "" : String.valueOf(o);
    }

    private static String messageOf(Throwable t) {
        String m = t.getMessage();
        return m == null ? t.getClass().getSimpleName() : m;
    }
}
