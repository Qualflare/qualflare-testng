import org.testng.annotations.*;
import static org.testng.Assert.*;

public class BrokenConfigTest {
    @BeforeClass public void setUp() { throw new IllegalStateException("beforeClass exploded"); }
    @Test public void guardedOne() { assertTrue(true); }
    @Test public void guardedTwo() { assertTrue(true); }
}
