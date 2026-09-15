# qualflare-testng

A native TestNG reporter for [Qualflare](https://qualflare.com) — captures results
directly from your test run: status, per-attempt retry history and flakiness, nested
steps, attachments, and author-facing metadata.

Without it, TestNG results reach Qualflare through JUnit XML, which carries pass/fail, a
duration and a class name. No per-attempt history, no metadata, and no way to tell a
timeout from a failure.

The reporter makes **no network calls**. It writes a report directory, and
[`qualflare-cli`](https://github.com/Qualflare/qualflare-cli) uploads it.

## Install

```xml
<dependency>
  <groupId>com.qualflare</groupId>
  <artifactId>qualflare-testng</artifactId>
  <version>0.1.0</version>
  <scope>test</scope>
</dependency>
```

That is the whole setup. The listener registers itself through TestNG's `ServiceLoader`
support — no `@Listeners` annotation, no `-listener` flag, no `testng.xml` edit.

Requires **TestNG 7.4.0+** and **Java 11+**. The 7.4.0 floor is not arbitrary:
`ITestResult.wasRetried()` must exist, and the entire retry story depends on it.

**Zero runtime dependencies.** The JSON is hand-rolled, and TestNG itself is `provided`
scope. A test reporter sits on everyone's test classpath; putting a second copy of
anything in front of what the project already uses is a real source of breakage.

## Retries

TestNG retries through `IRetryAnalyzer`, in the same JVM, and the full attempt sequence is
captured:

```
attempts = [failed, failed, passed]    isFlaky = true    retryCount = 2
```

Worth knowing: TestNG delivers a retried *failure* as a **skip** with `wasRetried()` set,
not as a failure. This reporter routes on that flag, which is why a flaky test reports as
flaky rather than as two skips and a pass.

Maven Surefire's `rerunFailingTestsCount` does **not** apply to the TestNG provider —
measured. Retries come from `IRetryAnalyzer` only.

## Enriching your tests

```java
import com.qualflare.testng.Qualflare;
import org.testng.annotations.Test;

public class CheckoutTest {

    @Test
    public void checksOut() {
        Qualflare.label("feature", "checkout");
        Qualflare.tag("smoke");
        Qualflare.priority(Qualflare.HIGH);

        Qualflare.step("add to cart", () -> {
            Qualflare.parameter("sku", "widget");
            Qualflare.maskedParameter("token");
        });
    }
}
```

No annotation and no registration — the API finds the running test through TestNG's own
thread-local. Calls made off the test thread are dropped with a warning rather than
attached to whichever test runs next.

**Nothing in the API can fail your test.** No method throws, and calls are inert when no
reporter is listening.
