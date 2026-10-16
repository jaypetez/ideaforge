"""Check managed-device execution and the recorder's actual output, outside instrumentation."""

import argparse
import json
from pathlib import Path
import re
import sys
import xml.etree.ElementTree as ET


CAPTURE_CLASS = "io.github.jaypetez.ideaforge.ux.UxCaptureDeviceTest"
MODES = {
    "full": ("requestedUxEvidenceIsRenderedCompleteAndReachable", "ux"),
    "shell": ("requestedShellEvidenceKeepsInsetsAndIdentity", "ux-shell"),
    "privacy-before": ("requestedPrivacyPolicyLinksAreReachable", "ux-privacy-before"),
    "privacy": ("requestedPrivacyPolicyLinksAreReachable", "ux-privacy-after"),
    "consent-before": ("requestedInferenceConsentIsProminent", "ux-consent-before"),
    "consent": ("requestedInferenceConsentIsProminent", "ux-consent-after"),
}
DEVICES = {"compact": "pixel6Api34Visual", "expanded": "pixelTabletApi34Visual"}
PNG = b"\x89PNG\r\n\x1a\n"


def require(condition, message):
    if not condition:
        raise ValueError(message)


def check_report(path, method):
    root = ET.parse(path).getroot()
    require(root.tag == "testsuites", f"{path}: unexpected JUnit root")
    count = int(root.attrib["tests"])
    require(count > 0, f"{path}: zero discovered visual tests")
    cases = list(root.iter("testcase"))
    require(count == len(cases), f"{path}: inconsistent JUnit execution count")
    require(all(int(root.attrib[key]) == 0 for key in ("failures", "errors")),
            f"{path}: visual tests failed")
    selected = [case for case in cases
                if case.get("classname") == CAPTURE_CLASS and case.get("name") == method]
    require(len(selected) == 1, f"{path}: selected evidence method did not execute: {method}")
    require(not any(selected[0].find(tag) is not None for tag in ("skipped", "failure", "error")),
            f"{path}: selected evidence method did not pass")


def check_png(directory, relative):
    require(isinstance(relative, str) and relative, "missing PNG artifact path")
    path = (directory / relative).resolve()
    require(path.is_relative_to(directory.resolve()) and path.suffix == ".png",
            f"invalid PNG artifact path: {relative}")
    with path.open("rb") as image:
        require(image.read(8) == PNG, f"missing or corrupt PNG: {path}")


def check_manifest(path, sha):
    manifest = json.loads(path.read_text(encoding="utf-8"))
    require(isinstance(manifest, dict), f"{path}: malformed capture manifest")
    require(manifest.get("schema") == 1 and manifest.get("mode") == "matrix",
            f"{path}: unsupported capture manifest")
    require(manifest.get("testedSha") == sha and manifest.get("sourceState") == "clean",
            f"{path}: capture provenance does not match the clean tested SHA")
    require(manifest.get("status") == "passed" and manifest.get("failures") == [],
            f"{path}: capture matrix did not pass")
    expected = manifest.get("expected")
    require(isinstance(expected, list) and expected and all(isinstance(key, str) and key for key in expected),
            f"{path}: empty or malformed expected capture inventory")
    require(len(set(expected)) == len(expected), f"{path}: duplicate expected capture")
    captures = manifest.get("captures")
    require(isinstance(captures, list) and len(captures) == len(expected),
            f"{path}: incomplete capture matrix")
    keys = []
    for capture in captures:
        require(isinstance(capture, dict) and capture.get("passed") is True,
                f"{path}: a requested capture failed")
        require(capture.get("testedSha") == sha and capture.get("sourceState") == "clean",
                f"{path}: capture SHA/source state mismatch")
        artifact = capture.get("artifact")
        check_png(path.parent, artifact)
        require(isinstance(capture.get("state"), str) and capture["state"],
                f"{path}: missing capture state")
        keys.append(f"{Path(artifact).parts[0]}/{capture['state']}")
        geometry = capture.get("geometry")
        require(isinstance(geometry, list), f"{path}: missing control evidence")
        for control in geometry:
            require(isinstance(control, dict) and control.get("passed") is True,
                    f"{path}: a requested control failed")
            check_png(path.parent, control.get("artifact"))
    require(len(set(keys)) == len(keys) and set(keys) == set(expected),
            f"{path}: captured states do not match the requested inventory")
    return len(captures)


def check_evidence(build, window, sha, evidence):
    require(re.fullmatch(r"[0-9a-f]{40}", sha) is not None, "supply the exact 40-character tested SHA")
    method, folder = MODES[evidence]
    device = DEVICES[window]
    report = build / "outputs/androidTest-results/managedDevice/debug" / device / f"TEST-{device}.xml"
    check_report(report, method)
    manifests = []
    for location in ("intermediates", "outputs"):
        root = build / location / "managed_device_android_test_additional_output"
        manifests.extend(root.glob(f"**/{folder}/manifest-{window}.json"))
    require(manifests, f"{device}: missing requested {evidence} capture manifest")
    return sum(check_manifest(path, sha) for path in manifests)


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--build", type=Path, default=Path("app/build"))
    parser.add_argument("--window", choices=DEVICES, required=True)
    parser.add_argument("--sha", required=True)
    parser.add_argument("--evidence", choices=MODES, default="full")
    args = parser.parse_args()
    try:
        count = check_evidence(args.build, args.window, args.sha, args.evidence)
    except (OSError, ValueError, KeyError, TypeError, ET.ParseError) as error:
        print(f"Native UX evidence failed: {error}", file=sys.stderr)
        return 1
    print(f"Native UX evidence verified: {args.window}, {args.evidence}, {count} captures, SHA {args.sha}")
    return 0


if __name__ == "__main__":
    sys.exit(main())
