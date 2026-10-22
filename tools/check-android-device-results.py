#!/usr/bin/env python3
"""Reject empty or unsuccessful managed-device discovery before accepting visual evidence."""

import sys
from pathlib import Path
from xml.etree import ElementTree


def require_selected_test(root, selected):
    test_class, separator, method = selected.partition("#")
    if not separator or not test_class or not method:
        raise ValueError("visual evidence requires an explicit CLASS#METHOD selector")
    if root.tag not in ("testsuite", "testsuites"):
        raise ValueError("not a JUnit test-result report")
    cases = list(root.iter("testcase"))
    reported = int(root.attrib["tests"])
    if reported == 0 or not cases:
        raise ValueError(f"zero executed visual tests for {selected}; Gradle success is not evidence")
    if reported != len(cases):
        raise ValueError(f"reported {reported} tests but found {len(cases)} actual test cases")
    for suite in root.iter():
        if suite.tag in ("testsuite", "testsuites"):
            for status in ("failures", "errors", "skipped"):
                if int(suite.get(status, "0")) != 0:
                    raise ValueError(f"visual test report has {status}: {suite.get(status)}")
        if suite.tag in ("failure", "error", "skipped"):
            raise ValueError(f"visual test report contains a {suite.tag}")
    matches = [case for case in cases if case.get("classname") == test_class and case.get("name") == method]
    if len(matches) != 1:
        raise ValueError(f"expected one executed {selected}, found {len(matches)}; other tests cannot replace it")
    return len(cases)


def main():
    if len(sys.argv) != 3:
        raise SystemExit("usage: python tools/check-android-device-results.py REPORT.xml CLASS#METHOD")
    report, selected = sys.argv[1:]
    try:
        total = require_selected_test(ElementTree.parse(Path(report)).getroot(), selected)
    except (OSError, ElementTree.ParseError, KeyError, ValueError) as error:
        raise SystemExit(f"Native UX execution failed ({report}): {error}") from error
    print(f"Native UX execution verified: {selected}; 1 selected passing test, {total} actual total ({report})")


if __name__ == "__main__":
    main()
