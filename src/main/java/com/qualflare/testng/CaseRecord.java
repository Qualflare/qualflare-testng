package com.qualflare.testng;

import java.util.ArrayList;
import java.util.List;

/**
 * One test, and every attempt at it.
 *
 * <p>Keyed on {@code uniqueId}, which despite the field name is not any identifier TestNG
 * hands out -- it is {@link TestKey}'s {@code className#methodName(params)} string, the
 * one place that identity gets computed (see {@link TestKey} for why). That key is what
 * makes retry accumulation work: an {@code IRetryAnalyzer} retry re-runs the same method
 * with the same parameters in the same JVM, so it produces the same key, and successive
 * attempts land in this record's {@link #attempts} list instead of creating separate
 * cases. {@code invocationCount} is deliberately excluded from the key -- it increments
 * for both retries and DataProvider rows, so keying on it would split a retried test into
 * separate cases instead of accumulating attempts on one.
 *
 * <p>Not thread-safe on its own; {@link Accumulator} owns the locking.
 */
final class CaseRecord {
    final String uniqueId;
    final String suiteName;
    final String className;
    final String displayName;
    final String legacyName;
    final List<Attempt> attempts = new ArrayList<>();

    /**
     * Author metadata from the LAST attempt only.
     *
     * <p>Same rule the CucumberJS reporter documents: attempts carry their own status,
     * duration and error, but steps, labels, tags and parameters come from the final
     * attempt. Replaying an abandoned attempt's step trace alongside the winning one
     * would show a step tree that never existed in that shape.
     */
    CaseMeta meta = new CaseMeta();

    CaseRecord(String uniqueId, String suiteName, String className, String displayName, String legacyName) {
        this.uniqueId = uniqueId;
        this.suiteName = suiteName;
        this.className = className;
        this.displayName = displayName;
        this.legacyName = legacyName;
    }

    /**
     * The final attempt wins, which is what makes a retry-to-green read as green: a case
     * that failed once and then passed is reported PASSED, with the earlier failure kept
     * in {@link #attempts} for {@link #isFlaky()} to find rather than lost.
     */
    String status() {
        return attempts.isEmpty() ? Status.SKIPPED : attempts.get(attempts.size() - 1).status;
    }

    long durationNanos() {
        return attempts.isEmpty() ? 0L : attempts.get(attempts.size() - 1).durationNanos;
    }

    String message() {
        return attempts.isEmpty() ? "" : attempts.get(attempts.size() - 1).message;
    }

    /**
     * Flaky means it genuinely recovered: at least one failing attempt, and a final pass.
     * A test that failed every attempt is not flaky, it is broken -- calling it flaky
     * would hide a hard failure behind a softer word.
     */
    boolean isFlaky() {
        if (attempts.size() < 2 || !Status.PASSED.equals(status())) {
            return false;
        }
        for (int i = 0; i < attempts.size() - 1; i++) {
            String s = attempts.get(i).status;
            if (Status.FAILED.equals(s) || Status.ERROR.equals(s) || Status.TIMEOUT.equals(s)) {
                return true;
            }
        }
        return false;
    }

    /** 1-based count of retries, i.e. attempts beyond the first. */
    int retryCount() {
        return Math.max(0, attempts.size() - 1);
    }
}
