# qualflare-testng

[![CI](https://github.com/Qualflare/qualflare-testng/actions/workflows/ci.yml/badge.svg)](https://github.com/Qualflare/qualflare-testng/actions/workflows/ci.yml)
[![Qualflare](https://api.qualflare.com/p/qualflare-testng/badge.svg)](https://reports.qualflare.com/p/qualflare-testng/launches)
[![License: Apache-2.0](https://img.shields.io/badge/License-Apache%202.0-blue.svg)](./LICENSE)

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

## Quickstart

```bash
mvn test
npm install -g @qualflare/cli
qf login my-project "$QUALFLARE_TOKEN" --force
qf my-project collect ./qualflare-results
```

The reporter writes one uniquely-named JSON file per JVM into `qualflare-results/`, and
`qf collect` merges every file in the directory into a single launch — which is what makes
a forked or sharded run arrive as one report rather than one per worker. Override the
directory with `-Dqualflare.outputDir=...` or `QUALFLARE_OUTPUT_DIR`.

`qualflare-cli` is a standalone Go binary, not a Java artifact — it is not on Maven
Central. Homebrew and npm are the two channels; the release page also carries plain
binaries for every platform.

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
thread-local. Calls made with **no test in scope** — including from a configuration method
such as `@BeforeMethod` — are dropped with a warning rather than attached to whichever test
runs next.

**Nothing in the API can fail your test.** No method throws, and calls are inert when no
reporter is listening.

## Test reports

This reporter is tested with itself. `e2e/` is a TestNG suite covering this package's own
behaviour — the metadata API, nested steps, attachments, DataProvider rows as separate
cases, a retried test recorded as three attempts, and a second class as its own suite —
run by this reporter and uploaded to Qualflare on every merge to `main` by the
**published** `qualflare-cli`. The results below are that suite's, reported through the
code this README documents:

[![Qualflare](https://api.qualflare.com/p/qualflare-testng/banner.svg)](https://reports.qualflare.com/p/qualflare-testng/launches)

Every case there is meant to pass, so a red run is a real regression rather than a fixture
failing on purpose. The deliberately awkward cases — a throwing `@BeforeClass`, a test that
never recovers, a timeout, metadata emitted from a configuration method — live in
`test/integration/fixture`, which sets `testFailureIgnore` and is never uploaded.

That suite is a separate Maven project rather than this repo's unit tests, deliberately:
the reporter registers itself through `ServiceLoader`, so running the root project's tests
attaches a live listener to tests that reset the accumulator and inspect it directly. The
resulting self-report would be one case out of 47 — a green badge meaning nothing.
