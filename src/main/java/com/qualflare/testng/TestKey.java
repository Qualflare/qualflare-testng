package com.qualflare.testng;

import org.testng.ITestResult;

/**
 * The identity of a test case, and the ONE place it is computed.
 *
 * <p>Both {@link QualflareListener} and {@link Qualflare} need this. If they computed it
 * separately and ever disagreed, metadata would be filed under a key with no case attached
 * and would vanish with no error -- so there is exactly one implementation.
 *
 * <p><b>invocationCount is deliberately absent.</b> Measured on TestNG 7.12.0: it
 * increments both for {@code IRetryAnalyzer} retries (same parameters) and for
 * DataProvider rows (different parameters). Keying on it would split a retried test into
 * separate cases; ignoring parameters would collapse distinct rows into one. Parameters
 * separate cases; invocationCount only orders attempts within one.
 */
final class TestKey {

    private TestKey() {}

    /** Stable across retries, distinct across DataProvider rows. */
    static String of(ITestResult result) {
        return result.getTestClass().getName() + "#" + displayName(result);
    }

    /** The method with its parameters, e.g. {@code rows(alpha,1)}. */
    static String displayName(ITestResult result) {
        return result.getMethod().getMethodName() + "(" + params(result) + ")";
    }

    private static String params(ITestResult result) {
        Object[] ps = result.getParameters();
        if (ps == null || ps.length == 0) {
            return "";
        }
        StringBuilder b = new StringBuilder();
        for (int i = 0; i < ps.length; i++) {
            if (i > 0) {
                b.append(',');
            }
            b.append(str(ps[i]));
        }
        return b.toString();
    }

    /**
     * A parameter is somebody else's object and its toString() is somebody else's code.
     * A reporter must never be the reason a build fails, so a throwing toString() degrades
     * to the type name rather than propagating.
     */
    private static String str(Object o) {
        if (o == null) {
            return "null";
        }
        try {
            return String.valueOf(o);
        } catch (RuntimeException e) {
            return "<" + o.getClass().getSimpleName() + ">";
        }
    }
}
