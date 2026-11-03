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
import shutil
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
    return [label(n) for n in nodes(root if root is not None else dump()) if label(n)]


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


def dismiss_system_anr(root):
    """A freshly booted emulator sometimes shows "<System app> isn't responding" (not our app)."""
    texts = screen_texts(root)
    if any(t.endswith("isn't responding") and not t.startswith("Jarvis") for t in texts):
        wait = find(text_is("Wait"), root)
        if wait is not None:
            print("dismissing system ANR dialog:", [t for t in texts if "responding" in t])
            tap(wait)
            time.sleep(1)
            return True
    return False


def wait_for(pred, what, timeout=40):
    end = time.time() + timeout
    root = None
    while time.time() < end:
        root = dump()
        if dismiss_system_anr(root):
            continue
        n = find(pred, root)
        if n is not None:
            return n
        time.sleep(1.5)
    raise SystemExit(f"timeout waiting for {what}; screen shows: {screen_texts(root)[:80]}")


def text_is(t):
    return lambda n: label(n) == t


def text_has(t):
    return lambda n: t in label(n)


def tab(name):
    """Bottom navigation items merge icon and label ("📦, Modeller")."""
    return lambda n: label(n) == name or (label(n).endswith(name) and len(label(n)) <= len(name) + 5)


def text_re(rx):
    r = re.compile(rx)
    return lambda n: bool(r.search(label(n)))


def shot(name):
    os.makedirs("smoke-shots", exist_ok=True)
    png = f"smoke-shots/{name}.png"
    with open(png, "wb") as f:
        f.write(subprocess.run(["adb", "exec-out", "screencap", "-p"], capture_output=True).stdout)
    tool = shutil.which("magick") or shutil.which("convert")
    if tool:  # optional: a small JPEG next to the PNG
        subprocess.run([tool, png, "-resize", "360x", "-quality", "70", png[:-4] + ".jpg"], check=False)
    print(f"[shot] {name}: {' | '.join(screen_texts())}", flush=True)


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


PROBES = "io.github.ahmetdurmus1218.jarvis.test/io.github.ahmetdurmus1218.jarvis.probe.Probes"

# A real (English, 14 MB) Wikipedia ZIM from openzim's test suite, pinned by SHA-256.
TEST_ZIM_URL = "https://raw.githubusercontent.com/openzim/zim-testing-suite/main/data/withns/wikipedia_en_climate_change_mini_2024-06.zim"
TEST_ZIM_SHA = "72db7ae7708d3a4cff918495078901ec027b14b427433a2328286ec130d1c43b"


# MediaPipe's own gesture/face test photos, pinned by SHA-256.
TRACKING_PHOTOS = {
    "thumb_up.jpg": "5d673c081ab13b8a1812269ff57047066f9c33c07db5f4178089e8cb3fdc0291",
    "pointing_up.jpg": "ecf8ca2611d08fa25948a4fc10710af9120e88243a54da6356bacea17ff3e36e",
    "victory.jpg": "84cb8853e3df614e0cb5c93a25e3e2f38ea5e4f92fd428ee7d867ed3479d5764",
    "fist.jpg": "43fa1cabf3f90d574accc9a56986e2ee48638ce59fc65af1846487f73bb2ef24",
    "portrait.jpg": "a6f11efaa834706db23f275b6115058fa87fc7f14362681e6abe14e82749de3e",
}


def fetch_pinned(url, sha, name):
    import hashlib
    import tempfile
    import urllib.request
    path = os.path.join(tempfile.gettempdir(), name)
    urllib.request.urlretrieve(url, path)
    got = hashlib.sha256(open(path, "rb").read()).hexdigest()
    if got != sha:
        raise SystemExit(f"{name}: SHA-256 mismatch {got}")
    return path


def fetch_test_zim():
    import hashlib
    import tempfile
    import urllib.request
    path = os.path.join(tempfile.gettempdir(), "test.zim")
    if not (os.path.isfile(path) and hashlib.sha256(open(path, "rb").read()).hexdigest() == TEST_ZIM_SHA):
        urllib.request.urlretrieve(TEST_ZIM_URL, path)
    got = hashlib.sha256(open(path, "rb").read()).hexdigest()
    if got != TEST_ZIM_SHA:
        raise SystemExit(f"test ZIM SHA-256 mismatch: {got}")
    return path


def scroll_find(pred, what, tries=12):
    """Swipes up until a node matching pred is fully visible above the bottom navigation."""
    for _ in range(tries):
        root = dump()
        nav = find(tab("Ayarlar"), root)
        nav_top = bounds(nav)[1] if nav is not None else 10 ** 6
        for n in nodes(root):
            if pred(n) and bounds(n)[3] < nav_top:
                return n
        adb("shell", "input", "swipe", "540", "1600", "540", "900", "400")
        time.sleep(1)
    raise SystemExit(f"not found after scrolling: {what}")


