#!/usr/bin/env python3
"""Public generator check: identical pinned inputs reproduce the shipped bytes."""
import subprocess
import sys
import tempfile
from pathlib import Path

root = Path(__file__).resolve().parents[2]
with tempfile.TemporaryDirectory() as directory:
    output = Path(directory) / "places.gzip"
    subprocess.run([sys.executable, str(root / "tools/places/generate.py"), "--output", str(output)], check=True)
    assert output.read_bytes() == (root / "app/src/main/assets/places-v1.sqlite.gzip").read_bytes()
print("Packaged database reproduced byte-for-byte")
