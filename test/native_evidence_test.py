import importlib.util
import json
from pathlib import Path
import tempfile
import unittest


SPEC = importlib.util.spec_from_file_location(
    "native_evidence", Path(__file__).resolve().parents[1] / "tools/check-native-evidence.py")
EVIDENCE = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(EVIDENCE)
SHA = "a" * 40


class NativeEvidenceTest(unittest.TestCase):
    def setUp(self):
        self.scratch = tempfile.TemporaryDirectory()
        self.addCleanup(self.scratch.cleanup)
        self.build = Path(self.scratch.name)
        self.report = self.build / "outputs/androidTest-results/managedDevice/debug/pixel6Api34Visual/TEST-pixel6Api34Visual.xml"
        self.report.parent.mkdir(parents=True)
        self.method, folder = EVIDENCE.MODES["full"]
        self.write_report()
        self.manifest = self.build / "intermediates/managed_device_android_test_additional_output/task" / folder / "manifest-compact.json"
        self.manifest.parent.mkdir(parents=True)
        self.artifact = "compact-light-f1_0/real-app/fresh.png"
        image = self.manifest.parent / self.artifact
        image.parent.mkdir(parents=True)
        image.write_bytes(EVIDENCE.PNG)
        self.data = {
            "schema": 1, "mode": "matrix", "testedSha": SHA, "sourceState": "clean",
            "status": "passed", "failures": [], "expected": ["compact-light-f1_0/fresh"],
            "captures": [{"state": "fresh", "testedSha": SHA, "sourceState": "clean",
                          "passed": True, "artifact": self.artifact, "geometry": []}],
        }
        self.write_manifest()

    def write_report(self, count=1, body=None):
        if body is None:
            body = f'<testcase classname="{EVIDENCE.CAPTURE_CLASS}" name="{self.method}" />'
        self.report.write_text(
            f'<testsuites tests="{count}" failures="0" errors="0">{body}</testsuites>', encoding="utf-8")

    def write_manifest(self):
        self.manifest.write_text(json.dumps(self.data), encoding="utf-8")

    def verify(self):
        return EVIDENCE.check_evidence(self.build, "compact", SHA, "full")

    def test_actual_execution_and_complete_matching_artifacts_pass(self):
        self.assertEqual(self.verify(), 1)

    def test_original_zero_test_report_fails_even_when_artifacts_exist(self):
        self.report.write_text(
            '<?xml version="1.0"?><testsuites tests="0" failures="0" errors="0" skipped="0" />',
            encoding="utf-8")
        with self.assertRaisesRegex(ValueError, "zero discovered visual tests"):
            self.verify()

    def test_missing_malformed_and_inconsistent_reports_fail(self):
        for text in ("", "<testsuites", '<testsuites tests="1" failures="0" errors="0" />'):
            with self.subTest(text=text):
                self.report.write_text(text, encoding="utf-8")
                with self.assertRaises((ValueError, EVIDENCE.ET.ParseError)):
                    self.verify()
        self.report.unlink()
        with self.assertRaises(FileNotFoundError):
            self.verify()

    def test_skipped_failed_and_wrong_selected_methods_fail(self):
        for tag in ("skipped", "failure", "error"):
            with self.subTest(tag=tag):
                self.write_report(body=f'<testcase classname="{EVIDENCE.CAPTURE_CLASS}" name="{self.method}"><{tag}/></testcase>')
                with self.assertRaisesRegex(ValueError, "did not pass"):
                    self.verify()
        self.write_report(body='<testcase classname="other" name="unrelated" />')
        with self.assertRaisesRegex(ValueError, "did not execute"):
            self.verify()

    def test_stale_dirty_running_and_failed_manifests_fail(self):
        for key, value in (("testedSha", "b" * 40), ("sourceState", "development-dirty"),
                           ("status", "running"), ("failures", ["capture failed"])):
            with self.subTest(key=key):
                original = self.data[key]
                self.data[key] = value
                self.write_manifest()
                with self.assertRaises(ValueError):
                    self.verify()
                self.data[key] = original

    def test_missing_and_malformed_manifests_fail(self):
        for text in ("{", "[]", "null"):
            with self.subTest(text=text):
                self.manifest.write_text(text, encoding="utf-8")
                with self.assertRaises(ValueError):
                    self.verify()
        self.manifest.unlink()
        with self.assertRaisesRegex(ValueError, "missing requested"):
            self.verify()

    def test_empty_partial_duplicate_and_wrong_state_inventories_fail(self):
        for expected in ([], ["compact-light-f1_0/fresh", "missing/state"],
                         ["compact-light-f1_0/fresh"] * 2, ["wrong/state"]):
            with self.subTest(expected=expected):
                self.data["expected"] = expected
                self.write_manifest()
                with self.assertRaises(ValueError):
                    self.verify()

    def test_capture_provenance_and_control_failures_are_not_hidden(self):
        capture = self.data["captures"][0]
        for key, value in (("testedSha", "b" * 40), ("passed", False),
                           ("sourceState", "development-dirty"),
                           ("geometry", [{"passed": False, "artifact": self.artifact}])):
            with self.subTest(key=key):
                original = capture[key]
                capture[key] = value
                self.write_manifest()
                with self.assertRaises(ValueError):
                    self.verify()
                capture[key] = original

    def test_missing_corrupt_and_escaping_pngs_fail(self):
        image = self.manifest.parent / self.artifact
        image.write_bytes(b"not a PNG")
        with self.assertRaisesRegex(ValueError, "corrupt PNG"):
            self.verify()
        image.unlink()
        with self.assertRaises(FileNotFoundError):
            self.verify()
        self.data["captures"][0]["artifact"] = "../outside.png"
        self.write_manifest()
        with self.assertRaisesRegex(ValueError, "invalid PNG artifact path"):
            self.verify()

    def test_focused_mode_cannot_use_a_full_inventory_manifest(self):
        self.method, _ = EVIDENCE.MODES["shell"]
        self.write_report()
        with self.assertRaisesRegex(ValueError, "missing requested shell"):
            EVIDENCE.check_evidence(self.build, "compact", SHA, "shell")


if __name__ == "__main__":
    unittest.main()
