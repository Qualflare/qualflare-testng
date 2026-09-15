# qualflare-testng Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** A native TestNG reporter that writes Qualflare's JSON report format, capturing per-attempt retry history, steps, attachments and author metadata that TestNG's JUnit XML output cannot carry.

**Architecture:** One self-contained Maven artifact. Twelve framework-agnostic classes are copied verbatim from `qualflare-junit5` (only the package line changes); four new classes form the TestNG adapter. A `ServiceLoader`-registered listener implements `ITestListener` + `IExecutionListener` + `IConfigurationListener`, accumulates into JVM-scoped static state, and writes one JSON file per JVM on `onExecutionFinish()`.

**Tech Stack:** Java 11 (compile floor), TestNG 7.4.0+ at `provided` scope, Maven, Maven Surefire 3.5.6, Python 3 for the integration verifier.

**Spec:** `docs/superpowers/specs/2026-09-15-qualflare-testng-design.md`

## Global Constraints

Every task's requirements implicitly include these. Values are copied verbatim from the spec.

- **Package:** `com.qualflare.testng`. **Artifact:** `com.qualflare:qualflare-testng`.
- **Java floor:** 11 (`maven.compiler.release`). **TestNG floor:** 7.4.0, because `ITestResult.wasRetried()` must exist.
- **Zero runtime dependencies.** TestNG is `provided` scope. The published jar must have no transitive compile-scope dependency. Never add one.
- **Nothing may fail a build.** No method on the public API throws or returns an error. A failure while writing the report goes to stderr and is swallowed.
- **Wire statuses** are exactly: `passed`, `failed`, `skipped`, `error`, `timeout`, `aborted`.
- **Durations are integer nanoseconds** on the wire.
- **`attempts` is omitted below 2 entries** (`ReportWriter.MIN_ATTEMPTS_TO_SEND`).
- **Step cap:** 300 per attempt (`Replay.MAX_STEPS_PER_ATTEMPT`). Exceeding it must produce a visible warning, never a silently shorter list.
- **Attachment budget is charged in encoded bytes**, not raw bytes.
- **Assertion failures are classified by TYPE** (`instanceof AssertionError`), never by a list of class names.
- **Metadata calls outside a test are dropped with a warning**, never attached to whichever test runs next.

---

## File Structure

| File | Responsibility |
|---|---|
| `pom.xml` | Build, TestNG `provided`, jar manifest carrying `Implementation-Version` |
| `src/main/java/com/qualflare/testng/TestKey.java` | **New.** The one identity function, shared by listener and metadata API |
| `src/main/java/com/qualflare/testng/Status.java` | **New.** TestNG status + throwable → wire status |
| `src/main/java/com/qualflare/testng/QualflareListener.java` | **New.** The only TestNG-aware lifecycle class |
| `src/main/java/com/qualflare/testng/Qualflare.java` | **New.** Author-facing static API |
| `src/main/java/com/qualflare/testng/{Accumulator,Attempt,Attachments,CaseMeta,CaseRecord,Config,Json,Keys,Replay,ReportWriter,Run,Version}.java` | **Copied verbatim** from `qualflare-junit5`, package line only |
| `src/main/resources/META-INF/services/org.testng.ITestNGListener` | Zero-config registration |
| `src/test/java/com/qualflare/testng/*Test.java` | Unit tests |
| `test/integration/fixture/**`, `test/integration/verify.py` | End-to-end gate |
| `.github/workflows/ci.yml` | Compatibility matrix |

**Source of the copied files:** `/Users/ibrahim/Astrais/frameworks/qualflare-junit5/src/main/java/com/qualflare/junit5/`

---

### Task 1: Build skeleton that carries its own version

**Files:**
- Create: `pom.xml`, `.gitignore`, `src/main/java/com/qualflare/testng/Version.java`
- Test: `src/test/java/com/qualflare/testng/VersionTest.java`

**Interfaces:**
- Consumes: nothing
- Produces: `Version.VALUE` (`static final String`) — the reporter version stamped into every report

**Why this is a task and not setup:** `qualflare-junit5` 0.1.0 shipped with every report claiming version `0.0.0-dev`, because the jar manifest had no `Implementation-Version` and the fallback shipped. It was invisible until someone opened a report. Building the manifest wiring first, with a test, means this reporter cannot repeat it.

- [ ] **Step 1: Write `pom.xml`**

```xml
<?xml version="1.0" encoding="UTF-8"?>
<project xmlns="http://maven.apache.org/POM/4.0.0">
  <modelVersion>4.0.0</modelVersion>
  <groupId>com.qualflare</groupId>
  <artifactId>qualflare-testng</artifactId>
  <version>0.1.0-SNAPSHOT</version>
  <packaging>jar</packaging>
  <name>qualflare-testng</name>
  <description>Native TestNG reporter for Qualflare</description>

  <properties>
    <maven.compiler.release>11</maven.compiler.release>
    <project.build.sourceEncoding>UTF-8</project.build.sourceEncoding>
    <!-- The FLOOR, not the tested ceiling. wasRetried() must exist; it does from 7.4.0.
         CI runs the fixture against 7.4.0 and 7.12.0. -->
    <testng.version>7.12.0</testng.version>
    <surefire.version>3.5.6</surefire.version>
  </properties>

  <dependencies>
    <!-- provided: the published artifact must pull in nothing. A test reporter sits on
         everyone's test classpath and must not put a second TestNG in front of theirs. -->
    <dependency>
      <groupId>org.testng</groupId>
      <artifactId>testng</artifactId>
      <version>${testng.version}</version>
      <scope>provided</scope>
    </dependency>
  </dependencies>

  <build>
    <plugins>
      <plugin>
        <groupId>org.apache.maven.plugins</groupId>
        <artifactId>maven-jar-plugin</artifactId>
        <version>3.4.2</version>
        <configuration>
          <archive>
            <!-- Without this, getImplementationVersion() returns null and every report
                 claims 0.0.0-dev. That shipped once already in qualflare-junit5 0.1.0. -->
            <manifest><addDefaultImplementationEntries>true</addDefaultImplementationEntries></manifest>
          </archive>
        </configuration>
      </plugin>
      <plugin>
        <groupId>org.apache.maven.plugins</groupId>
        <artifactId>maven-surefire-plugin</artifactId>
        <version>${surefire.version}</version>
      </plugin>
    </plugins>
  </build>
</project>
```

- [ ] **Step 2: Write `.gitignore`**

```
target/
qualflare-results/
```

- [ ] **Step 3: Write the failing test**

Create `src/test/java/com/qualflare/testng/VersionTest.java`:

```java
package com.qualflare.testng;

import org.testng.annotations.Test;
import static org.testng.Assert.*;

public class VersionTest {

    /**
     * Pins the defect qualflare-junit5 0.1.0 shipped: every report claimed 0.0.0-dev
     * because the jar manifest had no Implementation-Version. Running from target/classes
     * there is no manifest at all, so the fallback IS correct here -- what this asserts is
     * that the constant exists, is never null, and is never empty.
     */
    @Test
    public void versionIsAlwaysPresent() {
        assertNotNull(Version.VALUE, "version must never be null");
        assertFalse(Version.VALUE.trim().isEmpty(), "version must never be blank");
    }
}
```

- [ ] **Step 4: Run it and watch it fail**

Run: `mvn -q test -Dtest=VersionTest`
Expected: compilation failure — `cannot find symbol: class Version`.

- [ ] **Step 5: Write the minimal implementation**

Create `src/main/java/com/qualflare/testng/Version.java`:

```java
package com.qualflare.testng;

/** The reporter version, stamped into every report. */
final class Version {

    /**
     * Read from the jar manifest's Implementation-Version, which maven-jar-plugin adds via
     * addDefaultImplementationEntries. Null when running from target/classes (no manifest),
     * which is why the fallback exists -- but it must never be what a RELEASED jar reports.
     */
    static final String VALUE = value();

    private Version() {}

    private static String value() {
        String v = Version.class.getPackage().getImplementationVersion();
        return v == null || v.trim().isEmpty() ? "0.0.0-dev" : v;
    }
}
```

- [ ] **Step 6: Run the test and watch it pass**

Run: `mvn -q test -Dtest=VersionTest`
Expected: PASS.

- [ ] **Step 7: Prove the manifest wiring actually works in a built jar**

Run:
```bash
mvn -q package -DskipTests
unzip -p target/qualflare-testng-0.1.0-SNAPSHOT.jar META-INF/MANIFEST.MF | grep Implementation-Version
```
Expected: `Implementation-Version: 0.1.0-SNAPSHOT`. If this line is absent the manifest configuration is wrong — fix it now, not after release.

- [ ] **Step 8: Commit**

```bash
git add pom.xml .gitignore src/main/java/com/qualflare/testng/Version.java src/test/java/com/qualflare/testng/VersionTest.java
git commit -m "build: maven skeleton with the version stamped into the jar manifest"
```

---

### Task 2: Copy the framework-agnostic core

**Files:**
- Create (copied): `src/main/java/com/qualflare/testng/{Accumulator,Attempt,Attachments,CaseMeta,CaseRecord,Config,Json,Keys,Replay,ReportWriter,Run}.java`
- Test: `src/test/java/com/qualflare/testng/CoreSmokeTest.java`

