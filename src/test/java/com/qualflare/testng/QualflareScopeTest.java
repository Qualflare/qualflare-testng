package com.qualflare.testng;

import org.testng.ITestResult;
import org.testng.Reporter;
import org.testng.annotations.BeforeMethod;
import org.testng.annotations.Test;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.atomic.AtomicInteger;

import static org.testng.Assert.*;

/**
 * What "in scope" means, which is narrower than "the current result is non-null".
 *
 * <p>Measured: {@code Reporter.getCurrentTestResult()} is NON-NULL inside
 * {@code @BeforeClass} and {@code @BeforeMethod} and returns the configuration method's own
 * result. A null check alone therefore passes, and the entries are filed under a key like
 * {@code MyTest#setUp()} that never becomes a case -- only *failing* configuration methods
 * are reported, and under a different key again. The result was silent data loss plus a
 * leak in {@code Accumulator.pendingEntries} that grew on every {@code @BeforeMethod}
 * invocation for the life of the JVM.
 *
 * <p>Setting shared labels in a base-class {@code @BeforeMethod} is mainstream TestNG, so
 * the author has to be told rather than left to discover the gap from an empty report.
 */
public class QualflareScopeTest {

    private static final AtomicInteger stepBodyRuns = new AtomicInteger();

    /** Exactly the mainstream pattern: shared metadata set once for every test. */
    @BeforeMethod
    public void setUpSharedMetadata() {
        Run.resetForTest();
        Qualflare.label("suite", "shared");
        Qualflare.tag("shared");
        Qualflare.priority(Qualflare.LOW);
        Qualflare.step("shared setup", stepBodyRuns::incrementAndGet);
    }

    @Test
    public void metadataFromABeforeMethodIsDroppedRatherThanFiledUnderAKeyWithNoCase() {
        // Nothing buffered ANYWHERE -- not under this test's key, and not under the
        // configuration method's key either, which is where it used to leak.
        assertEquals(Run.accumulator().pendingEntryKeyCount(), 0,
                "metadata emitted from @BeforeMethod must be dropped, not filed under a key"
                        + " that never becomes a case");
    }

    @Test
    public void metadataFromABeforeMethodDoesNotLandOnTheTestThatFollows() {
        String mine = "com.qualflare.testng.QualflareScopeTest"
                + "#metadataFromABeforeMethodDoesNotLandOnTheTestThatFollows()";
        assertFalse(Run.accumulator().hasEntriesFor(mine),
                "attaching a configuration method's metadata to whichever test runs next is"
                        + " silent wrong data, which is worse than absent data");
    }

    /** Dropping the metadata must not drop the author's own work. */
    @Test
    public void aStepInABeforeMethodStillRunsItsBody() {
        assertTrue(stepBodyRuns.get() > 0,
                "a step whose reporting is inert must still run its body");
    }

    /**
     * The same rule without depending on TestNG's configuration lifecycle: a configuration
     * result installed as the current result is rejected on its own merits.
     */
    @Test
    public void aConfigurationResultIsRejectedEvenWhenItIsTheCurrentResult() throws Exception {
        ITestResult saved = Reporter.getCurrentTestResult();
        Path file = Files.createTempFile("qf-scope", ".txt");
        Files.write(file, "body".getBytes(StandardCharsets.UTF_8));
        try {
            Reporter.setCurrentTestResult(Fakes.configResult("C", "setUp"));
            Run.resetForTest();
            Attachments.resetBudget();

            Qualflare.label("feature", "checkout");
            Qualflare.attachment("notes", file, "text/plain");

            assertEquals(Run.accumulator().pendingEntryKeyCount(), 0,
                    "a configuration result must file no metadata");
            assertEquals(Run.accumulator().pendingAttachmentsFor("C#setUp()").size(), 0,
                    "and no attachment either");
        } finally {
            Reporter.setCurrentTestResult(saved);
            Temp.deleteRecursively(file);
        }
    }
}