def mic_feedback():
    baseline = set(screen_texts())
    tap(wait_for(text_is("🎤"), "mic button"))
    allow_labels = ("While using the app", "Only this time", "Allow", "Uygulamayı kullanırken", "İzin ver")
    timeline, new_texts = [], []
    end = time.time() + 20
    while time.time() < end:
        root = dump()
        allow = find(lambda n: label(n) in allow_labels, root)
        if allow is not None:
            timeline.append("[permission prompt → allow]")
            tap(allow)
            time.sleep(1)
            continue
        texts = [t for t in screen_texts(root) if t not in baseline]
        if texts and (not timeline or timeline[-1] != texts):
            timeline.append(texts)
        new_texts += texts
        time.sleep(1)
    print("mic timeline:")
    for t in timeline:
        print("   ", t)
    log = adb("logcat", "-d", "-s", "JarvisMic:*", check=False)
    print("JarvisMic log:\n" + "\n".join("    " + l for l in log.splitlines()[-30:]))
    if not new_texts:
        raise SystemExit("tapping the mic changed nothing on screen")
    # Leave the recognizer idle for the next steps.
    if find(text_is("■")) is not None:
        tap(find(text_is("■")))
        time.sleep(1)


def main():
    apk_dir = sys.argv[1]
    download = "--download" in sys.argv

    step("install")
    adb("install", "-r", find_apk(apk_dir, "androidApp-x86_64-release.apk"))  # the emulator is x86_64
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

    step("microphone: tap → permission → visible feedback (never silent)")
    mic_feedback()
    shot("02b-kale-mikrofon")
    check_no_crash()

    step("gate detected")
    tap(wait_for(tab("Modeller"), "Models tab"))
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
                nav = find(tab("Ayarlar"), root)
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
        tap(wait_for(tab("Ağ"), "network tab"))
        wait_for(text_has("Sonuç: tamam"), "network log entry")
        shot("07-kale-ag-kaydi")
        adb("shell", "am", "start", "-W", "-n", f"{GATE}/.StatusActivity")
        # Android 13+: Kapı asks once for the notification permission (download progress notification).
        allow = text_is("Allow")
        first = wait_for(lambda n: allow(n) or text_has("tamam")(n), "gate log or notification prompt")
        if allow(first):
            tap(first)
        wait_for(text_has("tamam"), "gate log")
        shot("08-kapi-durum")
        check_no_crash()

        step("Whisper from the chat: mic → 'Turkish unsupported' → one-tap download (3 files, one approval)")
        adb("shell", "am", "start", "-W", "-n", f"{KALE}/.android.MainActivity")
        tap(wait_for(tab("Sohbet"), "chat tab"))
        tap(wait_for(text_is("🎤"), "mic button"))
        offer = wait_for(lambda n: label(n).startswith("Jarvis'in ses tanımasını indir"), "Whisper offer in the chat", timeout=30)
        shot("09-kale-whisper-teklif")
        tap(offer)
        wait_for(text_is("İnternet erişimi onayı"), "approval screen")
        wait_for(text_has("huggingface.co"), "target host on approval screen")
        wait_for(text_has("3 dosya indirilecek"), "one approval for the whole group")
        tap(wait_for(text_is("Onayla"), "approve button"))
        time.sleep(4)
        adb("shell", "input", "text", PIN)
        adb("shell", "input", "keyevent", "66")
        seen_progress = False
        end = time.time() + 900
        while time.time() < end:
            texts = screen_texts()
            busy = [t for t in texts if t.startswith("Whisper indiriliyor") or t.startswith("Whisper hazırlanıyor")]
            if busy:
                if not seen_progress:
                    print("progress:", busy[0])
                seen_progress = True
            elif seen_progress:
                if any(t.startswith("Jarvis'in ses tanımasını indir") for t in texts):
                    raise SystemExit("Whisper download failed: " + " | ".join(texts))
                break
            time.sleep(3)
        else:
            raise SystemExit("Whisper download did not finish: " + " | ".join(screen_texts()))
        wait_for(text_has("Ses tanıma hazır"), "'ready' notice replaces the old error in the chat")
        shot("10-kale-whisper-indi")
        tap(wait_for(tab("Modeller"), "Models tab"))
        scroll_find(lambda n: label(n).startswith("Whisper small (tokens)"), "Whisper rows")
        texts = screen_texts()
        if any(t.startswith("İndir (3 dosya") for t in texts):
            raise SystemExit("Whisper group still shows a download button")
        tap(wait_for(tab("Ağ"), "network tab"))
        time.sleep(1)
        ok_entries = sum(1 for t in screen_texts() if "Sonuç: tamam" in t)
        print("network log 'tamam' entries:", ok_entries)
        if ok_entries < 4:
            raise SystemExit("expected Piper + 3 Whisper files in the network log")
        check_no_crash()

        step("Whisper decodes Turkish ON the device with the installed files (same code path as the mic)")
        adb("install", "-r", find_apk(apk_dir, "androidApp-release-androidTest.apk"))
        out = adb("shell", "am", "instrument", "-w", "-e", "probe", "whisper", PROBES, check=False)
        print(out)
        if "INSTRUMENTATION_RESULT: result=PASS" not in out:
            raise SystemExit("Whisper probe failed")

        step("Piper voice ON the device: Jarvis says a sentence, Whisper on the phone must hear it")
        out = adb("shell", "am", "instrument", "-w", "-e", "probe", "piper", PROBES, check=False)
        print(out)
        if "INSTRUMENTATION_RESULT: result=PASS" not in out:
            raise SystemExit("Piper probe failed")

        step("offline Wikipedia ON the device: libzim search + read on a real Wikipedia ZIM")
        zim = fetch_test_zim()
        adb("push", zim, "/data/local/tmp/test.zim")
        out = adb("shell", "am", "instrument", "-w", "-e", "probe", "wiki", "-e", "zim", "/data/local/tmp/test.zim", PROBES, check=False)
        print(out)
        if "INSTRUMENTATION_RESULT: result=PASS" not in out:
            raise SystemExit("Wikipedia probe failed")

        step("camera: open → permission → tracking models through Kapı (storage.googleapis.com) → 'BU NE?'")
        adb("shell", "am", "start", "-W", "-n", f"{KALE}/.android.MainActivity")
        tap(wait_for(tab("Sohbet"), "chat tab"))
        tap(wait_for(text_is("📷"), "camera button"))
        for _ in range(5):
            allow = find(lambda n: label(n) in ("While using the app", "Only this time", "Allow"))
            if allow is not None:
                tap(allow)
                break
            time.sleep(1)
        wait_for(text_is("J.A.R.V.I.S · EL KONTROLÜ"), "camera screen")
        time.sleep(3)
        shot("12a-kale-kamera-acik")
        check_no_crash()
        tap(wait_for(lambda n: label(n).startswith("El ve yüz takibini indir"), "tracking download offer", timeout=30))
        wait_for(text_is("İnternet erişimi onayı"), "approval screen")
        wait_for(text_has("storage.googleapis.com"), "Google host on the approval screen")
        tap(wait_for(text_is("Onayla"), "approve button"))
        time.sleep(4)
        adb("shell", "input", "text", PIN)
        adb("shell", "input", "keyevent", "66")
        wait_for(text_has("✋ ateş"), "gesture legend once tracking is installed", timeout=300)
        shot("12-kale-kamera")
        tap(wait_for(text_is("BU NE?"), "what-is-this button"))
        answer = wait_for(lambda n: "Görmek için gerçek bir model gerekiyor" in label(n) or "görüntüsü alınamadı" in label(n), "vision answer", timeout=60)
        print("vision answer:", label(answer))
        shot("13-kale-kamera-cevap")
        tap(wait_for(text_is("DEMO"), "demo button"))
        for name, wait in (("14-el-ates", 1.5), ("15-el-muhur", 3.5), ("16-el-gezegen", 4.0), ("17-el-simsek", 8.5)):
            time.sleep(wait)
            shot(name)
        check_no_crash()
        tap(wait_for(text_is("← GERİ"), "back"))
        check_no_crash()

        step("hand/face tracking ON the device: real photos → MediaPipe → Jarvis's gesture classifier")
        for name, sha in TRACKING_PHOTOS.items():
            adb("push", fetch_pinned(f"https://storage.googleapis.com/mediapipe-assets/{name}", sha, name), f"/data/local/tmp/{name}")
        out = adb("shell", "am", "instrument", "-w", "-e", "probe", "tracking", "-e", "dir", "/data/local/tmp", PROBES, check=False)
        print(out)
        if "INSTRUMENTATION_RESULT: result=PASS" not in out:
            raise SystemExit("tracking probe failed")

        step("mic with Whisper installed: tap → listening → feedback")
        adb("shell", "am", "start", "-W", "-n", f"{KALE}/.android.MainActivity")
        tap(wait_for(tab("Sohbet"), "chat tab"))
        mic_feedback()
        shot("11-kale-mikrofon-whisper")
        tap(wait_for(tab("Ayarlar"), "settings tab"))
        wait_for(text_has("Ses tanıma: Whisper small"), "Whisper is the active recognizer")
        check_no_crash()

    print("\nSMOKE TEST PASSED", flush=True)


def dump_diagnostics():
    """On failure: crashes, the app's own log tags and the activity on top, for the CI log."""
    print("\n=== diagnostics", flush=True)
    print(adb("logcat", "-d", "-b", "crash", check=False)[-8000:])
    log = adb("logcat", "-d", "-v", "brief", check=False).splitlines()
    keep = [l for l in log if any(k in l for k in ("AndroidRuntime", "JarvisCamera", "JarvisMic", "CameraX", "Camera2", "mediapipe", "F DEBUG", "libc", KALE))]
    print("\n".join(keep[-120:]))
    print(adb("shell", "dumpsys", "activity", "activities", check=False)[:3000])


if __name__ == "__main__":
    try:
        main()
    except SystemExit as e:
        if e.code not in (0, None):
            dump_diagnostics()
        raise