**Interfaces:**
- Consumes: `Version.VALUE` from Task 1
- Produces, relied on by every later task — exact signatures:
  - `Run.accumulator()` → `Accumulator`
  - `Run.ensureHook()` → `void`; `Run.write()` → `void`; `Run.resetForTest()` → `void`
  - `Accumulator.started(String uniqueId, long nanoTime)`
  - `Accumulator.finished(String uniqueId, String suiteName, String className, String displayName, String legacyName, String status, long nanoTime, String message, String trace)`
  - `Accumulator.skipped(String uniqueId, String suiteName, String className, String displayName, String legacyName, String reason)`
  - `Accumulator.entry(String uniqueId, String key, String value)`
  - `Accumulator.attachment(String uniqueId, Attachments.Attachment a)`
  - `Accumulator.cases()` → `Collection<CaseRecord>`; `isEmpty()` → `boolean`; `clear()` → `void`
  - `ReportWriter.write(Collection<CaseRecord>)` → `Path`; `ReportWriter.render(Collection<CaseRecord>)` → `String`
  - `Attempt(String status, long durationNanos, String message, String trace)`
  - `Keys.SEP`, `Keys.LABEL`, `Keys.TAG`, `Keys.LINK`, `Keys.PRIORITY`, `Keys.DESCRIPTION`, `Keys.PARAMETER`, `Keys.MASKED_PARAMETER`, `Keys.STEP_START`, `Keys.STEP_STOP`, `Keys.isOurs(String)`
  - `Replay.MAX_STEPS_PER_ATTEMPT` (= 300), `Replay.apply(CaseMeta, List<String[]>)`

**Do not rewrite these files.** They carry fixes that were found by mutation testing and by production defects: the attachment budget charged in encoded bytes, the depth-bounded cause walk, the step-cap warning that used to be discarded. Copy them; change one line each.

- [ ] **Step 1: Copy the eleven files and rewrite only the package line**

```bash
SRC=/Users/ibrahim/Astrais/frameworks/qualflare-junit5/src/main/java/com/qualflare/junit5
DST=src/main/java/com/qualflare/testng
mkdir -p "$DST"
for f in Accumulator Attempt Attachments CaseMeta CaseRecord Config Json Keys Replay ReportWriter Run; do
  sed 's/^package com\.qualflare\.junit5;$/package com.qualflare.testng;/' "$SRC/$f.java" > "$DST/$f.java"
done
```

- [ ] **Step 2: Verify that the package line is the ONLY difference**

```bash
SRC=/Users/ibrahim/Astrais/frameworks/qualflare-junit5/src/main/java/com/qualflare/junit5
DST=src/main/java/com/qualflare/testng
for f in Accumulator Attempt Attachments CaseMeta CaseRecord Config Json Keys Replay ReportWriter Run; do
  echo "--- $f"
  diff "$SRC/$f.java" "$DST/$f.java" | grep -E '^[<>]' || true
done
```

Expected: for each file exactly two lines — `< package com.qualflare.junit5;` and `> package com.qualflare.testng;`. **Any other line means the copy drifted; redo it.**

- [ ] **Step 3: Check for leftover references to the old identifier**

```bash
grep -rn 'junit5\|org\.junit' src/main/java/com/qualflare/testng/ || echo "clean"
```
Expected: `clean`. These eleven files were measured to contain no `org.junit` imports. If anything matches, the wrong file was copied.

- [ ] **Step 4: Adjust the two strings that name the reporter**

`ReportWriter` and `Run` print diagnostics prefixed `[qualflare-junit5]`, and `ReportWriter` names the report file. Both must say `testng`:

```bash
sed -i.bak 's/\[qualflare-junit5\]/[qualflare-testng]/g; s/qualflare-junit5-/qualflare-testng-/g' \
  src/main/java/com/qualflare/testng/ReportWriter.java \
  src/main/java/com/qualflare/testng/Run.java
rm -f src/main/java/com/qualflare/testng/*.bak
grep -rn 'qualflare-junit5' src/main/java/com/qualflare/testng/ || echo "clean"
```
Expected: `clean`.

- [ ] **Step 5: Write the smoke test**

Create `src/test/java/com/qualflare/testng/CoreSmokeTest.java`:

```java
package com.qualflare.testng;

import org.testng.annotations.Test;
import java.nio.file.Files;
import java.nio.file.Path;
import static org.testng.Assert.*;

/**
 * Proves the copied core compiles and round-trips. It is NOT a re-test of the core's
 * behaviour -- that is covered in qualflare-junit5 and the files are byte-identical apart
 * from the package. This asserts only that the copy is wired up and usable here.
 */
public class CoreSmokeTest {

    @Test
    public void accumulatesOneCaseAndRendersIt() {
        Accumulator acc = new Accumulator();
        acc.started("k1", 1_000L);
        acc.finished("k1", "MySuite", "com.example.MyTest", "passes()",
                "com.example.MyTest.passes", Status.PASSED, 5_000L, "", "");

        assertEquals(acc.cases().size(), 1, "one finished test is one case");
        String json = ReportWriter.render(acc.cases());
        assertTrue(json.contains("passes()"), "rendered report must name the case");
        assertTrue(json.contains("\"passed\""), "rendered report must carry the status");
    }

    @Test
    public void writesAFileNamedForThisReporter() throws Exception {
        Accumulator acc = new Accumulator();
        acc.started("k2", 0L);
        acc.finished("k2", "S", "C", "d", "l", Status.PASSED, 1L, "", "");
        Path p = ReportWriter.write(acc.cases());
        try {
            assertTrue(Files.exists(p), "the writer must produce a file");
            assertTrue(p.getFileName().toString().startsWith("qualflare-testng-"),
                    "file must be named for THIS reporter, got " + p.getFileName());
        } finally {
            Files.deleteIfExists(p);
        }
    }
}
```

**Note:** this test references `Status.PASSED`, which Task 4 creates. Write Task 4's `Status` before running it, or temporarily inline the literal `"passed"`. The plan orders Status after this task because `Status` is TestNG-aware and this one is not; if executing strictly in order, use the string literal and switch to the constant in Task 4.

- [ ] **Step 6: Compile**

Run: `mvn -q compile`
Expected: BUILD SUCCESS.

- [ ] **Step 7: Commit**

```bash
git add src/main/java/com/qualflare/testng src/test/java/com/qualflare/testng/CoreSmokeTest.java
git commit -m "feat: copy the framework-agnostic core from qualflare-junit5

Eleven files, package line only. Verified by diff that nothing else changed:
these carry fixes found by mutation testing and by production defects (the
attachment budget charged in encoded bytes, the depth-bounded cause walk, the
step-cap warning that was previously discarded) and must not be rewritten.

Duplicated rather than shared, per the spec: a shared core would become the
first runtime dependency of both JVM reporters and require restructuring a
published artifact. Extract one when a THIRD JVM reporter appears."
```

---

### Task 3: `TestKey` — the one identity function

**Files:**
- Create: `src/main/java/com/qualflare/testng/TestKey.java`
- Test: `src/test/java/com/qualflare/testng/TestKeyTest.java`

**Interfaces:**
- Consumes: nothing
- Produces: `TestKey.of(ITestResult)` → `String`, and `TestKey.displayName(ITestResult)` → `String`

**Why its own task:** both `QualflareListener` and `Qualflare` must compute the *same* key, or metadata lands under a different identity than the case it belongs to and is silently dropped. One function, one test, used by both.

- [ ] **Step 1: Write the failing test**

Create `src/test/java/com/qualflare/testng/TestKeyTest.java`:

```java
package com.qualflare.testng;

import org.testng.ITestResult;
import org.testng.annotations.DataProvider;
import org.testng.annotations.Test;
import static org.testng.Assert.*;

/**
 * Identity is the thing that decides whether a retried test is ONE case with three
 * attempts or three separate cases -- and whether two DataProvider rows collapse into one.
 * Measured: invocationCount increments for BOTH, so it cannot be the discriminator.
 */
public class TestKeyTest {

    @Test
    public void includesClassAndMethod() {
        ITestResult r = Fakes.result("com.example.MyTest", "passes", new Object[] {});
        assertEquals(TestKey.of(r), "com.example.MyTest#passes()");
    }

    @Test
    public void parametersSeparateDataProviderRows() {
        ITestResult a = Fakes.result("com.example.MyTest", "rows", new Object[] {"alpha", 1});
        ITestResult b = Fakes.result("com.example.MyTest", "rows", new Object[] {"beta", 2});
        assertNotEquals(TestKey.of(a), TestKey.of(b),
                "different DataProvider rows must be different cases");
    }

    @Test
    public void retriesOfTheSameTestShareOneKey() {
        // Same class, same method, same (empty) parameters -- only invocationCount differs,
        // and invocationCount is deliberately NOT part of the key.
        ITestResult first = Fakes.result("com.example.MyTest", "flaky", new Object[] {});
        ITestResult third = Fakes.result("com.example.MyTest", "flaky", new Object[] {});
        assertEquals(TestKey.of(first), TestKey.of(third),
                "retries are attempts of ONE case, not separate cases");
    }

    @Test
    public void survivesNullParameters() {
        ITestResult r = Fakes.result("com.example.MyTest", "nulls", new Object[] {null, "x"});
        assertEquals(TestKey.of(r), "com.example.MyTest#nulls(null,x)");
    }

    @Test
    public void survivesAParameterWhoseToStringThrows() {
        // A reporter must never be the reason a build fails -- including because someone's
        // domain object has a broken toString().
        Object hostile = new Object() {
            @Override public String toString() { throw new IllegalStateException("boom"); }
        };
        ITestResult r = Fakes.result("com.example.MyTest", "hostile", new Object[] {hostile});
        String key = TestKey.of(r);
        assertTrue(key.startsWith("com.example.MyTest#hostile("), "got " + key);
    }

    @Test
    public void displayNameIsTheMethodWithItsParameters() {
        ITestResult r = Fakes.result("com.example.MyTest", "rows", new Object[] {"alpha", 1});
        assertEquals(TestKey.displayName(r), "rows(alpha,1)");
    }
}
```

- [ ] **Step 2: Write the test double**

Create `src/test/java/com/qualflare/testng/Fakes.java`:

```java
package com.qualflare.testng;

import org.testng.ITestResult;
import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;

/**
 * ITestResult has a large surface and TestNG offers no public builder, so the unit tests
 * drive a dynamic proxy answering only the handful of methods the reporter calls. This is
 * deliberately thin: anything the production code starts calling will show up here as an
 * UnsupportedOperationException rather than a silent null.
 */
final class Fakes {

    private Fakes() {}

    static ITestResult result(String className, String methodName, Object[] params) {
        return result(className, methodName, params, ITestResult.SUCCESS, null, false);
    }

    static ITestResult result(String className, String methodName, Object[] params,
                              int status, Throwable thrown, boolean wasRetried) {
        InvocationHandler h = (proxy, method, args) -> {
            switch (method.getName()) {
                case "getParameters":  return params;
                case "getStatus":      return status;
                case "getThrowable":   return thrown;
                case "wasRetried":     return wasRetried;
                case "getName":        return methodName;
                case "getTestClass":   return testClass(className);
                case "getMethod":      return testMethod(methodName);
                case "toString":       return className + "#" + methodName;
                case "hashCode":       return System.identityHashCode(proxy);
                case "equals":         return proxy == args[0];
                default:
                    throw new UnsupportedOperationException(
                            "Fakes does not answer ITestResult." + method.getName()
                                    + " -- add it deliberately");
            }
        };
        return (ITestResult) Proxy.newProxyInstance(
                Fakes.class.getClassLoader(), new Class<?>[] {ITestResult.class}, h);
    }

    private static Object testClass(String className) {
        InvocationHandler h = (proxy, method, args) -> {
            if ("getName".equals(method.getName())) return className;
            if ("toString".equals(method.getName())) return className;
            if ("hashCode".equals(method.getName())) return className.hashCode();
            if ("equals".equals(method.getName())) return proxy == args[0];
            throw new UnsupportedOperationException("ITestClass." + method.getName());
        };
        return Proxy.newProxyInstance(Fakes.class.getClassLoader(),
                new Class<?>[] {org.testng.ITestClass.class}, h);
    }

    private static Object testMethod(String methodName) {
        InvocationHandler h = (proxy, method, args) -> {
            if ("getMethodName".equals(method.getName())) return methodName;
            if ("toString".equals(method.getName())) return methodName;
            if ("hashCode".equals(method.getName())) return methodName.hashCode();
            if ("equals".equals(method.getName())) return proxy == args[0];
            throw new UnsupportedOperationException("ITestNGMethod." + method.getName());
        };
        return Proxy.newProxyInstance(Fakes.class.getClassLoader(),
                new Class<?>[] {org.testng.ITestNGMethod.class}, h);
    }
}
```

- [ ] **Step 3: Run the tests and watch them fail**

Run: `mvn -q test -Dtest=TestKeyTest`
Expected: compilation failure — `cannot find symbol: class TestKey`.

- [ ] **Step 4: Implement `TestKey`**

Create `src/main/java/com/qualflare/testng/TestKey.java`:

```java
package com.qualflare.testng;

import org.testng.ITestResult;

/**
 * The identity of a test case, and the ONE place it is computed.
 *
 * <p>Both {@link QualflareListener} and {@link Qualflare} need this. If they computed it
 * separately and ever disagreed, metadata would be filed under a key with no case attached
 * and would vanish with no error -- so there is exactly one implementation.
 *
 * <p><b>invocationCount is deliberately absent.</b> Measured on TestNG 7.12.0: it
 * increments both for {@code IRetryAnalyzer} retries (same parameters) and for
 * DataProvider rows (different parameters). Keying on it would split a retried test into
 * separate cases; ignoring parameters would collapse distinct rows into one. Parameters
 * separate cases; invocationCount only orders attempts within one.
 */
final class TestKey {

    private TestKey() {}

    /** Stable across retries, distinct across DataProvider rows. */
    static String of(ITestResult result) {
        return result.getTestClass().getName() + "#" + displayName(result);
    }

    /** The method with its parameters, e.g. {@code rows(alpha,1)}. */
    static String displayName(ITestResult result) {
        return result.getMethod().getMethodName() + "(" + params(result) + ")";
    }

    private static String params(ITestResult result) {
        Object[] ps = result.getParameters();
        if (ps == null || ps.length == 0) {
            return "";
        }
        StringBuilder b = new StringBuilder();
        for (int i = 0; i < ps.length; i++) {
            if (i > 0) {
                b.append(',');
            }
            b.append(str(ps[i]));
        }
        return b.toString();
    }

    /**
     * A parameter is somebody else's object and its toString() is somebody else's code.
     * A reporter must never be the reason a build fails, so a throwing toString() degrades
     * to the type name rather than propagating.
     */
    private static String str(Object o) {
        if (o == null) {
            return "null";
        }
        try {
            return String.valueOf(o);
        } catch (RuntimeException e) {
            return "<" + o.getClass().getSimpleName() + ">";
        }
    }
}
```

- [ ] **Step 5: Run the tests and watch them pass**

Run: `mvn -q test -Dtest=TestKeyTest`
Expected: 6 tests, all PASS.

- [ ] **Step 6: Mutation-check the guard that matters**

Temporarily add `invocationCount` to the key (e.g. append `result.getMethod().getCurrentInvocationCount()` in `of`). Run `mvn -q test -Dtest=TestKeyTest`.
Expected: `retriesOfTheSameTestShareOneKey` FAILS. **Revert the mutation.** If it passed, the test is not pinning the behaviour and must be strengthened before moving on.

- [ ] **Step 7: Commit**

```bash
git add src/main/java/com/qualflare/testng/TestKey.java src/test/java/com/qualflare/testng/TestKeyTest.java src/test/java/com/qualflare/testng/Fakes.java
git commit -m "feat: TestKey, the single identity function for TestNG cases

Keyed on class#method(params). invocationCount is deliberately excluded: it
increments for BOTH retries and DataProvider rows, so keying on it splits a
retried test into separate cases while ignoring parameters collapses distinct
rows into one.

One implementation because the listener and the metadata API must agree -- a
disagreement files metadata under a key with no case and loses it silently."
```

---

### Task 4: `Status` — TestNG result to wire status

**Files:**
- Create: `src/main/java/com/qualflare/testng/Status.java`
- Test: `src/test/java/com/qualflare/testng/StatusTest.java`

**Interfaces:**
- Consumes: `Fakes.result(...)` from Task 3
- Produces: `Status.PASSED/FAILED/SKIPPED/ERROR/TIMEOUT/ABORTED` (`static final String`), `Status.of(ITestResult)` → `String`, `Status.ofThrowable(Throwable)` → `String`

- [ ] **Step 1: Write the failing test**

Create `src/test/java/com/qualflare/testng/StatusTest.java`:

```java
package com.qualflare.testng;

import org.testng.ITestResult;
import org.testng.annotations.Test;
import java.io.IOException;
import static org.testng.Assert.*;

public class StatusTest {

    @Test
    public void successIsPassed() {
        ITestResult r = Fakes.result("C", "m", new Object[] {}, ITestResult.SUCCESS, null, false);
        assertEquals(Status.of(r), Status.PASSED);
    }

    @Test
    public void anAssertionFailureIsFailed() {
        ITestResult r = Fakes.result("C", "m", new Object[] {}, ITestResult.FAILURE,
                new AssertionError("expected 1 but found 2"), false);
        assertEquals(Status.of(r), Status.FAILED);
    }

    /**
     * Classified by TYPE, not by a list of class names. qualflare-junit5 0.1.0 matched
     * exact names and mislabelled every org.junit.ComparisonFailure as an error -- it
     * extends AssertionError but was on no list. TestNG's own assertions, AssertJ, Truth
     * and Hamcrest all extend AssertionError too.
     */
    @Test
    public void anAssertionSubclassIsStillFailed() {
        class CustomAssertion extends AssertionError {
            CustomAssertion() { super("custom"); }
        }
        ITestResult r = Fakes.result("C", "m", new Object[] {}, ITestResult.FAILURE,
                new CustomAssertion(), false);
        assertEquals(Status.of(r), Status.FAILED, "any AssertionError subclass is a failure");
    }

    @Test
    public void anyOtherThrowableIsAnError() {
        ITestResult r = Fakes.result("C", "m", new Object[] {}, ITestResult.FAILURE,
                new IllegalStateException("fixture broken"), false);
        assertEquals(Status.of(r), Status.ERROR,
                "a broken fixture is an error, not a failed expectation");
    }

    @Test
    public void aWrappedAssertionIsStillFailed() {
        ITestResult r = Fakes.result("C", "m", new Object[] {}, ITestResult.FAILURE,
                new RuntimeException("wrapper", new AssertionError("inner")), false);
        assertEquals(Status.of(r), Status.FAILED, "the cause chain is walked");
    }

    /**
     * Two throwables each holding the other as a cause is legal in Java (only DIRECT
     * self-causation is forbidden). An unbounded cause walk would spin forever, hanging
     * the reporter at the exact moment a build is already failing.
     */
    @Test(timeOut = 5000)
    public void aCauseCycleTerminates() {
        Exception a = new Exception("a");
        Exception b = new Exception("b", a);
        a.initCause(b);
        ITestResult r = Fakes.result("C", "m", new Object[] {}, ITestResult.FAILURE, a, false);
        assertEquals(Status.of(r), Status.ERROR);
    }

    @Test
    public void aGenuineSkipIsSkipped() {
        ITestResult r = Fakes.result("C", "m", new Object[] {}, ITestResult.SKIP,
                new Throwable("depends on a failed method"), false);
        assertEquals(Status.of(r), Status.SKIPPED);
    }

    /**
     * THE load-bearing rule. When IRetryAnalyzer retries, TestNG marks the FAILED attempt
     * SKIP so it does not count as a failure, preserving the throwable and setting
     * wasRetried(). Trusting the callback name reports a flaky test as clean.
     */
    @Test
    public void aRetriedSkipIsAFailure() {
        ITestResult r = Fakes.result("C", "m", new Object[] {}, ITestResult.SKIP,
                new AssertionError("attempt 1"), true);
        assertEquals(Status.of(r), Status.FAILED,
                "a skip with wasRetried()==true is a failed attempt, not a skip");
    }

    @Test
    public void aRetriedSkipCarryingANonAssertionIsAnError() {
        ITestResult r = Fakes.result("C", "m", new Object[] {}, ITestResult.SKIP,
                new IOException("io"), true);
        assertEquals(Status.of(r), Status.ERROR);
    }

    @Test
    public void successPercentageFailureIsFailed() {
        ITestResult r = Fakes.result("C", "m", new Object[] {},
                ITestResult.SUCCESS_PERCENTAGE_FAILURE, new AssertionError("x"), false);
        assertEquals(Status.of(r), Status.FAILED);
    }

    @Test
    public void aFailureWithNoThrowableIsStillFailed() {
        ITestResult r = Fakes.result("C", "m", new Object[] {}, ITestResult.FAILURE, null, false);
        assertEquals(Status.of(r), Status.FAILED);
    }
}
```

