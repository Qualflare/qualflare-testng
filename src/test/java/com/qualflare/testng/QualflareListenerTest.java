package com.qualflare.testng;

import org.testng.ITestResult;
import org.testng.annotations.BeforeMethod;
import org.testng.annotations.Test;
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
    public void anEmptyRunWritesNothing() {
        listener.onExecutionFinish();
        assertTrue(Run.accumulator().isEmpty(),
                "a run with no tests must not produce a report file");
    }
}
