package com.qualflare.testng;

/** Maps a test result onto the wire's status vocabulary. */
final class Status {

    static final String PASSED = "passed";
    static final String FAILED = "failed";
    static final String SKIPPED = "skipped";
    static final String ERROR = "error";
    static final String TIMEOUT = "timeout";
    static final String ABORTED = "aborted";

    private Status() {}
}
