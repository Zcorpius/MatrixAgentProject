#!/usr/bin/env python3
"""Run JNI-independent UTF-8/UTF-16 and fragmented stop-marker regression checks."""
import os
from pathlib import Path
import shutil
import subprocess
import tempfile

root = Path(__file__).resolve().parents[2]
compiler = os.environ.get("CXX") or shutil.which("clang++") or shutil.which("g++")
if not compiler:
    raise SystemExit("A host C++17 compiler is required")
with tempfile.TemporaryDirectory(prefix="matrix-native-stream-") as directory:
    executable = Path(directory) / "native-stream-test"
    subprocess.run([compiler, "-std=c++17", "-Wall", "-Wextra", "-Werror",
                    "-I" + str(root / "ondevice/src/main/cpp"),
                    str(root / "ondevice/src/test/cpp/utf8stream_test.cpp"),
                    "-o", str(executable)], check=True)
    subprocess.run([str(executable)], check=True)
print("PASS: Unicode scalar validation, UTF-16 round trip, all UTF-8 and stop-marker split boundaries")
