# qualflare-testng — design

A native TestNG reporter for Qualflare: the tenth reporter in the family, and the
second on the JVM.

## Why TestNG, and why a native reporter

`qualflare-junit5` does not cover TestNG. TestNG does not run on the JUnit Platform, so
the Launcher-level listener never sees it.

The CLI can already ingest TestNG results, but only through a thin alias: the whole
"TestNG parser" is 26 lines delegating to the shared JUnit XML parser
(`internal/adapters/parsers/unit/testng/testng.go`). TestNG users therefore get the JUnit
XML floor — status, duration, class name, error text — and nothing else. No per-attempt
retry history, no steps, no attachments, no author metadata, and no way to distinguish a
timeout from a plain failure.

This reporter closes that gap in the same shape `qualflare-junit5` did for the JUnit
Platform.

## What was measured, not assumed

Every load-bearing claim below was verified against TestNG 7.12.0 under Maven Surefire
3.5.6 with a probe listener, and the interfaces checked with `javap` against the real
jars. This section exists because the one thing the JUnit 5 reporter did *not* measure —
whether a listener instance spans a whole run — was the one thing it got wrong.

| Claim | Result |
|---|---|
| `ServiceLoader` auto-discovery works | Yes. A `META-INF/services/org.testng.ITestNGListener` entry fired under Surefire with no configuration. TestNG's own `setListenersToSkipFromBeingWiredInViaServiceLoaders(String...)` confirms wiring is on by default. |
| `IExecutionListener.onExecutionStart/Finish` scope | Fires **exactly once per JVM**, bracketing everything. |
| Listener instance lifetime | **One instance** for the entire run. |
| Surefire `rerunFailingTestsCount` | **Does not apply to the TestNG provider.** Configured properly in the pom, a hard-failing test still ran once. Retries come only from `IRetryAnalyzer`. |
| Retried failure delivery | Arrives as `onTestSkipped` (status 3) with the throwable preserved and `wasRetried() == true`. |
| `wasRetried()` availability | Present in TestNG 7.4.0 as well as 7.12.0. |
| Timeout | Distinct `onTestFailedWithTimeout` callback carrying `ThreadTimeoutException`. |
| Failing `@BeforeClass` | `onConfigurationFailure` **plus** `onTestSkipped` for each guarded test, carrying the original exception. Guarded tests are not silent (unlike JUnit). |
| `invocationCount` | Increments for retries **and** for DataProvider rows, so it cannot by itself distinguish them. |
| Disabled tests (`enabled = false`) | Emit no events at all. |
| Current-test access from a static API | `Reporter.getCurrentTestResult()` — a thread-local, so no annotation or extension is needed. |

The probe's full event trace is reproduced in Appendix A.

## Scope

A single Maven Central artifact, `com.qualflare:qualflare-testng`, in its own repository,
self-contained, with **zero runtime dependencies** — TestNG itself at `provided` scope,
mirroring how `qualflare-junit5` keeps its published artifact dependency-free.

### Deliberately out of scope for v1

- `IReporter` (that interface exists to generate HTML reports, not to observe execution)
- `testng.xml` suite-file parsing
- `IDataProviderListener`, `IAlterSuiteListener`
- Any change to `qualflare-cli`: the reporter writes the native format the
  `native/qualflare` parser already reads

## Architecture

### Components

Roughly 69% of `qualflare-junit5` (1116 of 1623 lines) is framework-agnostic and is
copied verbatim: `Accumulator`, `CaseRecord`, `Attempt`, `CaseMeta`, `ReportWriter`,
`Json`, `Config`, `Keys`, `Replay`, `Run`, `Attachments`, `Version`. Only the adapter
layer is new.

| Component | Role |
|---|---|
| `QualflareListener` | `ITestListener` + `IExecutionListener` + `IConfigurationListener`. The only TestNG-aware class. Translates callbacks into accumulator calls. |
| `Status` | Maps TestNG status + throwable type to the wire vocabulary. |
| `Qualflare` | The author-facing static API, resolving the current test via `Reporter.getCurrentTestResult()`. |
| *(copied core)* | Accumulation, JSON writing, config, step/attachment budgets. |

### Why the code is duplicated rather than shared

A shared `qualflare-jvm-core` artifact would remove ~1100 lines of duplication, but it
would also become the first real runtime dependency of both reporters (breaking a claim
that is currently literally true), couple their release cadences, and require
restructuring an already-published artifact. All nine existing reporters are separate
self-contained repositories, and release tooling, docs and badges assume that.

**Recorded trigger:** extract a shared core when a *third* JVM reporter appears. Two
copies of stable code is cheaper than the refactor; three is not. The duplicated code is
the part that has already been through mutation testing — churn lives in the adapters.

### Install

```xml
<dependency>
  <groupId>com.qualflare</groupId>
  <artifactId>qualflare-testng</artifactId>
  <scope>test</scope>
</dependency>
```

That is the whole setup. No `@Listeners` annotation, no `-listener` flag, no `testng.xml`
edit, and — unlike `qualflare-junit5` — no extension to register and no autodetection
property to enable, because the metadata API resolves the current test through TestNG's
own thread-local.

