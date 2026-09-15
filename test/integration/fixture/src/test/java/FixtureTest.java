import com.qualflare.testng.Qualflare;
import java.nio.charset.StandardCharsets;
import org.testng.annotations.*;
import static org.testng.Assert.*;

public class FixtureTest {

    @Test public void passes() { assertTrue(true); }

    @Test public void failsHard() { assertEquals(1, 2, "never recovers"); }

    private static int flakyRuns = 0;
    @Test(retryAnalyzer = Retry.class)
    public void flakyRecovers() { assertTrue(++flakyRuns > 2, "attempt " + flakyRuns); }

    @Test(timeOut = 100) public void timesOut() throws Exception { Thread.sleep(5000); }

    @DataProvider(name = "rows")
    public Object[][] rows() { return new Object[][] {{"alpha", 1}, {"beta", 2}}; }
    @Test(dataProvider = "rows") public void parameterised(String name, int n) { assertTrue(n > 0); }

    @Test public void upstreamFails() { fail("upstream"); }
    @Test(dependsOnMethods = "upstreamFails") public void skippedByDependency() { assertTrue(true); }

    @Test(enabled = false) public void disabled() { fail("should never run"); }

    @Test
    public void carriesMetadata() throws Exception {
        Qualflare.label("feature", "checkout");
        Qualflare.tag("smoke");
        Qualflare.priority(Qualflare.HIGH);
        Qualflare.parameter("sku", "widget");
        Qualflare.maskedParameter("token");
        Qualflare.step("add to cart", () -> Qualflare.parameter("qty", "2"));

        // Proves the whole attachment path end to end: API -> Attachments -> Accumulator
        // -> ReportWriter. Inlined rather than image-copied so the assertion can read the
        // content back out of the report itself.
        java.nio.file.Path f = java.nio.file.Files.createTempFile("qf-fixture", ".txt");
        java.nio.file.Files.write(f, "receipt-body".getBytes(StandardCharsets.UTF_8));
        Qualflare.attachment("receipt", f, "text/plain");
        java.nio.file.Files.deleteIfExists(f);
    }
}
