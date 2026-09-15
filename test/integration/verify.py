#!/usr/bin/env python3
"""Runs the fixture and asserts the traps this reporter exists to handle.

A report can be structurally valid and semantically wrong. These are the properties
that would silently regress: a retried failure recorded as a skip, DataProvider rows
collapsed into one case, a timeout flattened into a failure, a broken @BeforeClass
leaving a suite that reads green.
"""
import base64
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


def run(extra_args=()):
    results = os.path.join(FIXTURE, "qualflare-results")
    shutil.rmtree(results, ignore_errors=True)

    cmd = [MVN, "-q", "test", "-Dqualflare.version=" + reporter_version()]
    if TESTNG:
        cmd.append("-Dtestng.version=" + TESTNG)
    cmd.extend(extra_args)
    proc = subprocess.run(cmd, cwd=FIXTURE, capture_output=True, text=True)

    files = glob.glob(os.path.join(results, "*.json"))
    if not files:
        # Say WHY. Swallowing maven's output turns a missing dependency into the
        # uninformative "no report written", which is the same class of mistake as
        # trusting a green checkmark.
        print("  no report written -- maven exited %d; the fixture build said:"
              % proc.returncode)
        for line in (proc.stdout + proc.stderr).splitlines():
            if re.search(r"ERROR|BUILD FAILURE|Could not resolve|cannot find symbol", line):
                print("    " + line.strip())
        if proc.returncode == 0:
            # Exit 0 with no report implicates registration or packaging, NOT the test
            # failures above -- this fixture fails tests on purpose (testFailureIgnore),
            # so those [ERROR] lines are expected noise and are not the cause. Printing
            # them alone sent a reader chasing the wrong thing.
            print("    the build SUCCEEDED and still produced no report, so the listener")
            print("     probably never registered: check that the built jar contains")
            print("     META-INF/services/org.testng.ITestNGListener naming")
            print("     com.qualflare.testng.QualflareListener")
            for line in (proc.stdout + proc.stderr).splitlines()[-25:]:
                print("    " + line.rstrip())
        sys.exit(1)
    if len(files) != 1:
        print("  expected exactly 1 report file, got %d" % len(files))
        sys.exit(1)
    with open(files[0]) as fh:
        return json.load(fh), proc.stdout + proc.stderr


def index(report, prefix=""):
    """Flatten cases across suites, keyed by display name.

    Name uniqueness is asserted BEFORE indexing. A plain dict silently collapses
    duplicates, and duplicates are precisely what an identity regression produces: one
    retried test split into two CaseRecords with different uniqueIds emits two objects
    sharing a display name. Without this assertion the check named "a retried test is
    ONE case" could not fail for its own bug class -- Accumulator keys by uniqueId and
    ReportWriter does no name-based dedup.
    """
    seen = {}
    for suite in report["suites"]:
        for case in suite["cases"]:
            seen.setdefault(case["name"], []).append(case)
    dupes = {n: len(v) for n, v in seen.items() if len(v) > 1}
    check(prefix + "no case name appears twice (an identity split would duplicate one)",
          not dupes, dupes)
    return {n: v[0] for n, v in seen.items()}


def worker_threads(cases):
    """The distinct TestNG worker threads the fixture observed, from its qfThread label."""
    names = set()
    for case in cases.values():
        for label in case.get("labels") or []:
            if label.get("name") == "qfThread":
                names.add(label.get("value"))
    return names


def signature(cases):
    """Name -> (status, attempt count): what must not change with the execution mode."""
    return {n: (c.get("status"), len(c.get("attempts") or [])) for n, c in cases.items()}


