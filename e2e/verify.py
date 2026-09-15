#!/usr/bin/env python3
"""Inspect the dogfood report BEFORE it is uploaded.

A report can be structurally valid and semantically wrong. That is how
`Case.attempts` went missing for three qualflare-cli releases while every run
stayed green: the upload succeeded, the JSON parsed, and nobody asserted that
what the suite declared was actually in it.

So this asserts the properties the dogfood suite exists to demonstrate, and it
is deliberately strict about two of them:

  - EVERY case must have passed. That is the invariant separating this suite
    from test/integration/fixture. A red case here is a real regression.
  - No case name may appear twice. An identity regression splits one retried
    test into several records that share a display name, and a dict keyed by
    name would hide exactly that -- it did, in this project's own verifier,
    until a review found the check named "a retried test is ONE case" could not
    fail for its own bug class.

Reads the report only. The suite has already run; running maven again here would
produce a second report and hide which one was uploaded.
"""
import glob
import json
import os
import sys

OUTPUT_DIR = os.environ.get("QUALFLARE_OUTPUT_DIR", "qualflare-results")

# What the suite declares. Kept explicit rather than derived from the report:
# deriving the expectation from the thing under test is how a check stops being
# able to fail.
EXPECTED = {
    "recordsEveryMetadataKind()",
    "nestsSteps()",
    "aMaskedParameterInsideAStep()",
    "attachesAFile()",
    "dataProviderRowsAreTheirOwnCases(chrome)",
    "dataProviderRowsAreTheirOwnCases(firefox)",
    "retriesAreRecordedAsAttempts()",
    "testngOwnReporterLogIsLeftAlone()",
    "reportsFromASecondClass()",
}

failures = []


def check(label, ok, detail=""):
    print(("  PASS  " if ok else "  FAIL  ") + label + ("" if ok else "   -> " + str(detail)))
    if not ok:
        failures.append(label)


def load():
    files = sorted(glob.glob(os.path.join(OUTPUT_DIR, "*.json")))
    if not files:
        print("  no report in %s/ -- the suite ran but the reporter produced nothing." % OUTPUT_DIR)
        print("  Most likely the listener never registered: check that the jar on the test")
        print("  classpath contains META-INF/services/org.testng.ITestNGListener naming")
        print("  com.qualflare.testng.QualflareListener.")
        sys.exit(1)
    if len(files) != 1:
        print("  expected exactly 1 report file in %s/, got %d: %s" % (OUTPUT_DIR, len(files), files))
        print("  More than one means state escaped its JVM scope, or a stale file survived.")
        sys.exit(1)
    with open(files[0]) as fh:
        return json.load(fh)


def index(report):
    """Cases by display name, asserting uniqueness first."""
    seen = {}
    for suite in report.get("suites", []):
        for case in suite.get("cases", []):
            seen.setdefault(case["name"], []).append(case)
    dupes = {n: len(v) for n, v in seen.items() if len(v) > 1}
    check("no case name appears twice (an identity split would duplicate one)", not dupes, dupes)
    return {n: v[0] for n, v in seen.items()}


def blob(obj):
    return json.dumps(obj, sort_keys=True)


def main():
    report = load()
    cases = index(report)
    print("  cases: " + ", ".join(sorted(cases)))
    print()

    # --- the dogfood invariant ------------------------------------------------
    check("every declared case is present", EXPECTED <= set(cases),
          sorted(EXPECTED - set(cases)))
    check("and nothing unexpected appeared", set(cases) <= EXPECTED,
          sorted(set(cases) - EXPECTED))

    reds = {n: c["status"] for n, c in cases.items() if c["status"] != "passed"}
    check("EVERY case passed -- this suite has no deliberate failures", not reds, reds)

    check("cases are grouped into more than one suite",
          len(report.get("suites", [])) >= 2, len(report.get("suites", [])))

    # --- the version defect this family already shipped once ------------------
    version = (report.get("metadata") or {}).get("version")
    check("the report names a real reporter version, not the 0.0.0-dev fallback",
          bool(version) and version != "0.0.0-dev", version)
    check("the report names testng as its framework",
          report.get("framework") == "testng", report.get("framework"))

    # --- the headline capability ----------------------------------------------
    retry = cases.get("retriesAreRecordedAsAttempts()")
    if retry:
        got = [a["status"] for a in (retry.get("attempts") or [])]
        check("a retried test carries EVERY attempt, failures included",
              got == ["failed", "failed", "passed"], got)
        check("and is marked flaky", retry.get("isFlaky") is True, retry.get("isFlaky"))
        check("with retryCount 2", retry.get("retryCount") == 2, retry.get("retryCount"))
        check("while its final status is passed", retry.get("status") == "passed",
              retry.get("status"))

    # --- metadata -------------------------------------------------------------
    meta = cases.get("recordsEveryMetadataKind()")
    if meta:
        b = blob(meta)
        check("labels survived", "platform" in b and "reporting" in b, b[:160])
        check("tags survived", "smoke" in b and "dogfood" in b, b[:160])
        check("the link survived", "qualflare-testng" in b, b[:160])
        check("priority survived", meta.get("priority") == "high", meta.get("priority"))
        check("description survived", bool(meta.get("description")), meta.get("description"))
        props = meta.get("properties") or {}
        check("an unmasked parameter keeps its value", props.get("plan") == "pro", props)
        check("a MASKED parameter carries no value", props.get("token") == "", props)

    # --- steps ----------------------------------------------------------------
    steps = cases.get("nestsSteps()")
    if steps:
        b = blob(steps)
        check("the outer step is named", "add to cart" in b, b[:200])
        check("the nested step is named", "set quantity" in b, b[:200])

    masked_step = cases.get("aMaskedParameterInsideAStep()")
    if masked_step:
        b = blob(masked_step)
        check("a masked parameter inside a step names the secret but not its value",
              "api-token" in b, b[:200])

    # --- attachments ----------------------------------------------------------
    attached = cases.get("attachesAFile()")
    if attached:
        names = [a.get("name") for a in (attached.get("attachments") or [])]
        check("the attachment reached the report", "note.txt" in names, names)

    print()
    if failures:
        print("%d check(s) failed -- NOT uploading a report that misrepresents the suite"
              % len(failures))
        sys.exit(1)
    print("all checks passed (%d cases, %d suites)"
          % (len(cases), len(report.get("suites", []))))


if __name__ == "__main__":
    main()
