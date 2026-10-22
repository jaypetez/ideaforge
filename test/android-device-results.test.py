"""Focused regressions for the managed-device XML gate; no emulator or dependencies."""

import runpy
import unittest
from pathlib import Path
from xml.etree import ElementTree


require_selected_test = runpy.run_path(
    str(Path(__file__).resolve().parents[1] / "tools" / "check-android-device-results.py")
)["require_selected_test"]
SELECTED = "io.github.jaypetez.ideaforge.ux.UxCaptureDeviceTest#requestedUxEvidenceIsRenderedCompleteAndReachable"


def report(case="", tests=1, **attributes):
    root = ElementTree.Element("testsuites", tests=str(tests), **attributes)
    suite = ElementTree.SubElement(root, "testsuite", name=SELECTED.split("#")[0], tests=str(tests))
    if case:
        suite.append(ElementTree.fromstring(case))
    return root


def selected_case(child=""):
    test_class, method = SELECTED.split("#")
    return f'<testcase classname="{test_class}" name="{method}">{child}</testcase>'


class DeviceResultTest(unittest.TestCase):
    def test_original_zero_test_success_report_is_rejected(self):
        # The actual #218 visual report had only this empty, successful testsuites root.
        root = ElementTree.fromstring(
            '<testsuites tests="0" failures="0" errors="0" skipped="0" time="0.000" hostname="localhost" />'
        )
        with self.assertRaisesRegex(ValueError, "zero executed visual tests"):
            require_selected_test(root, SELECTED)

    def test_a_nonzero_counter_without_actual_testcases_is_not_execution(self):
        with self.assertRaisesRegex(ValueError, "zero executed visual tests"):
            require_selected_test(report(tests=1), SELECTED)
        with self.assertRaisesRegex(ValueError, "reported 2 tests but found 1"):
            require_selected_test(report(selected_case(), tests=2), SELECTED)

    def test_a_different_test_cannot_replace_the_selected_method_or_class(self):
        for attribute in ("name", "classname"):
            root = report(selected_case())
            root.find(".//testcase").set(attribute, "unrelated")
            with self.assertRaisesRegex(ValueError, "expected one executed"):
                require_selected_test(root, SELECTED)

    def test_failures_errors_and_skips_are_not_visual_execution_passes(self):
        for tag, counter in (("failure", "failures"), ("error", "errors"), ("skipped", "skipped")):
            with self.subTest(tag=tag):
                with self.assertRaisesRegex(ValueError, f"contains a {tag}"):
                    require_selected_test(report(selected_case(f"<{tag}/>")), SELECTED)
                with self.assertRaisesRegex(ValueError, counter):
                    require_selected_test(report(selected_case(), **{counter: "1"}), SELECTED)

    def test_one_actual_selected_passing_test_is_accepted(self):
        self.assertEqual(1, require_selected_test(report(selected_case()), SELECTED))
        shell = SELECTED.replace("requestedUxEvidenceIsRenderedCompleteAndReachable",
                                 "requestedShellEvidenceKeepsInsetsAndIdentity")
        root = report(selected_case())
        root.find(".//testcase").set("name", shell.split("#")[1])
        self.assertEqual(1, require_selected_test(root, shell))


if __name__ == "__main__":
    unittest.main()
