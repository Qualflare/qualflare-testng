package com.qualflare.testng;

import org.testng.ITestResult;
import org.testng.annotations.BeforeMethod;
import org.testng.annotations.Test;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import static org.testng.Assert.*;

/**
 * Drives the listener with synthetic results, which is the only way to reproduce the
 * retry sequence deterministically. The integration fixture (Task 8) proves the same
 * behaviour against a real TestNG run.
 */
public class QualflareListenerTest {

    private QualflareListener listener;

    @BeforeMethod
    public void reset() {
        Run.resetForTest();
        listener = new QualflareListener();
    }

    private static List<String> statusesOf(CaseRecord rec) {
        List<String> out = new ArrayList<>();
        for (Attempt a : rec.attempts) {
            out.add(a.status);
        }
        return out;
    }

    private CaseRecord only() {
        assertEquals(Run.accumulator().cases().size(), 1,
                "expected exactly one case, got " + Run.accumulator().cases().size());
        return Run.accumulator().cases().iterator().next();
    }

    @Test
    public void aPassingTestIsOneCaseWithOneAttempt() {
        ITestResult r = Fakes.result("C", "passes", new Object[] {}, ITestResult.SUCCESS, null, false);
        listener.onTestStart(r);
        listener.onTestSuccess(r);

        CaseRecord rec = only();
        assertEquals(statusesOf(rec), java.util.Collections.singletonList(Status.PASSED));
        assertFalse(rec.isFlaky(), "a test that passed first time is not flaky");
    }

    /**
     * THE case this reporter exists for. TestNG delivers the two failed attempts as
     * onTestSkipped with wasRetried()==true.
     */
    @Test
    public void aRetriedTestIsOneCaseWithEveryAttemptRecorded() {
        ITestResult a1 = Fakes.result("C", "flaky", new Object[] {}, ITestResult.SKIP,
                new AssertionError("attempt 1"), true);
        ITestResult a2 = Fakes.result("C", "flaky", new Object[] {}, ITestResult.SKIP,
                new AssertionError("attempt 2"), true);
        ITestResult a3 = Fakes.result("C", "flaky", new Object[] {}, ITestResult.SUCCESS, null, false);

        listener.onTestStart(a1);
        listener.onTestSkipped(a1);
        listener.onTestStart(a2);
        listener.onTestSkipped(a2);
        listener.onTestStart(a3);
        listener.onTestSuccess(a3);

        CaseRecord rec = only();
        assertEquals(statusesOf(rec),
                java.util.Arrays.asList(Status.FAILED, Status.FAILED, Status.PASSED),
                "the failed attempts must be recorded as failures, not skips");
        assertTrue(rec.isFlaky(), "failed then passed is flaky");
        assertEquals(rec.retryCount(), 2);
    }

    @Test
    public void aTestThatNeverRecoversIsFailedAndNotFlaky() {
        ITestResult a1 = Fakes.result("C", "hard", new Object[] {}, ITestResult.SKIP,
                new AssertionError("1"), true);
        ITestResult a2 = Fakes.result("C", "hard", new Object[] {}, ITestResult.FAILURE,
                new AssertionError("2"), false);
        listener.onTestStart(a1);
        listener.onTestSkipped(a1);
        listener.onTestStart(a2);
        listener.onTestFailure(a2);

        CaseRecord rec = only();
        assertEquals(statusesOf(rec),
                java.util.Arrays.asList(Status.FAILED, Status.FAILED),
                "the retried attempt must be recorded as a failure, not a skip");
        assertEquals(rec.status(), Status.FAILED);
        assertFalse(rec.isFlaky(), "calling a hard failure flaky hides it behind a softer word");
    }

    @Test
    public void dataProviderRowsStayDistinctCases() {
        ITestResult a = Fakes.result("C", "rows", new Object[] {"alpha", 1}, ITestResult.SUCCESS, null, false);
        ITestResult b = Fakes.result("C", "rows", new Object[] {"beta", 2}, ITestResult.SUCCESS, null, false);
        listener.onTestStart(a);
        listener.onTestSuccess(a);
        listener.onTestStart(b);
        listener.onTestSuccess(b);

        assertEquals(Run.accumulator().cases().size(), 2,
                "two DataProvider rows are two cases");
    }

