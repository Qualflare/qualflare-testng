package com.qualflare.testng;

import org.testng.ITestResult;

/** Maps a TestNG result onto the wire's status vocabulary. */
final class Status {

    static final String PASSED = "passed";
    static final String FAILED = "failed";
    static final String SKIPPED = "skipped";
    static final String ERROR = "error";
    static final String TIMEOUT = "timeout";
    static final String ABORTED = "aborted";

    /** Deep enough for any real wrapping, short enough that a cycle cannot hang a build. */
    private static final int MAX_CAUSE_DEPTH = 32;

    private Status() {}

    /**
     * <b>The rule that matters:</b> a SKIP with {@code wasRetried() == true} is a FAILED
     * attempt, not a skip.
     *
     * <p>Measured on TestNG 7.12.0: when {@code IRetryAnalyzer.retry()} returns true,
     * TestNG marks the failed attempt SKIP (status 3) so it will not count as a failure,
     * while preserving the throwable and setting {@code wasRetried()}. A listener that
     * trusts the callback name reports a test that failed twice then passed as two SKIPS
     * and a pass -- losing the failures entirely and calling a flaky test clean.
     *
     * <p>{@code wasRetried() == false} on a SKIP is a genuine skip: a dependency failure,
     * or a test guarded by a configuration method that threw.
     */
    static String of(ITestResult result) {
        Throwable t = result.getThrowable();
        switch (result.getStatus()) {
            case ITestResult.SUCCESS:
                return PASSED;
            case ITestResult.SKIP:
                return result.wasRetried() ? ofThrowable(t) : SKIPPED;
            case ITestResult.SUCCESS_PERCENTAGE_FAILURE:
            case ITestResult.FAILURE:
            default:
                return ofThrowable(t);
        }
    }

    /**
     * An assertion failure is a "failed"; anything else thrown is an "error".
     *
     * <p>The distinction is the one triage actually uses: a hundred errors usually means
     * one broken fixture, a hundred failures means a hundred broken expectations.
     *
     * <p>Classified by TYPE, never by class name. qualflare-junit5 0.1.0 matched a list of
     * exact names and got this wrong for JUnit 4: {@code org.junit.ComparisonFailure}
     * extends {@code AssertionError} but was on no list, so every Vintage assertion failure
     * shipped as an error. TestNG's own assertions, AssertJ, Truth and Hamcrest are all
     * {@code instanceof AssertionError}.
     */
    static String ofThrowable(Throwable t) {
        if (t == null) {
            return FAILED;
        }
        // DEPTH-BOUNDED rather than cycle-detecting. Java forbids DIRECT self-causation
        // (initCause throws), so the obvious `getCause() == c` guard defends against the
        // one cycle that cannot happen while missing the one that can: two throwables each
        // holding the other is legal and would spin here forever.
        int depth = 0;
        for (Throwable c = t; c != null && depth < MAX_CAUSE_DEPTH; c = c.getCause(), depth++) {
            if (c instanceof AssertionError) {
                return FAILED;
            }
        }
        return ERROR;
    }
}
