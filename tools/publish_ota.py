"""Builds the firmware for both boards and prepares a GitHub OTA release.

    python tools/publish_ota.py "what changed"          # build + write ota/
    python tools/publish_ota.py "what changed" --push   # ... and git commit + push

Writes ota/firmware-esp32dev.bin, ota/firmware-esp32s3.bin and
ota/manifest.json. Counters with "Firmware update > From GitHub" set to this
repository read the manifest from raw.githubusercontent.com (files in the
repository itself; GitHub Release downloads are blocked on some networks).
The version comes from FIRMWARE_VERSION in include/config.h - raise it first,
counters only offer versions newer than their own.
"""
import hashlib
import json
import os
import re
import shutil
import subprocess
import sys

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
TARGETS = [  # (PlatformIO env, BOARD_NAME in config.h, file in ota/)
    ("esp32dev", "ESP32", "firmware-esp32dev.bin"),
    ("esp32-s3-n16r8", "ESP32-S3", "firmware-esp32s3.bin"),
]


def run(cmd):
    print("$", " ".join(cmd), flush=True)
    subprocess.run(cmd, cwd=ROOT, check=True)


def main():
    if len(sys.argv) < 2:
        sys.exit(__doc__)
    notes, push = sys.argv[1], "--push" in sys.argv[2:]
    config = open(os.path.join(ROOT, "include", "config.h"), encoding="utf-8").read()
    version = re.search(r'#define FIRMWARE_VERSION "([^"]+)"', config).group(1)

    run(["pio", "run"] + sum((["-e", env] for env, _, _ in TARGETS), []))

    os.makedirs(os.path.join(ROOT, "ota"), exist_ok=True)
    files = {}
    for env, board, name in TARGETS:
        src = os.path.join(ROOT, ".pio", "build", env, "firmware.bin")
        dst = os.path.join(ROOT, "ota", name)
        shutil.copyfile(src, dst)
        data = open(dst, "rb").read()
        files[board] = {"path": "ota/" + name, "size": len(data), "md5": hashlib.md5(data).hexdigest()}
        print(f"{board:9s} {name}: {len(data)} bytes, md5 {files[board]['md5']}")

    manifest = {"version": version, "notes": notes, "files": files}
    with open(os.path.join(ROOT, "ota", "manifest.json"), "w", encoding="utf-8") as f:
        json.dump(manifest, f, indent=2)
        f.write("\n")
    print(f"ota/manifest.json -> version {version}")

    if push:
        run(["git", "add", "ota"])
        run(["git", "commit", "-m", f"Firmware {version}: {notes}"])
        run(["git", "push"])
        print("Published. Counters can now find it with 'Check for update'.")


if __name__ == "__main__":
    main()
