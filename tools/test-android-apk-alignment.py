#!/usr/bin/env python3
"""Check a packaged APK with SDK zipalign and prove the check rejects a native entry."""

import shutil
import struct
import subprocess
import sys
from pathlib import Path
from zipfile import ZIP_STORED, ZipFile, ZipInfo


def check(zipalign, apk):
    command = [str(zipalign), "-c", "-P", "16", "-v", "4", str(apk)]
    print(subprocess.list2cmdline(command), flush=True)
    result = subprocess.run(command, capture_output=True, text=True)
    for line in result.stdout.splitlines():
        if ".so " in line or "Verification" in line:
            print(line)
    if result.returncode:
        print(result.stderr, file=sys.stderr)
    return result


def main():
    if len(sys.argv) != 3:
        raise SystemExit("usage: python tools/test-android-apk-alignment.py ZIPALIGN APK")
    zipalign, apk = map(Path, sys.argv[1:])
    baseline = check(zipalign, apk)
    if baseline.returncode:
        raise SystemExit(f"actual APK is not 16 KB ZIP-aligned:\n{baseline.stdout}")

    name = "lib/arm64-v8a/unaligned.so"
    with ZipFile(apk) as source:
        native = next((entry for entry in source.infolist()
                       if entry.filename.startswith("lib/arm64-v8a/") and entry.filename.endswith(".so")), None)
        if native is None:
            raise SystemExit("APK has no arm64 library for the alignment negative control")
        if name in source.namelist():
            raise SystemExit(f"negative-control name already exists: {name}")
        library = source.read(native)

    negative = apk.with_name(f"{apk.stem}-unaligned-negative.apk")
    # Exclusive creation and a copy leave the actual signed APK and other runs untouched.
    output = negative.open("xb")
    try:
        with output, apk.open("rb") as source:
            shutil.copyfileobj(source, output)
        with ZipFile(negative, "a") as target:
            entry = ZipInfo(name)
            entry.compress_type = ZIP_STORED
            offset = target.start_dir + 30 + len(name.encode("utf-8"))
            # A valid ZIP extra field keeps ordinary four-byte alignment while
            # deliberately missing the native-library 16 KB boundary.
            padding = -(offset + 4) % 4
            if (offset + 4 + padding) % 16384 == 0:
                padding += 4
            entry.extra = struct.pack("<HH", 0xFFFF, padding) + bytes(padding)
            target.writestr(entry, library)
        planted = check(zipalign, negative)
        if planted.returncode == 0 or f"{name} (BAD" not in planted.stdout:
            raise SystemExit(f"zipalign did not reject the planted unaligned.so:\n{planted.stdout}\n{planted.stderr}")
        print(f"Negative control rejected {name}; original APK was not modified")
    finally:
        negative.unlink()


if __name__ == "__main__":
    main()