def compare_parallel(serial):
    """The spec's serial-versus-parallel comparison.

    Under parallel="methods" every accumulation is a read-modify-write driven from a TestNG
    worker thread, and Accumulator's entire `synchronized` design exists for that case --
    untested until now, as was Qualflare's thread-local resolution under concurrent workers,
    which is the mechanism most likely to misattribute metadata. A lost attempt or a label
    on the wrong case shows up here as a disagreement between the two reports.
    """
    print()
    print("parallel run (-Dparallel=methods -DthreadCount=4):")
    report, _ = run(["-Dparallel=methods", "-DthreadCount=4"])
    cases = index(report, "parallel: ")

    # PROVE the run was actually parallel. Without this the comparison could pass simply
    # because -Dparallel never reached TestNG, which would make the whole check incapable
    # of failing for the concurrency bugs it exists to find.
    threads = worker_threads(cases)
    check("parallel: TestNG really did use more than one worker thread",
          len(threads) >= 2, sorted(threads))

    want, got = signature(serial), signature(cases)
    missing = sorted(set(want) - set(got))
    extra = sorted(set(got) - set(want))
    check("parallel: the same set of cases is reported", not missing and not extra,
          {"missing": missing, "unexpected": extra})

    disagree = {n: {"serial": want[n], "parallel": got[n]}
                for n in sorted(set(want) & set(got)) if want[n] != got[n]}
    check("parallel: every case keeps its status and attempt count", not disagree, disagree)

    # Metadata attribution. The label must be on its own case and on NO other -- a case
    # picking up another case's label is exactly the silent wrong data the thread-local
    # resolution has to prevent.
    carriers = sorted(n for n, c in cases.items() if "checkout" in json.dumps(c))
    check("parallel: the label is on exactly its own case", carriers == ["carriesMetadata()"],
          carriers)
    par_meta = cases.get("carriesMetadata()") or {}
    check("parallel: the attachment survived too",
          len(par_meta.get("attachments") or []) == 1, par_meta.get("attachments"))


def main():
    print("serial run:")
    report, output = run()
    cases = index(report)
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

    # The control for the parallel comparison below: serially there is exactly one worker.
    serial_threads = worker_threads(cases)
    check("serially the whole fixture runs on one thread", len(serial_threads) == 1,
          sorted(serial_threads))

    meta = cases.get("carriesMetadata()")
    check("metadata reached the report", meta is not None)
    if meta:
        blob = json.dumps(meta)
        check("labels survived", "checkout" in blob, blob[:200])
        check("steps survived", "add to cart" in blob, blob[:200])

        props = meta.get("properties") or {}
        check("the unmasked parameter carries its value",
              props.get("sku") == "widget", props.get("sku"))
        check("a masked parameter carries NO value",
              "token" in props and props.get("token") == "", props.get("token"))

        # Qualflare.attachment is the ONLY way a file can reach the report: TestNG has no
        # equivalent of the JUnit Platform's fileEntryPublished. Without this check the
        # whole Attachments/Accumulator/ReportWriter attachment path is unreachable code
        # behind a README promise.
        atts = meta.get("attachments") or []
        check("an attachment reached the report", len(atts) == 1, atts)
        if atts:
            a = atts[0]
            check("with its name and media type",
                  a.get("name") == "receipt" and a.get("mimeType") == "text/plain", a)
            decoded = None
            if a.get("content"):
                try:
                    decoded = base64.b64decode(a["content"]).decode("utf-8")
                except Exception as exc:  # noqa: BLE001 - reported, not raised
                    decoded = "undecodable: %s" % exc
            check("and its content, base64-inlined", decoded == "receipt-body", decoded)

    # Reporter.getCurrentTestResult() is NON-NULL inside @BeforeMethod and returns the
    # CONFIGURATION method's result, so a null check alone let config metadata through: it
    # was filed under a key that never becomes a case, with no warning, leaking for the
    # life of the JVM. Setting shared labels in a base-class @BeforeMethod is mainstream
    # TestNG, so this is a path real users take.
    cfgmeta = cases.get("doesNotInheritConfigMetadata()")
    check("the test guarded by a metadata-emitting @BeforeMethod is reported",
          cfgmeta is not None, sorted(cases))
    whole = json.dumps(report)
    for leaked in ("leaked-from-beforeMethod", "leaked-config-tag", "leaked-config-value"):
        check("no @BeforeMethod metadata reached the report (%s)" % leaked,
              leaked not in whole)
    check("a SUCCESSFUL configuration method produces no case of its own",
          "[config] ConfigMetadataTest#setUp" not in cases,
          [k for k in cases if k.startswith("[config] ")])

    # The report alone CANNOT prove this one. Dropped metadata and leaked metadata look
    # identical in the JSON -- a key that never becomes a case emits nothing either way.
    # The warning on stderr is the only observable difference, and "dropped WITH A WARNING"
    # is the constraint's actual wording, so it is what gets pinned.
    check("and the drop was WARNED about, naming the configuration method",
          "metadata call from the configuration method setUp" in output,
          "\n".join(l for l in output.splitlines() if "qualflare-testng]" in l)[:600])

    compare_parallel(cases)

    print()
    if failures:
        print("%d check(s) failed" % len(failures))
        sys.exit(1)
    print("all checks passed (%d cases)" % len(cases))


if __name__ == "__main__":
    main()
