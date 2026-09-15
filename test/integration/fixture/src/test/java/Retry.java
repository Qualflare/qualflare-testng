import org.testng.IRetryAnalyzer;
import org.testng.ITestResult;

public class Retry implements IRetryAnalyzer {
    private int count = 0;
    @Override public boolean retry(ITestResult result) { return count++ < 2; }
}
