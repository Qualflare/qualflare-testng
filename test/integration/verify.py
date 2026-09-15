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

        props = meta.get("properties") or {}
        check("the unmasked parameter carries its value",
              props.get("sku") == "widget", props.get("sku"))
        check("a masked parameter carries NO value",
              "token" in props and props.get("token") == "", props.get("token"))

    print()
    if failures:
        print("%d check(s) failed" % len(failures))
        sys.exit(1)
    print("all checks passed (%d cases)" % len(cases))


if __name__ == "__main__":
    main()
