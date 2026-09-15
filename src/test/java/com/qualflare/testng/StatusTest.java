package com.qualflare.testng;

import org.testng.ITestResult;
import org.testng.annotations.Test;
import java.io.IOException;
import static org.testng.Assert.*;

public class StatusTest {

    @Test
    public void successIsPassed() {
        ITestResult r = Fakes.result("C", "m", new Object[] {}, ITestResult.SUCCESS, null, false);
        assertEquals(Status.of(r), Status.PASSED);
    }

    @Test
    public void anAssertionFailureIsFailed() {
        ITestResult r = Fakes.result("C", "m", new Object[] {}, ITestResult.FAILURE,
                new AssertionError("expected 1 but found 2"), false);
        assertEquals(Status.of(r), Status.FAILED);
    }

    /**
     * Classified by TYPE, not by a list of class names. qualflare-junit5 0.1.0 matched
     * exact names and mislabelled every org.junit.ComparisonFailure as an error -- it
     * extends AssertionError but was on no list. TestNG's own assertions, AssertJ, Truth
     * and Hamcrest all extend AssertionError too.
     */
    @Test
    public void anAssertionSubclassIsStillFailed() {
        class CustomAssertion extends AssertionError {
            CustomAssertion() { super("custom"); }
        }
        ITestResult r = Fakes.result("C", "m", new Object[] {}, ITestResult.FAILURE,
                new CustomAssertion(), false);
        assertEquals(Status.of(r), Status.FAILED, "any AssertionError subclass is a failure");
    }

    @Test
    public void anyOtherThrowableIsAnError() {
        ITestResult r = Fakes.result("C", "m", new Object[] {}, ITestResult.FAILURE,
                new IllegalStateException("fixture broken"), false);
        assertEquals(Status.of(r), Status.ERROR,
                "a broken fixture is an error, not a failed expectation");
    }

    @Test
    public void aWrappedAssertionIsStillFailed() {
        ITestResult r = Fakes.result("C", "m", new Object[] {}, ITestResult.FAILURE,
                new RuntimeException("wrapper", new AssertionError("inner")), false);
        assertEquals(Status.of(r), Status.FAILED, "the cause chain is walked");
    }

    /**
     * Two throwables each holding the other as a cause is legal in Java (only DIRECT
     * self-causation is forbidden). An unbounded cause walk would spin forever, hanging
     * the reporter at the exact moment a build is already failing.
     */
    @Test(timeOut = 5000)
    public void aCauseCycleTerminates() {
        Exception a = new Exception("a");
        Exception b = new Exception("b", a);
        a.initCause(b);
        ITestResult r = Fakes.result("C", "m", new Object[] {}, ITestResult.FAILURE, a, false);
        assertEquals(Status.of(r), Status.ERROR);
    }

    @Test
    public void aGenuineSkipIsSkipped() {
        ITestResult r = Fakes.result("C", "m", new Object[] {}, ITestResult.SKIP,
                new Throwable("depends on a failed method"), false);
        assertEquals(Status.of(r), Status.SKIPPED);
    }

    /**
     * THE load-bearing rule. When IRetryAnalyzer retries, TestNG marks the FAILED attempt
     * SKIP so it does not count as a failure, preserving the throwable and setting
     * wasRetried(). Trusting the callback name reports a flaky test as clean.
     */
    @Test
    public void aRetriedSkipIsAFailure() {
        ITestResult r = Fakes.result("C", "m", new Object[] {}, ITestResult.SKIP,
                new AssertionError("attempt 1"), true);
        assertEquals(Status.of(r), Status.FAILED,
                "a skip with wasRetried()==true is a failed attempt, not a skip");
    }

    @Test
    public void aRetriedSkipCarryingANonAssertionIsAnError() {
        ITestResult r = Fakes.result("C", "m", new Object[] {}, ITestResult.SKIP,
                new IOException("io"), true);
        assertEquals(Status.of(r), Status.ERROR);
    }

    @Test
    public void successPercentageFailureIsFailed() {
        ITestResult r = Fakes.result("C", "m", new Object[] {},
                ITestResult.SUCCESS_PERCENTAGE_FAILURE, new AssertionError("x"), false);
        assertEquals(Status.of(r), Status.FAILED);
    }

    @Test
    public void aFailureWithNoThrowableIsStillFailed() {
        ITestResult r = Fakes.result("C", "m", new Object[] {}, ITestResult.FAILURE, null, false);
        assertEquals(Status.of(r), Status.FAILED);
    }
}
