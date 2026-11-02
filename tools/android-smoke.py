#!/usr/bin/env python3
"""End-to-end smoke test on an Android emulator (run by CI).

1. Installs jarvis.apk (Kale) and jarvis-kapi.apk (Kapı).
2. Checks permissions as the OS granted them: Kale has NO internet permission, Kale holds the
   signature-level GATE permission, Kapı has INTERNET.
3. Launches Kale, skips the wizard, runs a quick command ("saat kac") and checks the answer.
4. Checks that Kale detects Kapı (same signing key).
5. With --download: sets a screen-lock PIN, downloads the smallest locked Android item (the Turkish
   Piper voice) through the real chain: approval screen → PIN → Kapı → pinned HTTPS → SHA-256 in
   Kapı → SHA-256 again in Kale. Then checks the network log and Kapı's own log.

Screenshots are written to smoke-shots/ (PNG + small JPEG).
Usage: python3 tools/android-smoke.py <dir-with-apks> [--download]
"""
import os
import re
import subprocess
import sys
import time
import xml.etree.ElementTree as ET

KALE = "io.github.ahmetdurmus1218.jarvis"
GATE = KALE + ".gate"
PIN = "1234"


def adb(*args, check=True):
    r = subprocess.run(["adb", *args], capture_output=True, text=True, errors="replace")
    if check and r.returncode != 0:
        raise SystemExit(f"adb {' '.join(args)} failed ({r.returncode}): {r.stderr.strip()}")
    return r.stdout


def find_apk(root, name):
    for d, _, files in os.walk(root):
        if name in files:
            return os.path.join(d, name)
    raise SystemExit(f"{name} not found under {root}")


def dump():
    for _ in range(8):
        out = adb("exec-out", "uiautomator", "dump", "/dev/tty", check=False)
        i, j = out.find("<?xml"), out.rfind("</hierarchy>")
        if i >= 0 and j > i:
            return ET.fromstring(out[i:j + len("</hierarchy>")])
        time.sleep(1.5)
    raise SystemExit("uiautomator dump failed")


def label(n):
    return n.get("text") or n.get("content-desc") or ""


def nodes(root):
    return list(root.iter("node"))


def screen_texts(root=None):
    return [label(n) for n in nodes(root or dump()) if label(n)]


def find(pred, root=None):
    for n in nodes(root if root is not None else dump()):
        if pred(n):
            return n
    return None


def bounds(n):
    x1, y1, x2, y2 = map(int, re.findall(r"\d+", n.get("bounds")))
    return x1, y1, x2, y2


