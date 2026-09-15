package com.qualflare.testng;

import org.testng.annotations.Test;
import java.nio.file.Files;
import java.nio.file.Path;
import static org.testng.Assert.*;

/**
 * Proves the copied core compiles and round-trips. It is NOT a re-test of the core's
 * behaviour -- that is covered in qualflare-junit5 and the files are byte-identical apart
 * from the package. This asserts only that the copy is wired up and usable here.
 */
public class CoreSmokeTest {

    @Test
    public void accumulatesOneCaseAndRendersIt() {
        Accumulator acc = new Accumulator();
        acc.started("k1", 1_000L);
        acc.finished("k1", "MySuite", "com.example.MyTest", "passes()",
                "com.example.MyTest.passes", Status.PASSED, 5_000L, "", "");

        assertEquals(acc.cases().size(), 1, "one finished test is one case");
        String json = ReportWriter.render(acc.cases());
        assertTrue(json.contains("passes()"), "rendered report must name the case");
        assertTrue(json.contains("\"passed\""), "rendered report must carry the status");
    }

    @Test
    public void writesAFileNamedForThisReporter() throws Exception {
        Accumulator acc = new Accumulator();
        acc.started("k2", 0L);
        acc.finished("k2", "S", "C", "d", "l", Status.PASSED, 1L, "", "");
        Path p = ReportWriter.write(acc.cases());
        try {
            assertTrue(Files.exists(p), "the writer must produce a file");
            assertTrue(p.getFileName().toString().startsWith("qualflare-testng-"),
                    "file must be named for THIS reporter, got " + p.getFileName());
        } finally {
            Files.deleteIfExists(p);
        }
    }
}
