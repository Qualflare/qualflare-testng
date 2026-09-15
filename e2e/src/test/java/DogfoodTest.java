import com.qualflare.testng.Qualflare;
import org.testng.Reporter;
import org.testng.annotations.DataProvider;
import org.testng.annotations.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.testng.Assert.assertTrue;

/**
 * The suite this reporter reports on itself, uploaded to a public Qualflare project.
 *
 * <p><b>Every case here is meant to pass</b>, so a red run is a real regression rather than
 * a fixture failing on purpose. The deliberately awkward cases -- a throwing
 * {@code @BeforeClass}, a test that never recovers, a timeout, metadata emitted from a
 * configuration method -- live in {@code test/integration/fixture}, which sets
 * {@code testFailureIgnore} and is never uploaded.
 *
 * <p>That separation is not fastidiousness. The Go reporter's dogfood suite once shipped
 * 320 steps named "filler" to a report people actually read, because the step-cap
 * regression test was put in the uploaded suite. Names here are chosen to mean something
 * to a reader who has never seen this code.
 *
 * <p>Note what is absent: there is no {@code @Listeners} annotation and no extension to
 * register. The reporter arrives through {@code ServiceLoader} from the test classpath
 * alone, and {@link Qualflare} resolves the running test through TestNG's own thread-local.
 * This file being free of reporter plumbing IS the install story the README claims.
 */
public class DogfoodTest {

    @Test
    public void recordsEveryMetadataKind() {
        Qualflare.label("team", "platform");
        Qualflare.label("feature", "reporting");
        Qualflare.tag("smoke", "dogfood");
        Qualflare.link("https://github.com/Qualflare/qualflare-testng", Qualflare.ISSUE, "QT-1");
        Qualflare.priority(Qualflare.HIGH);
        Qualflare.description("exercises every metadata call the README documents");
        Qualflare.parameter("plan", "pro");
        Qualflare.maskedParameter("token");
    }

    @Test
    public void nestsSteps() {
        Qualflare.step("add to cart", () -> {
            Qualflare.parameter("sku", "widget");
            Qualflare.step("set quantity", () -> Qualflare.parameter("qty", "2"));
        });
    }

    /** A masked parameter emitted where the secret is genuinely in scope. */
    @Test
    public void aMaskedParameterInsideAStep() {
        Qualflare.step("authenticate", () -> Qualflare.maskedParameter("api-token"));
    }

    /**
     * The attachment API, which exists because the README promised it and the final review
     * found nothing behind the promise.
     */
    @Test
    public void attachesAFile() throws IOException {
        Path note = Files.createTempFile("dogfood-note", ".txt");
        Files.write(note, "a text attachment from the dogfood suite".getBytes(StandardCharsets.UTF_8));
        try {
            Qualflare.attachment("note.txt", note, "text/plain");
        } finally {
            Files.deleteIfExists(note);
        }
    }

    @DataProvider(name = "browsers")
    public Object[][] browsers() {
        return new Object[][] {{"chrome"}, {"firefox"}};
    }

    /**
     * Each row is its own case, with the value in the name. Identity is
     * {@code class#method(params)} precisely so these do not collapse into one.
     */
    @Test(dataProvider = "browsers")
    public void dataProviderRowsAreTheirOwnCases(String browser) {
        Qualflare.label("browser", browser);
    }

    /**
     * The headline capability, and the reason this reporter exists rather than reading
     * TestNG's JUnit XML.
     *
     * <p>This fails twice and then passes, so it ends GREEN and belongs here. The report
     * will show three attempts with the first two failed and {@code isFlaky = true} --
     * which is the point. TestNG hands a retried failure to {@code onTestSkipped} with
     * {@code wasRetried()} set, so a reporter that trusted the callback name would show
     * this case as two skips and a pass, losing the failures entirely.
     */
    private static int recoveringAttempts = 0;

    @Test(retryAnalyzer = Recovers.class)
    public void retriesAreRecordedAsAttempts() {
        assertTrue(++recoveringAttempts > 2,
                "deliberate failure on attempt " + recoveringAttempts + ", retried by Recovers");
    }

    /**
     * TestNG's own {@code Reporter.log} output is not consumed by this reporter.
     *
     * <p>Capturing it would need a case-level output field the wire format does not have,
     * which is a change across all ten reporters rather than a TestNG detail -- so it is
     * deliberately out of scope. This case pins that leaving it alone is harmless: the log
     * call happens, and the metadata after it still lands.
     */
    @Test
    public void testngOwnReporterLogIsLeftAlone() {
        Reporter.log("this line belongs to TestNG, not to Qualflare");
        Qualflare.label("after", "the-decoy");
    }
}
