package com.qualflare.testng;

import org.testng.annotations.AfterMethod;
import org.testng.annotations.BeforeMethod;
import org.testng.annotations.Test;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Base64;
import java.util.List;

import static org.testng.Assert.*;

/**
 * Real TestNG tests, so {@code Reporter.getCurrentTestResult()} is populated by the
 * framework exactly as it would be for a user.
 *
 * <p>{@code Qualflare.attachment} exists because TestNG has no equivalent of the JUnit
 * Platform's {@code fileEntryPublished}. Without an explicit call there is no way at all
 * for a file to reach the report, and {@code Attachments} plus its accumulator and writer
 * plumbing would be unreachable code behind a README promise.
 */
public class QualflareAttachmentTest {

    private static final String KEY_PREFIX = "com.qualflare.testng.QualflareAttachmentTest#";

    private Path dir;
    private String previousOutputDir;

    @BeforeMethod
    public void setUp() throws Exception {
        Run.resetForTest();
        Attachments.resetBudget();
        dir = Files.createTempDirectory("qf-attach");
        previousOutputDir = System.getProperty("qualflare.outputDir");
        System.setProperty("qualflare.outputDir", dir.toString());
    }

    @AfterMethod
    public void tearDown() throws Exception {
        if (previousOutputDir == null) {
            System.clearProperty("qualflare.outputDir");
        } else {
            System.setProperty("qualflare.outputDir", previousOutputDir);
        }
        Temp.deleteRecursively(dir);
    }

    private List<Attachments.Attachment> buffered(String method) {
        return Run.accumulator().pendingAttachmentsFor(KEY_PREFIX + method + "()");
    }

    @Test
    public void anAttachmentLandsUnderTheRunningTest() throws Exception {
        Path file = dir.resolve("notes.txt");
        Files.write(file, "hello".getBytes(StandardCharsets.UTF_8));

        Qualflare.attachment("notes", file, "text/plain");

        List<Attachments.Attachment> got = buffered("anAttachmentLandsUnderTheRunningTest");
        assertEquals(got.size(), 1, "the attachment must be filed under the running test");
        Attachments.Attachment a = got.get(0);
        assertEquals(a.name, "notes");
        assertEquals(a.mimeType, "text/plain");
        assertNotNull(a.contentBase64, "a non-image is inlined, because the endpoint has"
                + " nowhere else to put it");
        assertEquals(new String(Base64.getDecoder().decode(a.contentBase64),
                StandardCharsets.UTF_8), "hello");
    }

    /** The other of the two routes: an image is copied, never inlined. */
    @Test
    public void anImageAttachmentIsCopiedIntoTheReportDirectory() throws Exception {
        Path file = dir.resolve("shot.png");
        Files.write(file, new byte[] {(byte) 0x89, 'P', 'N', 'G'});

        Qualflare.attachment("shot", file, "image/png");

        List<Attachments.Attachment> got =
                buffered("anImageAttachmentIsCopiedIntoTheReportDirectory");
        assertEquals(got.size(), 1);
        Attachments.Attachment a = got.get(0);
        assertNull(a.contentBase64, "an image must not compete with the inline budget");
        assertNotNull(a.localImagePath, "an image is referenced by name");
        assertTrue(Files.isReadable(Paths.get(Config.outputDir()).resolve(a.localImagePath)),
                "the referenced copy must actually exist: " + a.localImagePath);
    }

    @Test
    public void aMissingFileIsDroppedRatherThanThrowing() {
        Qualflare.attachment("gone", dir.resolve("does-not-exist.txt"), "text/plain");
        assertEquals(buffered("aMissingFileIsDroppedRatherThanThrowing").size(), 0,
                "an unreadable file must not produce an attachment carrying nothing");
    }

    @Test
    public void aNullPathIsDroppedRatherThanThrowing() {
        Qualflare.attachment("nothing", null, "text/plain");
        assertEquals(buffered("aNullPathIsDroppedRatherThanThrowing").size(), 0);
    }

    /**
     * With no test in scope the call is inert rather than guessed onto whichever test ran
     * last.
     *
     * <p>Measured, and NOT what this project previously documented: TestNG's
     * {@code Reporter.m_currentTestResult} is an {@code InheritableThreadLocal}, so a plain
     * child thread inherits the spawning test's result and metadata from it lands on that
     * test. The genuinely out-of-scope thread is one with no result of its own, which is
     * what clearing the inherited copy here reproduces.
     *
     * <p>Asserted through an uncaught-exception handler because an exception on a spawned
     * thread dies there and does not propagate out of {@code join()}.
     */
    @Test
    public void anAttachmentWithNoTestInScopeIsInert() throws Exception {
        Path file = dir.resolve("off.txt");
        Files.write(file, "x".getBytes(StandardCharsets.UTF_8));

        java.util.concurrent.atomic.AtomicReference<Throwable> caught =
                new java.util.concurrent.atomic.AtomicReference<>();
        Thread t = new Thread(() -> {
            org.testng.Reporter.setCurrentTestResult(null);
            Qualflare.attachment("off", file, "text/plain");
        });
        t.setUncaughtExceptionHandler((thread, e) -> caught.set(e));
        t.start();
        t.join();

        assertNull(caught.get(), "an out-of-scope attachment must be inert, not explosive");
        assertEquals(buffered("anAttachmentWithNoTestInScopeIsInert").size(), 0,
                "it must not be guessed onto the test that happened to spawn the thread");
    }

    /** A child thread DOES inherit the test's result, so this is the attributable case. */
    @Test
    public void anAttachmentFromAThreadTheTestSpawnedLandsOnThatTest() throws Exception {
        Path file = dir.resolve("child.txt");
        Files.write(file, "child".getBytes(StandardCharsets.UTF_8));

        Thread t = new Thread(() -> Qualflare.attachment("child", file, "text/plain"));
        t.start();
        t.join();

        assertEquals(buffered("anAttachmentFromAThreadTheTestSpawnedLandsOnThatTest").size(), 1,
                "TestNG's Reporter thread-local is INHERITABLE, so the child thread's call"
                        + " is attributable to the spawning test");
    }
}