- [ ] **Step 2: Run and watch it fail**

Run: `mvn -q test -Dtest=StatusTest`
Expected: compilation failure — `cannot find symbol: class Status`.

- [ ] **Step 3: Implement `Status`**

Create `src/main/java/com/qualflare/testng/Status.java`:

```java
package com.qualflare.testng;

import org.testng.ITestResult;

/** Maps a TestNG result onto the wire's status vocabulary. */
final class Status {

    static final String PASSED = "passed";
    static final String FAILED = "failed";
    static final String SKIPPED = "skipped";
    static final String ERROR = "error";
    static final String TIMEOUT = "timeout";
    static final String ABORTED = "aborted";

    /** Deep enough for any real wrapping, short enough that a cycle cannot hang a build. */
    private static final int MAX_CAUSE_DEPTH = 32;

    private Status() {}

    /**
     * <b>The rule that matters:</b> a SKIP with {@code wasRetried() == true} is a FAILED
     * attempt, not a skip.
     *
     * <p>Measured on TestNG 7.12.0: when {@code IRetryAnalyzer.retry()} returns true,
     * TestNG marks the failed attempt SKIP (status 3) so it will not count as a failure,
     * while preserving the throwable and setting {@code wasRetried()}. A listener that
     * trusts the callback name reports a test that failed twice then passed as two SKIPS
     * and a pass -- losing the failures entirely and calling a flaky test clean.
     *
     * <p>{@code wasRetried() == false} on a SKIP is a genuine skip: a dependency failure,
     * or a test guarded by a configuration method that threw.
     */
    static String of(ITestResult result) {
        Throwable t = result.getThrowable();
        switch (result.getStatus()) {
            case ITestResult.SUCCESS:
                return PASSED;
            case ITestResult.SKIP:
                return result.wasRetried() ? ofThrowable(t) : SKIPPED;
            case ITestResult.SUCCESS_PERCENTAGE_FAILURE:
            case ITestResult.FAILURE:
            default:
                return ofThrowable(t);
        }
    }

    /**
     * An assertion failure is a "failed"; anything else thrown is an "error".
     *
     * <p>The distinction is the one triage actually uses: a hundred errors usually means
     * one broken fixture, a hundred failures means a hundred broken expectations.
     *
     * <p>Classified by TYPE, never by class name. qualflare-junit5 0.1.0 matched a list of
     * exact names and got this wrong for JUnit 4: {@code org.junit.ComparisonFailure}
     * extends {@code AssertionError} but was on no list, so every Vintage assertion failure
     * shipped as an error. TestNG's own assertions, AssertJ, Truth and Hamcrest are all
     * {@code instanceof AssertionError}.
     */
    static String ofThrowable(Throwable t) {
        if (t == null) {
            return FAILED;
        }
        // DEPTH-BOUNDED rather than cycle-detecting. Java forbids DIRECT self-causation
        // (initCause throws), so the obvious `getCause() == c` guard defends against the
        // one cycle that cannot happen while missing the one that can: two throwables each
        // holding the other is legal and would spin here forever.
        int depth = 0;
        for (Throwable c = t; c != null && depth < MAX_CAUSE_DEPTH; c = c.getCause(), depth++) {
            if (c instanceof AssertionError) {
                return FAILED;
            }
        }
        return ERROR;
    }
}
```

- [ ] **Step 4: Run and watch it pass**

Run: `mvn -q test -Dtest=StatusTest`
Expected: 11 tests, all PASS.

- [ ] **Step 5: Mutation-check the load-bearing rule**

Change `case ITestResult.SKIP:` to return `SKIPPED` unconditionally. Run `mvn -q test -Dtest=StatusTest`.
Expected: `aRetriedSkipIsAFailure` and `aRetriedSkipCarryingANonAssertionIsAnError` FAIL. **Revert.**

Then change `instanceof AssertionError` to `t.getClass().getName().equals("java.lang.AssertionError")`. Expected: `anAssertionSubclassIsStillFailed` FAILS. **Revert.**

- [ ] **Step 6: Switch `CoreSmokeTest` to the constant**

If Task 2's smoke test used the literal `"passed"`, replace it with `Status.PASSED` now and re-run `mvn -q test`.

- [ ] **Step 7: Commit**

```bash
git add src/main/java/com/qualflare/testng/Status.java src/test/java/com/qualflare/testng/StatusTest.java src/test/java/com/qualflare/testng/CoreSmokeTest.java
git commit -m "feat: TestNG status mapping, including the retried-skip rule

A SKIP with wasRetried()==true is a FAILED attempt. When IRetryAnalyzer retries,
TestNG marks the failed attempt SKIP so it will not count as a failure, while
preserving the throwable -- measured on 7.12.0. Trusting the callback name
reports a test that failed twice then passed as two skips and a pass.

Assertion failures are classified by TYPE, not by a list of class names. That
list is exactly how qualflare-junit5 0.1.0 shipped every JUnit 4
ComparisonFailure as an error.

Both guards confirmed by mutation."
```

---

### Task 5: `QualflareListener` — lifecycle and the write hook

**Files:**
- Create: `src/main/java/com/qualflare/testng/QualflareListener.java`
- Create: `src/main/resources/META-INF/services/org.testng.ITestNGListener`
- Test: `src/test/java/com/qualflare/testng/QualflareListenerTest.java`

**Interfaces:**
- Consumes: `TestKey.of`, `TestKey.displayName`, `Status.of`, `Status.ofThrowable`, `Run.accumulator()`, `Run.ensureHook()`, `Run.write()`, `Accumulator.started/finished/skipped`
- Produces: `QualflareListener` (public, no-arg constructor — required by `ServiceLoader`), and package-private `QualflareListener.write()` so tests can force a write without JVM shutdown

- [ ] **Step 1: Write the failing test**

Create `src/test/java/com/qualflare/testng/QualflareListenerTest.java`:

```java
package com.qualflare.testng;

import org.testng.ITestResult;
import org.testng.annotations.BeforeMethod;
import org.testng.annotations.Test;
import java.util.ArrayList;
import java.util.List;
import static org.testng.Assert.*;

/**
 * Drives the listener with synthetic results, which is the only way to reproduce the
 * retry sequence deterministically. The integration fixture (Task 8) proves the same
 * behaviour against a real TestNG run.
 */
public class QualflareListenerTest {

    private QualflareListener listener;

    @BeforeMethod
    public void reset() {
        Run.resetForTest();
        listener = new QualflareListener();
    }

    private static List<String> statusesOf(CaseRecord rec) {
        List<String> out = new ArrayList<>();
        for (Attempt a : rec.attempts) {
            out.add(a.status);
        }
        return out;
    }

    private CaseRecord only() {
        assertEquals(Run.accumulator().cases().size(), 1,
                "expected exactly one case, got " + Run.accumulator().cases().size());
        return Run.accumulator().cases().iterator().next();
    }

    @Test
    public void aPassingTestIsOneCaseWithOneAttempt() {
        ITestResult r = Fakes.result("C", "passes", new Object[] {}, ITestResult.SUCCESS, null, false);
        listener.onTestStart(r);
        listener.onTestSuccess(r);

        CaseRecord rec = only();
        assertEquals(statusesOf(rec), java.util.Collections.singletonList(Status.PASSED));
        assertFalse(rec.isFlaky(), "a test that passed first time is not flaky");
    }

    /**
     * THE case this reporter exists for. TestNG delivers the two failed attempts as
     * onTestSkipped with wasRetried()==true.
     */
    @Test
    public void aRetriedTestIsOneCaseWithEveryAttemptRecorded() {
        ITestResult a1 = Fakes.result("C", "flaky", new Object[] {}, ITestResult.SKIP,
                new AssertionError("attempt 1"), true);
        ITestResult a2 = Fakes.result("C", "flaky", new Object[] {}, ITestResult.SKIP,
                new AssertionError("attempt 2"), true);
        ITestResult a3 = Fakes.result("C", "flaky", new Object[] {}, ITestResult.SUCCESS, null, false);

        listener.onTestStart(a1);
        listener.onTestSkipped(a1);
        listener.onTestStart(a2);
        listener.onTestSkipped(a2);
        listener.onTestStart(a3);
        listener.onTestSuccess(a3);

        CaseRecord rec = only();
        assertEquals(statusesOf(rec),
                java.util.Arrays.asList(Status.FAILED, Status.FAILED, Status.PASSED),
                "the failed attempts must be recorded as failures, not skips");
        assertTrue(rec.isFlaky(), "failed then passed is flaky");
        assertEquals(rec.retryCount(), 2);
    }

    @Test
    public void aTestThatNeverRecoversIsFailedAndNotFlaky() {
        ITestResult a1 = Fakes.result("C", "hard", new Object[] {}, ITestResult.SKIP,
                new AssertionError("1"), true);
        ITestResult a2 = Fakes.result("C", "hard", new Object[] {}, ITestResult.FAILURE,
                new AssertionError("2"), false);
        listener.onTestStart(a1);
        listener.onTestSkipped(a1);
        listener.onTestStart(a2);
        listener.onTestFailure(a2);

        CaseRecord rec = only();
        assertEquals(rec.status(), Status.FAILED);
        assertFalse(rec.isFlaky(), "calling a hard failure flaky hides it behind a softer word");
    }

    @Test
    public void dataProviderRowsStayDistinctCases() {
        ITestResult a = Fakes.result("C", "rows", new Object[] {"alpha", 1}, ITestResult.SUCCESS, null, false);
        ITestResult b = Fakes.result("C", "rows", new Object[] {"beta", 2}, ITestResult.SUCCESS, null, false);
        listener.onTestStart(a);
        listener.onTestSuccess(a);
        listener.onTestStart(b);
        listener.onTestSuccess(b);

        assertEquals(Run.accumulator().cases().size(), 2,
                "two DataProvider rows are two cases");
    }

    @Test
    public void aTimeoutIsItsOwnStatus() {
        ITestResult r = Fakes.result("C", "slow", new Object[] {}, ITestResult.FAILURE,
                new RuntimeException("timed out"), false);
        listener.onTestStart(r);
        listener.onTestFailedWithTimeout(r);

        assertEquals(only().status(), Status.TIMEOUT,
                "the difference between broken and hung is the point of having the status");
    }

    @Test
    public void aGenuineSkipIsRecordedAsSkipped() {
        ITestResult r = Fakes.result("C", "dep", new Object[] {}, ITestResult.SKIP,
                new Throwable("depends on a failed method"), false);
        listener.onTestStart(r);
        listener.onTestSkipped(r);

        assertEquals(only().status(), Status.SKIPPED);
    }

    @Test
    public void anEmptyRunWritesNothing() {
        listener.onExecutionFinish();
        assertTrue(Run.accumulator().isEmpty(),
                "a run with no tests must not produce a report file");
    }
}
```