### Write hook and state

The report is written in `onExecutionFinish()`, which is a genuine once-per-JVM signal.
A JVM shutdown hook remains as a fallback for tools that drive `TestNG` programmatically
without the execution listener firing.

Run state is held in JVM-scoped statics. **The reason differs from `qualflare-junit5` and
should not be copied blindly:** there, statics were mandatory because Surefire opened a
new launcher session per re-run and the Platform reloaded the listener. Here a single
instance spans the run, so instance fields would work. Statics are kept only so that
`forkCount > 1` yields one file per JVM — the directory-merge model `qf collect` already
uses for pytest-xdist and Vitest shards.

## Data flow

### Case identity

A case is keyed on `className + "#" + methodName + "(" + parameters + ")"`.

`invocationCount` is **not** part of the key. It increments for retries *and* for
DataProvider rows, so keying on it would split a retried test into separate cases;
ignoring parameters would collapse distinct DataProvider rows into one. Parameters
separate cases; `invocationCount` orders attempts within a case.

- `parameterised("alpha",1)` and `parameterised("beta",2)` → two cases, one attempt each
- `flakyRecovers` at invocationCount 0,1,2 → one case, three attempts

### Attempts, and the rule that matters

> **`onTestSkipped` with `wasRetried() == true` records a FAILED attempt, not a skip.**

When `IRetryAnalyzer.retry()` returns true, TestNG marks the failed attempt SKIP so it
does not count as a failure, while preserving the throwable. A listener that trusts the
callback name reports the measured `flakyRecovers` case as two *skipped* attempts followed
by a pass — losing the failures entirely and reporting a flaky test as clean.

`wasRetried() == false` on a skip is a genuine skip: a dependency failure or a
configuration failure.

Derived per case:

- final status — the last attempt's status
- `isFlaky` — true when a failed attempt is followed by a passing one
- `retryCount` — attempts minus one
- `attempts` — omitted below two, matching the wire contract

### Status mapping

| TestNG | Wire status |
|---|---|
| `SUCCESS` | `passed` |
| `FAILURE`, throwable is an `AssertionError` | `failed` |
| `FAILURE`, any other throwable | `error` |
| `onTestFailedWithTimeout` | `timeout` |
| `SKIP`, `wasRetried() == false` | `skipped` |
| `SKIP`, `wasRetried() == true` | `failed` attempt (see above) |
| `SUCCESS_PERCENTAGE_FAILURE` | `failed` |

Assertion failures are classified **by type** (`instanceof AssertionError`), not by a list
of class names. This is carried over from a defect in `qualflare-junit5` 0.1.0, where an
exact-name list mislabelled every `org.junit.ComparisonFailure` as `error`. By type also
covers AssertJ, Truth, Hamcrest and TestNG's own assertions.

`timeout` is fidelity the JUnit reporter cannot produce; the wire contract already carries
it.

### Configuration failures

A throwing `@BeforeClass` yields `onConfigurationFailure` plus a skip per guarded test.
The guarded skips are reported as skips, carrying the original exception as their error.

In addition, **the configuration method is emitted as its own case with status `error`.**
A suite whose tests are all skipped reads as "nothing ran" rather than "broken", which is
the same false-green that `qualflare-junit5` emits a synthetic `[container failure]` case
to avoid — except here the case carries the real method name and real stack trace.

That case is named for the method it reports, prefixed to mark it as configuration rather
than a test the author wrote: `[config] BrokenConfigTest#setUp`. The prefix matters because
the name appears in a case list beside real tests, and an unprefixed `setUp` would read as
a test that someone forgot to delete. Only *failing* configuration methods produce a case;
successful `@BeforeClass`/`@AfterClass` invocations are not reported, or every suite would
gain noise cases for setup that worked.

### Author metadata

The `Qualflare` static API mirrors `qualflare-junit5`: `label`, `tag`, `link`, `priority`,
`step`, `parameter`, `maskedParameter`, `attachment`. The current test is resolved through
`Reporter.getCurrentTestResult()`.

`maskedParameter` takes no value, as in the JUnit reporter: `masked` is only a display
hint the server does not act on, so withholding the value is what actually keeps a secret
out of the report.

Calls made when no test is in scope — off the test thread, or outside a test — are dropped
with a warning rather than attached to whichever test runs next. Wrong metadata that looks
right is worse than absent metadata.

Additionally, `Reporter.log()` output is captured into the attempt's existing `stdout`
field, read per attempt via `Reporter.getOutput(ITestResult)` (verified present in 7.12.0).
TestNG users lean on `Reporter.log()` the way other ecosystems lean on `console.log`, and
the wire field already exists. Reading it per `ITestResult` rather than from the global
`Reporter.getOutput()` is what keeps output attributed to the right attempt under
`parallel="methods"`.

## Error handling

Nothing in the reporter may fail a build. No method on the public API throws or returns an
error, and calls are inert when no reporter is listening. A failure while writing the
report is reported on stderr and swallowed, as in `qualflare-junit5`.

