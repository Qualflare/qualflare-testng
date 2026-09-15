import com.qualflare.testng.Qualflare;
import org.testng.annotations.Test;

/**
 * A second class, so the uploaded report has more than one suite.
 *
 * <p>Cases are grouped by their test class, so this exists to prove that grouping actually
 * happens end to end rather than only in a unit test. A single-class dogfood suite would
 * leave the grouping code exercised but never observed in a real report.
 */
public class SecondClassTest {

    @Test
    public void reportsFromASecondClass() {
        Qualflare.tag("second-class");
        Qualflare.label("feature", "suite-grouping");
    }
}
