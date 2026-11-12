#!/usr/bin/env python3
"""CI helper: downloads locked model files (by group) into a directory and checks their SHA-256.

    python3 tools/fetch-models.py <dir> <group> [<group>...]

Uses the URLs and hashes in models.lock.json, so tests run against exactly the files the app
installs. Files already present with the right hash are kept (works with actions/cache).
"""
import hashlib, json, os, sys, time, urllib.error, urllib.request

root = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
dest, groups = sys.argv[1], set(sys.argv[2:])
lock = json.load(open(os.path.join(root, "models.lock.json"), encoding="utf-8"))
items = [i for i in lock["items"] if i["group"] in groups]
if not items:
    sys.exit(f"no locked items in groups {sorted(groups)}")
os.makedirs(dest, exist_ok=True)

def sha256(path):
    h = hashlib.sha256()
    with open(path, "rb") as f:
        for chunk in iter(lambda: f.read(1 << 20), b""):
            h.update(chunk)
    return h.hexdigest()

for it in items:
    path = os.path.join(dest, it["fileName"])
    if os.path.isfile(path) and sha256(path) == it["sha256"]:
        print("ok (cached)", it["fileName"])
        continue
    tmp = path + ".part"
    # Some hosts (Kiwix) redirect to a rotating set of mirrors and one may serve a broken copy:
    # try again, but never accept anything whose SHA-256 and size do not match the lock.
    # A busy mirror answers "429 Too Many Requests" or drops the connection now and then: wait and retry.
    for attempt in range(1, 6):
        req = urllib.request.Request(it["url"], headers={"User-Agent": "jarvis-ci"})
        try:
            with urllib.request.urlopen(req, timeout=600) as r, open(tmp, "wb") as out:
                while True:
                    b = r.read(1 << 20)
                    if not b:
                        break
                    out.write(b)
        except (urllib.error.URLError, OSError) as e:
            print(f"attempt {attempt}: {it['fileName']}: {e}", flush=True)
            if os.path.exists(tmp):
                os.remove(tmp)
            time.sleep(20 * attempt)
            continue
        got = sha256(tmp)
        size = os.path.getsize(tmp)
        if got == it["sha256"] and size == it["size"]:
            break
        os.remove(tmp)
        print(f"attempt {attempt}: SHA-256/size mismatch for {it['fileName']}: {got} ({size} bytes)", flush=True)
    else:
        sys.exit(f"could not fetch {it['fileName']} with the locked SHA-256 after 5 attempts")
    os.replace(tmp, path)
    print("ok", it["fileName"], it["size"])