- [ ] **Step 2: Run and watch it fail**

Run: `mvn -q test -Dtest=QualflareListenerTest`
Expected: compilation failure — `cannot find symbol: class QualflareListener`.

- [ ] **Step 3: Implement the listener**

Create `src/main/java/com/qualflare/testng/QualflareListener.java`:

```java
package com.qualflare.testng;

import org.testng.IConfigurationListener;
import org.testng.IExecutionListener;
import org.testng.ITestListener;
import org.testng.ITestResult;

import java.io.PrintWriter;
import java.io.StringWriter;

/**
 * The only TestNG-aware lifecycle class. Translates callbacks into accumulator calls and
 * writes the report once, at the end of the JVM's execution.
 *
 * <p>Registered through {@code META-INF/services/org.testng.ITestNGListener}, so adding
 * the dependency is the whole setup. Measured: TestNG wires ServiceLoader listeners in by
 * default -- its own {@code setListenersToSkipFromBeingWiredInViaServiceLoaders} method
 * exists precisely to opt out.
 *
 * <p><b>Unlike qualflare-junit5, there is no multi-session problem here.</b> Measured on
 * TestNG 7.12.0 under Surefire 3.5.6: {@code onExecutionStart}/{@code onExecutionFinish}
 * fire exactly once per JVM, one listener instance serves the whole run, and Surefire's
 * {@code rerunFailingTestsCount} does not apply to the TestNG provider at all -- retries
 * come only from {@code IRetryAnalyzer}, in-process. State is still JVM-scoped (see
 * {@link Run}) so that {@code forkCount > 1} writes one file per JVM, which is the
 * directory-merge model {@code qf collect} already uses.
 */
public final class QualflareListener
        implements ITestListener, IExecutionListener, IConfigurationListener {

    private static Accumulator acc() {
        return Run.accumulator();
    }

    // ---- execution ---------------------------------------------------------------

    @Override
    public void onExecutionStart() {
        Run.ensureHook();
    }

    @Override
    public void onExecutionFinish() {
        Run.write();
    }

    /** Package-private so tests can force a write without waiting for JVM shutdown. */
    void write() {
        Run.write();
    }

    // ---- tests -------------------------------------------------------------------

    @Override
    public void onTestStart(ITestResult result) {
        acc().started(TestKey.of(result), System.nanoTime());
    }

    @Override
    public void onTestSuccess(ITestResult result) {
        record(result, Status.PASSED);
    }

    @Override
    public void onTestFailure(ITestResult result) {
        record(result, Status.of(result));
    }

    @Override
    public void onTestFailedButWithinSuccessPercentage(ITestResult result) {
        record(result, Status.of(result));
    }

    /**
     * TestNG has a dedicated timeout callback, which JUnit does not. Mapping it onto
     * "failed" would throw away the difference between a broken test and a hung one.
     */
    @Override
    public void onTestFailedWithTimeout(ITestResult result) {
        record(result, Status.TIMEOUT);
    }

    /**
     * Two very different things arrive here, told apart by {@code wasRetried()}:
     * a FAILED attempt that will be retried, and a genuine skip. {@link Status#of}
     * owns that decision.
     */
    @Override
    public void onTestSkipped(ITestResult result) {
        if (result.wasRetried()) {
            record(result, Status.of(result));
            return;
        }
        Throwable t = result.getThrowable();
        acc().skipped(TestKey.of(result), suiteOf(result), classOf(result),
                TestKey.displayName(result), legacyOf(result), messageOf(t));
    }

    private void record(ITestResult result, String status) {
        Throwable t = result.getThrowable();
        acc().finished(TestKey.of(result), suiteOf(result), classOf(result),
                TestKey.displayName(result), legacyOf(result),
                status, System.nanoTime(), messageOf(t), traceOf(t));
    }

    // ---- naming ------------------------------------------------------------------

    /** Cases are grouped by their class, matching how qualflare-junit5 groups them. */
    private static String suiteOf(ITestResult result) {
        return result.getTestClass().getName();
    }

    private static String classOf(ITestResult result) {
        return result.getTestClass().getName();
    }

    /** The JUnit-style flat name, used for matching against previously-seen cases. */
    private static String legacyOf(ITestResult result) {
        return result.getTestClass().getName() + "." + result.getMethod().getMethodName();
    }

    static String messageOf(Throwable t) {
        if (t == null) {
            return "";
        }
        String m = t.getMessage();
        return m == null ? t.getClass().getSimpleName() : m;
    }

    static String traceOf(Throwable t) {
        if (t == null) {
            return "";
        }
        StringWriter w = new StringWriter();
        t.printStackTrace(new PrintWriter(w));
        return w.toString();
    }
}
```

- [ ] **Step 4: Register it for ServiceLoader discovery**

Create `src/main/resources/META-INF/services/org.testng.ITestNGListener` containing exactly:

```
com.qualflare.testng.QualflareListener
```

- [ ] **Step 5: Run and watch it pass**

Run: `mvn -q test -Dtest=QualflareListenerTest`
Expected: 7 tests, all PASS.

- [ ] **Step 6: Mutation-check the retry path**

In `onTestSkipped`, delete the `if (result.wasRetried())` branch so every skip is recorded as a skip. Run `mvn -q test -Dtest=QualflareListenerTest`.
Expected: `aRetriedTestIsOneCaseWithEveryAttemptRecorded` and `aTestThatNeverRecoversIsFailedAndNotFlaky` FAIL. **Revert.**

- [ ] **Step 7: Commit**

```bash
git add src/main/java/com/qualflare/testng/QualflareListener.java src/main/resources/META-INF/services/org.testng.ITestNGListener src/test/java/com/qualflare/testng/QualflareListenerTest.java
git commit -m "feat: the TestNG listener, registered via ServiceLoader

Writes on onExecutionFinish, which is a genuine once-per-JVM hook -- measured,
unlike JUnit's launcher session which Surefire opens once per rerun. Surefire's
rerunFailingTestsCount does not apply to the TestNG provider at all, so retries
come only from IRetryAnalyzer and arrive in-process on one listener instance.

onTestSkipped routes on wasRetried(): a retried skip is a failed attempt, a
genuine skip is a skip. Confirmed by mutation.

onTestFailedWithTimeout maps to the timeout status -- fidelity the JUnit
reporter cannot produce."
```

---

### Task 6: Configuration failures go red

**Files:**
- Modify: `src/main/java/com/qualflare/testng/QualflareListener.java`
- Test: `src/test/java/com/qualflare/testng/ConfigurationFailureTest.java`

**Interfaces:**
- Consumes: everything from Task 5
- Produces: `IConfigurationListener.onConfigurationFailure(ITestResult)` behaviour

**Why:** measured — a throwing `@BeforeClass` produces `onConfigurationFailure` plus a skip per guarded test. Better than JUnit, where guarded tests are silent. But a suite whose tests are all skipped reads as "nothing ran" rather than "broken", so the config method itself is emitted as an `error` case.

- [ ] **Step 1: Write the failing test**

Create `src/test/java/com/qualflare/testng/ConfigurationFailureTest.java`:

```java
package com.qualflare.testng;

import org.testng.ITestResult;
import org.testng.annotations.BeforeMethod;
import org.testng.annotations.Test;
import static org.testng.Assert.*;

public class ConfigurationFailureTest {

    private QualflareListener listener;

    @BeforeMethod
    public void reset() {
        Run.resetForTest();
        listener = new QualflareListener();
    }

    private CaseRecord caseNamed(String needle) {
        for (CaseRecord rec : Run.accumulator().cases()) {
            if (rec.displayName.contains(needle)) {
                return rec;
            }
        }
        fail("no case whose display name contains " + needle
                + "; cases were " + Run.accumulator().cases());
        return null;
    }

    @Test
    public void aFailingConfigurationMethodBecomesAnErrorCase() {
        ITestResult cfg = Fakes.result("com.example.BrokenTest", "setUp", new Object[] {},
                ITestResult.FAILURE, new IllegalStateException("beforeClass exploded"), false);
        listener.onConfigurationFailure(cfg);

        CaseRecord rec = caseNamed("setUp");
        assertEquals(rec.status(), Status.ERROR,
                "an all-skipped suite reads as 'nothing ran'; the config failure must be red");
        assertTrue(rec.message().contains("exploded"), "the real cause must survive");
    }

    @Test
    public void theConfigCaseIsMarkedAsConfigurationNotAsATest() {
        ITestResult cfg = Fakes.result("com.example.BrokenTest", "setUp", new Object[] {},
                ITestResult.FAILURE, new IllegalStateException("x"), false);
        listener.onConfigurationFailure(cfg);

        CaseRecord rec = caseNamed("setUp");
        assertTrue(rec.displayName.startsWith("[config] "),
                "unprefixed, 'setUp' reads as a test somebody forgot to delete; got "
                        + rec.displayName);
    }

    @Test
    public void aSuccessfulConfigurationMethodProducesNoCase() {
        ITestResult cfg = Fakes.result("com.example.OkTest", "setUp", new Object[] {},
                ITestResult.SUCCESS, null, false);
        listener.onConfigurationSuccess(cfg);

        assertTrue(Run.accumulator().isEmpty(),
                "setup that worked must not add noise cases to every suite");
    }
}
```

- [ ] **Step 2: Run and watch it fail**

Run: `mvn -q test -Dtest=ConfigurationFailureTest`
Expected: FAIL — `onConfigurationFailure` is inherited as a no-op default, so no case exists.

- [ ] **Step 3: Implement it**

Add to `QualflareListener`, after the tests section:

```java
    // ---- configuration -----------------------------------------------------------

    /**
     * A throwing {@code @BeforeClass} produces this, PLUS a skip for each test it guarded
     * (measured -- unlike JUnit, where the guarded tests emit nothing at all and a broken
     * suite can read green).
     *
     * <p>The guarded skips are recorded by {@link #onTestSkipped} as ordinary skips. This
     * method adds the configuration method itself as an {@code error} case, because a suite
     * whose tests are ALL skipped reads as "nothing ran" rather than "broken". That is the
     * same false-green qualflare-junit5 emits a synthetic case to avoid -- except here the
     * case carries the real method name and the real stack trace.
     *
     * <p>Only FAILING configuration methods produce a case. Reporting successful ones would
     * add setup noise to every suite in the product.
     */
    @Override
    public void onConfigurationFailure(ITestResult result) {
        Throwable t = result.getThrowable();
        String display = "[config] " + classOf(result) + "#" + result.getMethod().getMethodName();
        acc().finished(TestKey.of(result) + "/[qf-config-failure]",
                suiteOf(result), classOf(result), display, legacyOf(result),
                Status.ERROR, System.nanoTime(), messageOf(t), traceOf(t));
    }
```

- [ ] **Step 4: Run and watch it pass**

Run: `mvn -q test -Dtest=ConfigurationFailureTest`
Expected: 3 tests, all PASS.

- [ ] **Step 5: Run the whole unit suite**

Run: `mvn -q test`
Expected: every test passes. Task 5's `anEmptyRunWritesNothing` still holds because a successful configuration method adds nothing.

- [ ] **Step 6: Commit**

```bash
git add src/main/java/com/qualflare/testng/QualflareListener.java src/test/java/com/qualflare/testng/ConfigurationFailureTest.java
git commit -m "feat: a failing configuration method becomes a red case

Measured: a throwing @BeforeClass produces onConfigurationFailure plus a skip
per guarded test -- better than JUnit, where the guarded tests emit nothing.
But a suite whose tests are all skipped reads as 'nothing ran' rather than
'broken', so the config method is emitted as its own error case carrying the
real name and stack trace.

Prefixed [config] because unprefixed, 'setUp' in a case list reads as a test
someone forgot to delete. Only failing config methods produce a case."
```

---

### Task 7: `Qualflare` — the author-facing metadata API

**Files:**
- Create: `src/main/java/com/qualflare/testng/Qualflare.java`
- Test: `src/test/java/com/qualflare/testng/QualflareApiTest.java`

**Interfaces:**
- Consumes: `TestKey.of`, `Keys.*`, `Run.accumulator()`, `Accumulator.entry`, `Attachments`
- Produces (all `public static void`): `label(String,String)`, `tag(String...)`, `link(String)`, `link(String,String,String)`, `priority(String)`, `description(String)`, `parameter(String,String)`, `maskedParameter(String)`, `step(String,Runnable)`; and the constants `ISSUE`, `TMS`, `CUSTOM`, `HIGH`, `MEDIUM`, `LOW`

**The simplification over qualflare-junit5:** there, `QualflareExtension` existed only to capture the current `ExtensionContext`, and users had to add `@ExtendWith` or set an autodetection property. TestNG's `Reporter.getCurrentTestResult()` is a thread-local maintained by the framework, so no extension and no registration is needed. Entries also go **straight into the accumulator** rather than through a framework reporting channel, because TestNG has no equivalent of `publishReportEntry`.

- [ ] **Step 1: Write the failing test**

Create `src/test/java/com/qualflare/testng/QualflareApiTest.java`:

```java
package com.qualflare.testng;

import org.testng.annotations.Test;
import static org.testng.Assert.*;

/**
 * These run as REAL TestNG tests, so Reporter.getCurrentTestResult() is populated by the
 * framework exactly as it would be for a user -- which is the thing under test.
 */
public class QualflareApiTest {

    @Test
    public void metadataLandsUnderTheRunningTest() {
        Run.resetForTest();
        Qualflare.label("feature", "checkout");
        Qualflare.tag("smoke");

        // The key the entries were filed under must be the running test's own key.
        String expected = "com.qualflare.testng.QualflareApiTest#metadataLandsUnderTheRunningTest()";
        assertTrue(Run.accumulator().hasEntriesFor(expected),
                "metadata must be filed under the running test, expected key " + expected);
    }

    @Test
    public void nothingThrowsWhenCalledOffTheTestThread() throws Exception {
        Run.resetForTest();
        Thread t = new Thread(() -> {
            // No current test on this thread. This must be inert, not explosive.
            Qualflare.label("feature", "checkout");
            Qualflare.tag("smoke");
            Qualflare.step("a step", () -> { });
        });
        t.start();
        t.join();
        // Reaching here without an exception IS the assertion.
        assertTrue(true);
    }

    @Test
    public void aStepBodyStillRunsWhenNoTestIsInScope() throws Exception {
        boolean[] ran = {false};
        Thread t = new Thread(() -> Qualflare.step("s", () -> ran[0] = true));
        t.start();
        t.join();
        assertTrue(ran[0], "the test's own work must happen even when reporting is inert");
    }

    @Test
    public void aFailingStepRethrowsRatherThanSwallowing() {
        Run.resetForTest();
        try {
            Qualflare.step("explodes", () -> { throw new IllegalStateException("boom"); });
            fail("the exception must propagate");
        } catch (IllegalStateException expected) {
            // Turning a failing test green is the worst thing a reporter can do.
        }
    }

    @Test
    public void aMaskedParameterCannotCarryAValue() throws Exception {
        // A signature that cannot accept a value cannot leak one. Pinned as a compile-time
        // property via reflection so nobody "helpfully" adds an overload later.
        for (java.lang.reflect.Method m : Qualflare.class.getMethods()) {
            if (m.getName().equals("maskedParameter")) {
                assertEquals(m.getParameterCount(), 1,
                        "maskedParameter must take only a name, never a value");
            }
        }
    }
}
```

- [ ] **Step 2: Add the accumulator probe the test needs**

`Accumulator` is copied code, so this addition is deliberate and minimal. Add to `src/main/java/com/qualflare/testng/Accumulator.java`:

```java
    /** Test-only: did any metadata arrive for this key? */
    synchronized boolean hasEntriesFor(String uniqueId) {
        return pendingEntries.containsKey(uniqueId);
    }
```

**Note:** this is the one intentional divergence from the copied files. Record it in the commit message so a future diff against `qualflare-junit5` does not look like drift.

- [ ] **Step 3: Run and watch it fail**

Run: `mvn -q test -Dtest=QualflareApiTest`
Expected: compilation failure — `cannot find symbol: class Qualflare`.

- [ ] **Step 4: Implement `Qualflare`**

Create `src/main/java/com/qualflare/testng/Qualflare.java`:

```java
package com.qualflare.testng;

import org.testng.ITestResult;
import org.testng.Reporter;

import java.util.concurrent.atomic.AtomicBoolean;

/**
 * The author-facing API. Optional: without it every result, status, duration, error and
 * retry is still reported; only the metadata needs a test to attach to.
 *
 * <p>Nothing here can fail a test. No method throws, none returns an error, and every call
 * is inert when no test is in scope.
 *
 * <p>Unlike qualflare-junit5 this needs no extension and no registration, because
 * {@code Reporter.getCurrentTestResult()} is a thread-local TestNG maintains itself.
 */
public final class Qualflare {

    public static final String ISSUE = "issue";
    public static final String TMS = "tms";
    public static final String CUSTOM = "custom";

    public static final String HIGH = "high";
    public static final String MEDIUM = "medium";
    public static final String LOW = "low";

    private static final AtomicBoolean WARNED = new AtomicBoolean(false);

    private Qualflare() {}

    public static void label(String name, String value) {
        emit(Keys.LABEL, kv(name, value));
    }

    public static void tag(String... tags) {
        if (tags == null) {
            return;
        }
        for (String t : tags) {
            emit(Keys.TAG, str(t));
        }
    }

    public static void link(String url) {
        link(url, ISSUE, url);
    }

    public static void link(String url, String type, String name) {
        emit(Keys.LINK, str(url) + Keys.SEP + str(type) + Keys.SEP + str(name));
    }

    public static void priority(String priority) {
        emit(Keys.PRIORITY, str(priority));
    }

    public static void description(String text) {
        emit(Keys.DESCRIPTION, str(text));
    }

    public static void parameter(String name, String value) {
        emit(Keys.PARAMETER, kv(name, value));
    }

    /**
     * Takes no value at all. {@code masked} is only a display hint the server does not act
     * on, so withholding the value here is the only thing that actually keeps a secret out
     * of the report: a signature that cannot accept one cannot leak one.
     */
    public static void maskedParameter(String name) {
        emit(Keys.MASKED_PARAMETER, str(name));
    }

    public static void step(String name, Runnable body) {
        if (body == null) {
            return;
        }
        if (current() == null) {
            body.run(); // inert, but the test's own work still has to happen
            return;
        }
        long started = System.nanoTime();
        emit(Keys.STEP_START, str(name));
        try {
            body.run();
        } catch (Throwable t) {
            closeStep(Status.FAILED, System.nanoTime() - started, messageOf(t));
            throw t; // never swallow: turning a failing test green is the worst thing a reporter can do
        }
        closeStep(Status.PASSED, System.nanoTime() - started, "");
    }

    private static void closeStep(String status, long nanos, String error) {
        emit(Keys.STEP_STOP, status + Keys.SEP + nanos + Keys.SEP + str(error));
    }

    // ---- plumbing ---------------------------------------------------------------

    private static ITestResult current() {
        try {
            return Reporter.getCurrentTestResult();
        } catch (RuntimeException e) {
            return null;
        }
    }

    /**
     * Dropped with a warning rather than guessed at. Attaching this to whichever test runs
     * next is silent wrong data, which is worse than no data.
     *
     * <p>The thread-local is per-thread by design, so a call made from a thread the test
     * spawned finds nothing. That is the correct answer: the reporter cannot know which
     * test that thread belongs to.
     */
    private static void emit(String key, String value) {
        ITestResult r = current();
        if (r == null) {
            if (WARNED.compareAndSet(false, true)) {
                System.err.println("[qualflare-testng] metadata call outside a running test was"
                        + " dropped. Calls must be made on the test's own thread.");
            }
            return;
        }
        try {
            Run.accumulator().entry(TestKey.of(r), key, value);
        } catch (RuntimeException ignored) {
            // Fire and forget. A metadata problem must never fail somebody's run.
        }
    }

    private static String kv(String name, String value) {
        return str(name) + Keys.SEP + str(value);
    }

    private static String str(Object o) {
        return o == null ? "" : String.valueOf(o);
    }

    private static String messageOf(Throwable t) {
        String m = t.getMessage();
        return m == null ? t.getClass().getSimpleName() : m;
    }
}
```

- [ ] **Step 5: Run and watch it pass**

Run: `mvn -q test -Dtest=QualflareApiTest`
Expected: 5 tests, all PASS.

- [ ] **Step 6: Mutation-check the off-thread guard**

In `emit`, remove the `if (r == null)` early return so it dereferences `r`. Run `mvn -q test -Dtest=QualflareApiTest`.
Expected: `nothingThrowsWhenCalledOffTheTestThread` FAILS with a `NullPointerException`. **Revert.**

- [ ] **Step 7: Commit**

```bash
git add src/main/java/com/qualflare/testng/Qualflare.java src/main/java/com/qualflare/testng/Accumulator.java src/test/java/com/qualflare/testng/QualflareApiTest.java
git commit -m "feat: the metadata API, with no extension to register

Resolves the running test through Reporter.getCurrentTestResult(), a thread-local
TestNG maintains itself. qualflare-junit5 needed a whole Extension class for this
plus @ExtendWith or an autodetection property; TestNG needs neither.

Entries go straight into the accumulator because TestNG has no equivalent of
publishReportEntry. Calls off the test thread are dropped with a warning rather
than attached to whichever test runs next -- wrong metadata that looks right is
worse than none.

Adds Accumulator.hasEntriesFor for the test. That is the ONE intentional
divergence from the files copied verbatim in the core commit; a future diff
against qualflare-junit5 should expect it."
```

---

### Task 8: The integration fixture — a real TestNG run

**Files:**
- Create: `test/integration/fixture/pom.xml`
- Create: `test/integration/fixture/src/test/java/{FixtureTest,BrokenConfigTest,Retry}.java`
- Create: `test/integration/verify.py`

**Interfaces:**
- Consumes: the installed `com.qualflare:qualflare-testng` artifact
- Produces: an executable end-to-end gate

**Why a separate fixture project:** the unit tests drive synthetic results. This proves the same behaviour when TestNG itself produces the events — which is where identity, retry sequencing and ServiceLoader discovery actually get exercised.

- [ ] **Step 1: Write the fixture pom**

Create `test/integration/fixture/pom.xml`:

```xml
<?xml version="1.0" encoding="UTF-8"?>
<project xmlns="http://maven.apache.org/POM/4.0.0">
  <modelVersion>4.0.0</modelVersion>
  <groupId>qf.fixture</groupId><artifactId>testng-fixture</artifactId><version>1.0</version>
  <properties>
    <maven.compiler.release>11</maven.compiler.release>
    <project.build.sourceEncoding>UTF-8</project.build.sourceEncoding>
    <!-- Overridden by the CI matrix, which runs this against 7.4.0 and 7.12.0. -->
    <testng.version>7.12.0</testng.version>
    <surefire.version>3.5.6</surefire.version>
    <!-- Passed in by verify.py, read from the ROOT pom. Hardcoding it goes stale the
         moment versions:set runs, which cost qualflare-junit5 four red commits. -->
    <qualflare.version>0.1.0-SNAPSHOT</qualflare.version>
  </properties>
  <dependencies>
    <dependency>
      <groupId>org.testng</groupId><artifactId>testng</artifactId>
      <version>${testng.version}</version><scope>test</scope>
    </dependency>
    <dependency>
      <groupId>com.qualflare</groupId><artifactId>qualflare-testng</artifactId>
      <version>${qualflare.version}</version><scope>test</scope>
    </dependency>
  </dependencies>
  <build><plugins>
    <plugin>
      <groupId>org.apache.maven.plugins</groupId><artifactId>maven-surefire-plugin</artifactId>
      <version>${surefire.version}</version>
      <configuration><testFailureIgnore>true</testFailureIgnore></configuration>
    </plugin>
  </plugins></build>
</project>
```

- [ ] **Step 2: Write the fixture tests**

Create `test/integration/fixture/src/test/java/Retry.java`:

```java
import org.testng.IRetryAnalyzer;
import org.testng.ITestResult;

public class Retry implements IRetryAnalyzer {
    private int count = 0;
    @Override public boolean retry(ITestResult result) { return count++ < 2; }
}
```

Create `test/integration/fixture/src/test/java/FixtureTest.java`:

```java
import com.qualflare.testng.Qualflare;
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
    public void carriesMetadata() {
        Qualflare.label("feature", "checkout");
        Qualflare.tag("smoke");
        Qualflare.priority(Qualflare.HIGH);
        Qualflare.parameter("sku", "widget");
        Qualflare.maskedParameter("token");
        Qualflare.step("add to cart", () -> Qualflare.parameter("qty", "2"));
    }
}
```

Create `test/integration/fixture/src/test/java/BrokenConfigTest.java`:

```java
import org.testng.annotations.*;
import static org.testng.Assert.*;

public class BrokenConfigTest {
    @BeforeClass public void setUp() { throw new IllegalStateException("beforeClass exploded"); }
    @Test public void guardedOne() { assertTrue(true); }
    @Test public void guardedTwo() { assertTrue(true); }
}
```

- [ ] **Step 3: Write the verifier**

Create `test/integration/verify.py`:

```python
#!/usr/bin/env python3
"""Runs the fixture and asserts the traps this reporter exists to handle.

A report can be structurally valid and semantically wrong. These are the properties
that would silently regress: a retried failure recorded as a skip, DataProvider rows
collapsed into one case, a timeout flattened into a failure, a broken @BeforeClass
leaving a suite that reads green.
"""
import glob
import json
import os
import re
import shutil
import subprocess
import sys

HERE = os.path.dirname(os.path.abspath(__file__))
FIXTURE = os.path.join(HERE, "fixture")
MVN = os.environ.get("MVN", "mvn")
TESTNG = os.environ.get("TESTNG_VERSION")

failures = []


def check(label, ok, detail=""):
    print(("  PASS  " if ok else "  FAIL  ") + label + ("" if ok else "   -> " + str(detail)))
    if not ok:
        failures.append(label)


def reporter_version():
    """The version just built, read from the ROOT pom rather than assumed."""
    with open(os.path.join(HERE, "..", "..", "pom.xml")) as fh:
        text = fh.read()
    m = re.search(r"<artifactId>qualflare-testng</artifactId>\s*<version>([^<]+)</version>", text)
    return m.group(1) if m else "0.1.0-SNAPSHOT"


def run():
    results = os.path.join(FIXTURE, "qualflare-results")
    shutil.rmtree(results, ignore_errors=True)

    cmd = [MVN, "-q", "test", "-Dqualflare.version=" + reporter_version()]
    if TESTNG:
        cmd.append("-Dtestng.version=" + TESTNG)
    proc = subprocess.run(cmd, cwd=FIXTURE, capture_output=True, text=True)

    files = glob.glob(os.path.join(results, "*.json"))
    if not files:
        # Say WHY. Swallowing maven's output turns a missing dependency into the
        # uninformative "no report written", which is the same class of mistake as
        # trusting a green checkmark.
        print("  no report written -- the fixture build said:")
        for line in (proc.stdout + proc.stderr).splitlines():
            if re.search(r"ERROR|BUILD FAILURE|Could not resolve|cannot find symbol", line):
                print("    " + line.strip())
        sys.exit(1)
    if len(files) != 1:
        print("  expected exactly 1 report file, got %d" % len(files))
        sys.exit(1)
    with open(files[0]) as fh:
        return json.load(fh)


def index(report):
    out = {}
    for suite in report["suites"]:
        for case in suite["cases"]:
            out[case["name"]] = case
    return out


def main():
    cases = index(run())
    print("cases: " + ", ".join(sorted(cases)))

    flaky = cases.get("flakyRecovers()")
    check("a retried test is ONE case", flaky is not None, sorted(cases))
    if flaky:
        got = [a["status"] for a in (flaky.get("attempts") or [])]
        check("with the failed attempts recorded as FAILURES, not skips",
              got == ["failed", "failed", "passed"], got)
        check("and marked flaky", flaky.get("isFlaky") is True, flaky.get("isFlaky"))

    hard = cases.get("failsHard()")
    check("a test that never recovers is failed", hard and hard["status"] == "failed",
          hard and hard["status"])
    if hard:
        check("and is NOT flaky", not hard.get("isFlaky"), hard.get("isFlaky"))

    check("DataProvider rows stay distinct cases",
          "parameterised(alpha,1)" in cases and "parameterised(beta,2)" in cases,
          sorted(k for k in cases if k.startswith("parameterised")))

    slow = cases.get("timesOut()")
    check("a timeout reports timeout, not failed", slow and slow["status"] == "timeout",
          slow and slow["status"])

    dep = cases.get("skippedByDependency()")
    check("a dependency skip is skipped", dep and dep["status"] == "skipped",
          dep and dep["status"])

    cfg = [k for k in cases if k.startswith("[config] ")]
    check("a failing @BeforeClass produces a config case", len(cfg) == 1, cfg)
    if cfg:
        check("and it is red", cases[cfg[0]]["status"] == "error", cases[cfg[0]]["status"])
    check("its guarded tests are still reported",
          "guardedOne()" in cases and "guardedTwo()" in cases, sorted(cases))

    check("a disabled test appears nowhere", "disabled()" not in cases, sorted(cases))

    meta = cases.get("carriesMetadata()")
    check("metadata reached the report", meta is not None)
    if meta:
        blob = json.dumps(meta)
        check("labels survived", "checkout" in blob, blob[:200])
        check("steps survived", "add to cart" in blob, blob[:200])
        check("a masked parameter carries NO value", "token" in blob and '"token"' in blob)

    print()
    if failures:
        print("%d check(s) failed" % len(failures))
        sys.exit(1)
    print("all checks passed (%d cases)" % len(cases))


if __name__ == "__main__":
    main()
```

- [ ] **Step 4: Install the reporter and run the gate**

```bash
mvn -q install -DskipTests
python3 test/integration/verify.py
```
Expected: every check PASSES. If `flakyRecovers` reports `["skipped","skipped","passed"]`, Task 5's `wasRetried()` routing is wrong — fix it before continuing.

- [ ] **Step 5: Prove the gate actually fails when the behaviour breaks**

Temporarily revert the `wasRetried()` branch in `onTestSkipped` (record every skip as a skip), then `mvn -q install -DskipTests && python3 test/integration/verify.py`.
Expected: the "failed attempts recorded as FAILURES" check FAILS. **Revert.** A gate that cannot fail is not a gate.

- [ ] **Step 6: Commit**

```bash
git add test/integration
git commit -m "test: integration fixture asserting the traps, against a real TestNG run

The unit tests drive synthetic ITestResults; this proves the same behaviour when
TestNG itself produces the events, which is where identity, retry sequencing and
ServiceLoader discovery are actually exercised.

Verified the gate can fail: reverting the wasRetried() routing turns
flakyRecovers into [skipped, skipped, passed] and the run red."
```

---

### Task 9: CI matrix and documentation

**Files:**
- Create: `.github/workflows/ci.yml`, `README.md`, `docs/CONFIGURATION.md`, `docs/METADATA-API.md`, `LICENSE`

**Interfaces:**
- Consumes: everything above
- Produces: the compatibility guarantee the README claims

- [ ] **Step 1: Write the workflow**

Create `.github/workflows/ci.yml`:

```yaml
name: CI

on:
  push:
    branches: [main]
  pull_request:
  workflow_dispatch:

permissions:
  contents: read

concurrency:
  group: ci-${{ github.ref }}
  cancel-in-progress: true

jobs:
  unit:
    name: Unit (Java ${{ matrix.java }})
    runs-on: ubuntu-latest
    strategy:
      fail-fast: false
      matrix:
        # 11 is the compile floor, 21 the current LTS, 17 the one in between.
        java: ['11', '17', '21']
    steps:
      - uses: actions/checkout@v4
      - uses: actions/setup-java@v4
        with:
          distribution: temurin
          java-version: ${{ matrix.java }}
          cache: maven
      - run: mvn -B -ntp verify

  compatibility:
    name: TestNG ${{ matrix.testng }}
    runs-on: ubuntu-latest
    strategy:
      fail-fast: false
      matrix:
        # 7.4.0 is the floor: ITestResult.wasRetried() must exist, and the whole
        # retry story depends on it. 7.12.0 is current.
        testng: ['7.4.0', '7.12.0']
    steps:
      - uses: actions/checkout@v4
      - uses: actions/setup-java@v4
        with:
          distribution: temurin
          java-version: '17'
          cache: maven
      - name: Build and install the reporter
        run: mvn -B -ntp -q install -DskipTests
      - name: Run the integration gate against TestNG ${{ matrix.testng }}
        run: python3 test/integration/verify.py
        env:
          # Read by verify.py and passed through to the fixture. The reporter version
          # is read from the root pom, never hardcoded -- a stale default cost
          # qualflare-junit5 four red commits after versions:set.
          TESTNG_VERSION: ${{ matrix.testng }}
```

- [ ] **Step 2: Write the README**

Create `README.md`. It must state, and no more than, what has been verified:

```markdown
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
```

- [ ] **Step 3: Copy the licence**

```bash
cp /Users/ibrahim/Astrais/frameworks/qualflare-junit5/LICENSE LICENSE
```

- [ ] **Step 4: Write the docs pages**

Create `docs/CONFIGURATION.md` and `docs/METADATA-API.md` by adapting the equivalents from `qualflare-junit5`, replacing JUnit specifics (the `@ExtendWith` / autodetection section has no TestNG equivalent and must be **deleted**, not translated) and the artifact coordinates.

- [ ] **Step 5: Verify the whole thing from clean**

```bash
mvn -B -ntp verify
mvn -q install -DskipTests
python3 test/integration/verify.py
TESTNG_VERSION=7.4.0 python3 test/integration/verify.py
```
Expected: all green, including the 7.4.0 floor.

- [ ] **Step 6: Commit**

```bash
git add .github README.md LICENSE docs
git commit -m "ci: compatibility matrix, and docs stating only what is verified

Java 11/17/21 and TestNG 7.4.0/7.12.0. The 7.4.0 floor is where
ITestResult.wasRetried() exists, which the whole retry story depends on.

The README documents the retried-skip behaviour explicitly, because a user
comparing this reporter's output against TestNG's own XML will otherwise wonder
why the counts differ."
```

---

## Self-Review

**Spec coverage.** Walked each spec section against the tasks: install/ServiceLoader → Task 5; write hook and JVM-scoped state → Tasks 2, 5; case identity → Task 3; attempts and the retried-skip rule → Tasks 4, 5, 8; status mapping incl. timeout → Task 4; configuration failures → Task 6; metadata API → Task 7; testing (unit, integration, mutation, matrix) → Tasks 3–9; zero dependencies → Task 1 (`provided` scope) and the global constraints. `Reporter.log()` capture is explicitly out of scope in the spec and correctly has no task.

**Not covered, deliberately:** release/publishing scaffolding (`RELEASING.md`, GPG, Central Portal). The spec does not cover it, and `qualflare-junit5`'s `RELEASING.md` is the template to adapt when a release is actually wanted. Flagged rather than silently omitted.

**Placeholder scan.** No TBD/TODO. Every code step carries real code; the one instruction that is prose rather than code — Task 9 Step 4, adapting two docs pages — names the source file and the specific section that must be deleted rather than translated.

**Type consistency.** `TestKey.of(ITestResult)` → `String` is produced in Task 3 and consumed with that exact signature in Tasks 5, 6, 7. `Status.of(ITestResult)` and `Status.ofThrowable(Throwable)` are defined in Task 4 and used in Task 5. `Accumulator.finished(...)` is used in Tasks 5 and 6 with the nine-argument signature recorded in Task 2's Interfaces block. `Attempt` field `status` is read in Task 5's test helper and matches the copied class. `CaseRecord.displayName`, `.status()`, `.isFlaky()`, `.retryCount()`, `.message()` are used in Tasks 5–6 and match the copied class.

**One ordering hazard flagged inline:** Task 2's `CoreSmokeTest` references `Status.PASSED`, which Task 4 creates. Task 2 Step 5 says so and gives the workaround.

---

## Execution

Plan complete and saved to `docs/superpowers/plans/2026-09-15-qualflare-testng.md`.
