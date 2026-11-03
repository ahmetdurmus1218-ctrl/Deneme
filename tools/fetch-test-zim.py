#!/usr/bin/env python3
"""CI helper: downloads a small real Wikipedia ZIM (openzim test suite, 14 MB) pinned by SHA-256.

    python3 tools/fetch-test-zim.py <target.zim>
"""
import hashlib, os, sys, urllib.request

URL = "https://raw.githubusercontent.com/openzim/zim-testing-suite/main/data/withns/wikipedia_en_climate_change_mini_2024-06.zim"
SHA = "72db7ae7708d3a4cff918495078901ec027b14b427433a2328286ec130d1c43b"

target = sys.argv[1]
os.makedirs(os.path.dirname(os.path.abspath(target)), exist_ok=True)
if not (os.path.isfile(target) and hashlib.sha256(open(target, "rb").read()).hexdigest() == SHA):
    urllib.request.urlretrieve(URL, target + ".part")
    got = hashlib.sha256(open(target + ".part", "rb").read()).hexdigest()
    if got != SHA:
        os.remove(target + ".part")
        sys.exit(f"test ZIM SHA-256 mismatch: {got}")
    os.replace(target + ".part", target)
print("ok", target)
