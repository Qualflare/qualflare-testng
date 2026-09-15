import org.testng.IRetryAnalyzer;
import org.testng.ITestResult;

/**
 * Retries a test twice, then stops.
 *
 * <p>Exists so the dogfood suite can demonstrate the capability this reporter was built
 * for: TestNG delivers a retried FAILURE as a skip with {@code wasRetried()} set, and a
 * listener that trusts the callback name reports a flaky test as clean. The case this
 * drives ends green, so it belongs in the uploaded suite.
 */
public class Recovers implements IRetryAnalyzer {

    private static final int MAX_RETRIES = 2;

    private int attempts = 0;

    @Override
    public boolean retry(ITestResult result) {
        return attempts++ < MAX_RETRIES;
    }
}