def tap(n):
    x1, y1, x2, y2 = bounds(n)
    adb("shell", "input", "tap", str((x1 + x2) // 2), str((y1 + y2) // 2))


def wait_for(pred, what, timeout=40):
    end = time.time() + timeout
    root = None
    while time.time() < end:
        root = dump()
        n = find(pred, root)
        if n is not None:
            return n
        time.sleep(1.5)
    raise SystemExit(f"timeout waiting for {what}; screen shows: {screen_texts(root)[:80]}")


def text_is(t):
    return lambda n: label(n) == t


def text_has(t):
    return lambda n: t in label(n)


def text_re(rx):
    r = re.compile(rx)
    return lambda n: bool(r.search(label(n)))


def shot(name):
    os.makedirs("smoke-shots", exist_ok=True)
    png = f"smoke-shots/{name}.png"
    with open(png, "wb") as f:
        f.write(subprocess.run(["adb", "exec-out", "screencap", "-p"], capture_output=True).stdout)
    subprocess.run(["convert", png, "-resize", "360x", "-quality", "70", png[:-4] + ".jpg"], check=False)
    print(f"[shot] {name}", flush=True)


def check_no_crash():
    log = adb("logcat", "-d", "-b", "crash", check=False)
    if KALE in log or "FATAL EXCEPTION" in log:
        raise SystemExit("crash detected:\n" + log[-6000:])


def section(text, start, stop):
    i = text.find(start)
    if i < 0:
        return ""
    j = text.find(stop, i + len(start))
    return text[i:j if j > 0 else None]


def step(msg):
    print(f"\n=== {msg}", flush=True)


def main():
    apk_dir = sys.argv[1]
    download = "--download" in sys.argv

    step("install")
    adb("install", "-r", find_apk(apk_dir, "androidApp-release.apk"))
    adb("install", "-r", find_apk(apk_dir, "gate-release.apk"))

    step("permissions as granted by Android")
    kale = adb("shell", "dumpsys", "package", KALE)
    requested = section(kale, "requested permissions:", "install permissions:")
    print(requested)
    if "android.permission.INTERNET" in requested or "android.permission.INTERNET" in section(kale, "install permissions:", "runtime permissions:"):
        raise SystemExit("Kale must not have android.permission.INTERNET")
    if not re.search(re.escape(KALE) + r"\.permission\.GATE: granted=true", kale):
        raise SystemExit("Kale was not granted the signature-level GATE permission")
    gate = adb("shell", "dumpsys", "package", GATE)
    if "android.permission.INTERNET" not in gate:
        raise SystemExit("Kapı should hold android.permission.INTERNET")
    print("OK: Kale has no INTERNET permission; GATE permission granted; Kapı has INTERNET")

    step("launch Kale")
    adb("logcat", "-c")
    adb("shell", "am", "start", "-W", "-n", f"{KALE}/.android.MainActivity")
    wait_for(text_is("Kurulum sihirbazı"), "wizard")
    shot("01-kale-sihirbaz")
    tap(wait_for(text_is("Şimdilik atla (deneme modu)"), "skip button"))

    step("quick command")
    field = wait_for(lambda n: n.get("class") == "android.widget.EditText", "text field")
    tap(field)
    adb("shell", "input", "text", "saat%skac")
    tap(wait_for(text_is("Gönder"), "send button"))
    answer = wait_for(text_re(r"^Saat \d\d:\d\d\.$"), "time answer")
    print("answer:", label(answer))
    shot("02-kale-saat")

    step("gate detected")
    tap(wait_for(text_is("Modeller"), "Models tab"))
    wait_for(text_has("İndirme yolu: Jarvis Kapı"), "gate status")
    shot("03-kale-modeller")
    check_no_crash()

    if download:
        step("real download through Kapı: approval → PIN → pinned HTTPS → SHA-256 x2")
        adb("shell", "locksettings", "set-pin", PIN)
        target = None
        for _ in range(8):
            root = dump()
            ns = nodes(root)
            idx = next((i for i, n in enumerate(ns) if label(n).startswith("Piper Türkçe ses")), None)
            if idx is not None:
                after = [n for n in ns[idx + 1:] if label(n) == "İndir"]
                nav = find(text_is("Ayarlar"), root)
                nav_top = bounds(nav)[1] if nav is not None else 10 ** 6
                if after and bounds(after[0])[3] < nav_top:
                    target = after[0]
                    break
            adb("shell", "input", "swipe", "540", "1600", "540", "900", "400")
            time.sleep(1)
        if target is None:
            raise SystemExit("could not find the download button for the Piper voice")
        tap(target)
        wait_for(text_is("İnternet erişimi onayı"), "approval screen")
        wait_for(text_has("github.com"), "target host on approval screen")
        shot("04-kale-onay")
        tap(wait_for(text_is("Onayla"), "approve button"))
        time.sleep(4)
        shot("05-pin")
        adb("shell", "input", "text", PIN)
        adb("shell", "input", "keyevent", "66")
        wait_for(text_has("SHA-256 doğrulandı"), "verified install", timeout=300)
        shot("06-kale-kuruldu")
        tap(wait_for(text_is("Ağ"), "network tab"))
        wait_for(text_has("Sonuç: tamam"), "network log entry")
        shot("07-kale-ag-kaydi")
        adb("shell", "am", "start", "-W", "-n", f"{GATE}/.StatusActivity")
        wait_for(text_has("tamam"), "gate log")
        shot("08-kapi-durum")
        check_no_crash()

    print("\nSMOKE TEST PASSED", flush=True)


if __name__ == "__main__":
    main()
