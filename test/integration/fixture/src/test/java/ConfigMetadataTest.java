import com.qualflare.testng.Qualflare;
import org.testng.annotations.*;
import static org.testng.Assert.*;

/**
 * Setting shared labels in a @BeforeMethod is mainstream TestNG, and
 * Reporter.getCurrentTestResult() is non-null there -- it returns the CONFIGURATION
 * method's result. The metadata must be dropped with a warning, never attached to the test
 * that follows and never filed under a key that has no case.
 *
 * The setUp here SUCCEEDS on purpose: a successful configuration method is deliberately not
 * reported, so there is no case anywhere for this metadata to belong to.
 */
public class ConfigMetadataTest {

    @BeforeMethod
    public void setUp() {
        Qualflare.label("configLabel", "leaked-from-beforeMethod");
        Qualflare.tag("leaked-config-tag");
        Qualflare.parameter("configParam", "leaked-config-value");
    }

    @Test
    public void doesNotInheritConfigMetadata() {
        assertTrue(true);
    }
}
