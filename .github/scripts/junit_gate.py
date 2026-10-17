#!/usr/bin/env python3
"""Judge a test task from its JUnit XML, not from Gradle's exit alone.

usage: junit_gate.py <label> <dir> [<dir> ...]

Red when: no TEST-*.xml under the dirs, 0 test cases in them ("green because
nothing ran"), or any failure / error. Prints every case that failed and the
per-suite totals, so the log names what is red.
"""
import glob
import os
import sys
import xml.etree.ElementTree as ET


def main() -> int:
    if len(sys.argv) < 3:
        print("usage: junit_gate.py <label> <dir> [<dir> ...]", file=sys.stderr)
        return 2
    label, dirs = sys.argv[1], sys.argv[2:]
    files = sorted(
        {f for d in dirs for f in glob.glob(os.path.join(d, "**", "TEST-*.xml"), recursive=True)}
    )
    print(f"[{label}] result files: {len(files)}")
    tests = failures = errors = skipped = 0
    failed = []
    for f in files:
        root = ET.parse(f).getroot()
        suites = [root] if root.tag == "testsuite" else list(root.iter("testsuite"))
        for s in suites:
            for c in s.iter("testcase"):
                tests += 1
                name = f"{c.get('classname')}.{c.get('name')}"
                if c.find("failure") is not None:
                    failures += 1
                    failed.append(("FAIL", name))
                elif c.find("error") is not None:
                    errors += 1
                    failed.append(("ERROR", name))
                elif c.find("skipped") is not None:
                    skipped += 1
        print(f"  {os.path.relpath(f)}")
    for kind, name in failed:
        print(f"::error::[{label}] {kind} {name}")
    print(f"[{label}] tests={tests} failures={failures} errors={errors} skipped={skipped}")
    if not files:
        print(f"::error::[{label}] no TEST-*.xml under {dirs} — the task did not run")
        return 1
    if tests == 0:
        print(f"::error::[{label}] 0 test cases executed — green because nothing ran")
        return 1
    return 1 if failures + errors else 0


if __name__ == "__main__":
    sys.exit(main())
