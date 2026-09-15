package com.qualflare.testng;

import org.testng.ITestResult;
import org.testng.annotations.DataProvider;
import org.testng.annotations.Test;
import static org.testng.Assert.*;

/**
 * Identity is the thing that decides whether a retried test is ONE case with three
 * attempts or three separate cases -- and whether two DataProvider rows collapse into one.
 * Measured: invocationCount increments for BOTH, so it cannot be the discriminator.
 */
public class TestKeyTest {

    @Test
    public void includesClassAndMethod() {
        ITestResult r = Fakes.result("com.example.MyTest", "passes", new Object[] {});
        assertEquals(TestKey.of(r), "com.example.MyTest#passes()");
    }

    @Test
    public void parametersSeparateDataProviderRows() {
        ITestResult a = Fakes.result("com.example.MyTest", "rows", new Object[] {"alpha", 1});
        ITestResult b = Fakes.result("com.example.MyTest", "rows", new Object[] {"beta", 2});
        assertNotEquals(TestKey.of(a), TestKey.of(b),
                "different DataProvider rows must be different cases");
    }

    @Test
    public void retriesOfTheSameTestShareOneKey() {
        // Same class, same method, same (empty) parameters -- only invocationCount differs,
        // and invocationCount is deliberately NOT part of the key.
        ITestResult first = Fakes.result("com.example.MyTest", "flaky", new Object[] {});
        ITestResult third = Fakes.result("com.example.MyTest", "flaky", new Object[] {});
        assertEquals(TestKey.of(first), TestKey.of(third),
                "retries are attempts of ONE case, not separate cases");
    }

    @Test
    public void survivesNullParameters() {
        ITestResult r = Fakes.result("com.example.MyTest", "nulls", new Object[] {null, "x"});
        assertEquals(TestKey.of(r), "com.example.MyTest#nulls(null,x)");
    }

    @Test
    public void survivesAParameterWhoseToStringThrows() {
        // A reporter must never be the reason a build fails -- including because someone's
        // domain object has a broken toString().
        Object hostile = new Object() {
            @Override public String toString() { throw new IllegalStateException("boom"); }
        };
        ITestResult r = Fakes.result("com.example.MyTest", "hostile", new Object[] {hostile});
        String key = TestKey.of(r);
        assertTrue(key.startsWith("com.example.MyTest#hostile("), "got " + key);
    }

    @Test
    public void displayNameIsTheMethodWithItsParameters() {
        ITestResult r = Fakes.result("com.example.MyTest", "rows", new Object[] {"alpha", 1});
        assertEquals(TestKey.displayName(r), "rows(alpha,1)");
    }
}