The step cap (300 per attempt) and the attachment budget are inherited from the copied
core, including the fix where the budget is charged in **encoded** bytes rather than raw,
and the one where exceeding the cap produces a visible warning rather than a silently
shorter step list.

## Testing

Mirroring `qualflare-junit5`, whose test shape earned its keep by catching four defects in
review.

**Unit tests** drive the `Accumulator` and `Status` directly: identity keying, attempt
accumulation, the retried-skip rule, status mapping by throwable type, the step cap, and
that a masked parameter carries no value.

**An integration fixture** runs a real TestNG suite and asserts the traps this design
exists to handle:

1. a retried failure is a failed attempt, so `flakyRecovers` reports
   `attempts = [failed, failed, passed]` with `isFlaky = true`
2. a test that never recovers is `failed` and **not** flaky
3. DataProvider rows stay distinct cases
4. a timeout reports `timeout`, not `failed`
5. a failing `@BeforeClass` produces an `error` case for the config method *and* skips for
   the guarded tests
6. a dependency skip is `skipped`, not a failed attempt
7. a disabled test appears nowhere

**A serial-versus-parallel comparison**, as in `qualflare-junit5`: under
`parallel="methods"` every accumulation is a read-modify-write from a TestNG worker
thread, and the two reports must match. This is what would catch a lost attempt or
metadata attributed to the wrong test.

**Mutation checks.** Each guard above is confirmed by breaking it deliberately and seeing
the test fail — the discipline that verified every guard in the JUnit reporter.

**A compatibility matrix** across TestNG versions (7.4.0 as the floor, since `wasRetried()`
must exist, through 7.12.0) and Java 11/17/21.

**A dogfood suite** that reports on the reporter itself and uploads on merge to `main`,
backing the README badge. Awkward fixtures stay in the integration fixture, which is never
uploaded — the Go reporter once shipped 320 steps named `filler` to a public report by
putting its step-cap test in the uploaded suite.

## Open questions

None blocking. Two decisions deferred by choice:

- **Shared JVM core** — deferred until a third JVM reporter exists, per the recorded
  trigger above.
- **Version floor** — 7.4.0 is proposed because `wasRetried()` must exist. If real users
  are on older TestNG, the retried-skip rule needs a fallback (infer a retry from a later
  invocation of the same identity), which is strictly worse and should only be built if
  demand appears.

## Appendix A — probe trace

TestNG 7.12.0, Surefire 3.5.6, one JVM. `pid` omitted; the listener identity is
unchanged throughout, which is the point.

```
EXECUTION_START                       listener=1926764753
SUITE_START   name=Surefire suite
CTX_START     name=Surefire test
BEFORE_CLASS  MainTest
TEST_START    MainTest#failsHard                invocationCount=0
TEST_FAIL     MainTest#failsHard                thrown=AssertionError wasRetried=false status=2
TEST_START    MainTest#flakyRecovers            invocationCount=0
TEST_SKIP     MainTest#flakyRecovers            thrown=AssertionError wasRetried=true  status=3
TEST_START    MainTest#flakyRecovers            invocationCount=1
TEST_SKIP     MainTest#flakyRecovers            thrown=AssertionError wasRetried=true  status=3
TEST_START    MainTest#flakyRecovers            invocationCount=2
TEST_PASS     MainTest#flakyRecovers
TEST_START    MainTest#parameterised(alpha,1)   invocationCount=0
TEST_PASS     MainTest#parameterised(alpha,1)
TEST_START    MainTest#parameterised(beta,2)    invocationCount=1
TEST_PASS     MainTest#parameterised(beta,2)
TEST_START    MainTest#passes                   invocationCount=0
TEST_PASS     MainTest#passes
TEST_START    MainTest#timesOut                 invocationCount=0
TEST_TIMEOUT  MainTest#timesOut                 thrown=ThreadTimeoutException
TEST_START    MainTest#upstreamFails            invocationCount=0
TEST_FAIL     MainTest#upstreamFails            thrown=AssertionError wasRetried=false status=2
TEST_START    MainTest#skippedByDependency      invocationCount=0
TEST_SKIP     MainTest#skippedByDependency      thrown=Throwable      wasRetried=false status=3
AFTER_CLASS   MainTest
BEFORE_CLASS  BrokenConfigTest
CONFIG_FAIL   BrokenConfigTest#setUp            thrown=IllegalStateException
TEST_START    BrokenConfigTest#guardedOne       invocationCount=0
TEST_SKIP     BrokenConfigTest#guardedOne       thrown=IllegalStateException wasRetried=false status=3
TEST_START    BrokenConfigTest#guardedTwo       invocationCount=0
TEST_SKIP     BrokenConfigTest#guardedTwo       thrown=IllegalStateException wasRetried=false status=3
AFTER_CLASS   BrokenConfigTest
CTX_FINISH    name=Surefire test
SUITE_FINISH  name=Surefire suite
EXECUTION_FINISH                      listener=1926764753
```

Note `disabled` — annotated `enabled = false` — appears nowhere.
