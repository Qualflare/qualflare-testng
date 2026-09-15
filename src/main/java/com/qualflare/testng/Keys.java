package com.qualflare.testng;

/**
 * The report-entry vocabulary shared by {@link Qualflare} and {@link QualflareListener}.
 *
 * <p>{@code publishReportEntry} carries a {@code Map<String,String>}, so a call has exactly
 * one string to work with. Multi-field messages are packed with {@link #SEP}, the ASCII
 * unit separator: it cannot appear in a Java identifier, a URL or a sane label, and unlike
 * {@code |} or {@code =} it needs no escaping rules that users would have to know about.
 * The listener splits with a limit so a value containing a separator cannot shift the
 * remaining fields.
 *
 * <p>Keys are namespaced under {@code qf.} so they never collide with a project's own
 * report entries, which are common in Java suites and must pass through untouched.
 */
final class Keys {
    static final String SEP = "\u001f";

    static final String LABEL = "qf.label";
    static final String TAG = "qf.tag";
    static final String LINK = "qf.link";
    static final String PRIORITY = "qf.priority";
    static final String DESCRIPTION = "qf.description";
    static final String PARAMETER = "qf.parameter";
    static final String MASKED_PARAMETER = "qf.maskedParameter";
    static final String STEP_START = "qf.step+";
    static final String STEP_STOP = "qf.step-";

    private Keys() {}

    static boolean isOurs(String key) {
        return key != null && key.startsWith("qf.");
    }
}