    @Test
    public void aTimeoutIsItsOwnStatus() {
        ITestResult r = Fakes.result("C", "slow", new Object[] {}, ITestResult.FAILURE,
                new RuntimeException("timed out"), false);
        listener.onTestStart(r);
        listener.onTestFailedWithTimeout(r);

        assertEquals(only().status(), Status.TIMEOUT,
                "the difference between broken and hung is the point of having the status");
    }

    @Test
    public void aGenuineSkipIsRecordedAsSkipped() {
        ITestResult r = Fakes.result("C", "dep", new Object[] {}, ITestResult.SKIP,
                new Throwable("depends on a failed method"), false);
        listener.onTestStart(r);
        listener.onTestSkipped(r);

        assertEquals(only().status(), Status.SKIPPED);
    }

    @Test
    public void anEmptyRunWritesNoReportFile() throws Exception {
        // The original assertion here was Run.accumulator().isEmpty(), which is trivially
        // true: @BeforeMethod already emptied it and Run.write() only reads. That version
        // passed even with onExecutionFinish() deleted. This asserts the observable thing.
        Path dir = Files.createTempDirectory("qf-empty-run");
        String prev = System.getProperty("qualflare.outputDir");
        System.setProperty("qualflare.outputDir", dir.toString());
        try {
            listener.onExecutionFinish();
            try (java.util.stream.Stream<Path> s = Files.list(dir)) {
                assertEquals(s.count(), 0L,
                        "a run with no tests must not produce a report file");
            }
        } finally {
            if (prev == null) {
                System.clearProperty("qualflare.outputDir");
            } else {
                System.setProperty("qualflare.outputDir", prev);
            }
        }
    }

    /**
     * {@code qualflare.enabled} was documented in docs/CONFIGURATION.md and had zero
     * callers -- {@code QUALFLARE_ENABLED=false} still wrote a report. The positive control
     * for this is {@code aRunWithOneCaseDoesWriteAReportFile} below: without it, "no file
     * appeared" would also be satisfied by writing being broken outright.
     */
    @Test
    public void aDisabledRunWritesNoReportFile() throws Exception {
        Path dir = Files.createTempDirectory("qf-disabled");
        String prevDir = System.getProperty("qualflare.outputDir");
        String prevEnabled = System.getProperty("qualflare.enabled");
        System.setProperty("qualflare.outputDir", dir.toString());
        System.setProperty("qualflare.enabled", "false");
        try {
            ITestResult r = Fakes.result("C", "passes", new Object[] {},
                    ITestResult.SUCCESS, null, false);
            listener.onTestStart(r);
            listener.onTestSuccess(r);
            assertFalse(Run.accumulator().isEmpty(),
                    "the case must have accumulated: this test is about WRITING, not"
                            + " about the listener going deaf");

            listener.onExecutionFinish();

            try (java.util.stream.Stream<Path> s = Files.list(dir)) {
                assertEquals(s.count(), 0L,
                        "qualflare.enabled=false must write no report file");
            }
        } finally {
            restore("qualflare.outputDir", prevDir);
            restore("qualflare.enabled", prevEnabled);
            Temp.deleteRecursively(dir);
        }
    }

    private static void restore(String key, String previous) {
        if (previous == null) {
            System.clearProperty(key);
        } else {
            System.setProperty(key, previous);
        }
    }

    @Test
    public void aRunWithOneCaseDoesWriteAReportFile() throws Exception {
        // The positive control for the test above. Without it, "no file appeared" could be
        // true because writing is broken rather than because the run was empty.
        Path dir = Files.createTempDirectory("qf-one-case");
        String prev = System.getProperty("qualflare.outputDir");
        System.setProperty("qualflare.outputDir", dir.toString());
        try {
            ITestResult r = Fakes.result("C", "passes", new Object[] {},
                    ITestResult.SUCCESS, null, false);
            listener.onTestStart(r);
            listener.onTestSuccess(r);
            listener.onExecutionFinish();
            try (java.util.stream.Stream<Path> s = Files.list(dir)) {
                assertEquals(s.count(), 1L, "one case must produce exactly one report file");
            }
        } finally {
            if (prev == null) {
                System.clearProperty("qualflare.outputDir");
            } else {
                System.setProperty("qualflare.outputDir", prev);
            }
        }
    }
}
