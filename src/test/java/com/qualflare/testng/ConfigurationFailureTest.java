package com.qualflare.testng;

import org.testng.ITestResult;
import org.testng.annotations.BeforeMethod;
import org.testng.annotations.Test;
import static org.testng.Assert.*;

public class ConfigurationFailureTest {

    private QualflareListener listener;

    @BeforeMethod
    public void reset() {
        Run.resetForTest();
        listener = new QualflareListener();
    }

    private CaseRecord caseNamed(String needle) {
        for (CaseRecord rec : Run.accumulator().cases()) {
            if (rec.displayName.contains(needle)) {
                return rec;
            }
        }
        fail("no case whose display name contains " + needle
                + "; cases were " + Run.accumulator().cases());
        return null;
    }

    @Test
    public void aFailingConfigurationMethodBecomesAnErrorCase() {
        ITestResult cfg = Fakes.result("com.example.BrokenTest", "setUp", new Object[] {},
                ITestResult.FAILURE, new IllegalStateException("beforeClass exploded"), false);
        listener.onConfigurationFailure(cfg);

        CaseRecord rec = caseNamed("setUp");
        assertEquals(rec.status(), Status.ERROR,
                "an all-skipped suite reads as 'nothing ran'; the config failure must be red");
        assertTrue(rec.message().contains("exploded"), "the real cause must survive");
    }

    @Test
    public void theConfigCaseIsMarkedAsConfigurationNotAsATest() {
        ITestResult cfg = Fakes.result("com.example.BrokenTest", "setUp", new Object[] {},
                ITestResult.FAILURE, new IllegalStateException("x"), false);
        listener.onConfigurationFailure(cfg);

        CaseRecord rec = caseNamed("setUp");
        assertTrue(rec.displayName.startsWith("[config] "),
                "unprefixed, 'setUp' reads as a test somebody forgot to delete; got "
                        + rec.displayName);
    }

    @Test
    public void aSuccessfulConfigurationMethodProducesNoCase() {
        ITestResult cfg = Fakes.result("com.example.OkTest", "setUp", new Object[] {},
                ITestResult.SUCCESS, null, false);
        listener.onConfigurationSuccess(cfg);

        assertTrue(Run.accumulator().isEmpty(),
                "setup that worked must not add noise cases to every suite");
    }
}
