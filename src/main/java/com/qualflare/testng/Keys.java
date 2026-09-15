package com.qualflare.testng;

/**
 * The metadata vocabulary {@link Qualflare} writes and {@link Replay} reads back.
 *
 * <p>Nothing framework-level is involved. TestNG has no equivalent of JUnit's
 * {@code publishReportEntry}, so an entry here never leaves the JVM: {@code Qualflare}
 * appends {key, value} straight into {@link Accumulator}'s per-attempt buffer, and
 * {@link Replay} folds the buffer into a {@link CaseMeta} when the attempt finishes.
 *
 * <p>A value is a single string, so multi-field messages are packed with {@link #SEP}, the
 * ASCII unit separator: it cannot appear in a Java identifier, a URL or a sane label, and
 * unlike {@code |} or {@code =} it needs no escaping rules that users would have to know
 * about. {@code Replay.split} splits with a field limit, so a value that somehow contains a
 * separator cannot shift the fields after it.
 *
 * <p>Keys stay namespaced under {@code qf.} even though nothing else shares the buffer.
 * It costs four characters, it keeps the entries readable in a debugger, and it matches the
 * eight sibling reporters -- one vocabulary to learn across all of them.
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
}
