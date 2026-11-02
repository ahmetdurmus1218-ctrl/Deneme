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
        out = adb("shell", "am", "instrument", "-w",
                  f"{KALE}.test/io.github.ahmetdurmus1218.jarvis.probe.WhisperProbe", check=False)
        print(out)
        if "INSTRUMENTATION_RESULT: result=PASS" not in out:
            raise SystemExit("Whisper probe failed")

        step("mic with Whisper installed: tap → listening → feedback")
        adb("shell", "am", "start", "-W", "-n", f"{KALE}/.android.MainActivity")
        tap(wait_for(tab("Sohbet"), "chat tab"))
        mic_feedback()
        shot("11-kale-mikrofon-whisper")
        tap(wait_for(tab("Ayarlar"), "settings tab"))
        wait_for(text_has("Ses tanıma: Whisper small"), "Whisper is the active recognizer")
        check_no_crash()

    print("\nSMOKE TEST PASSED", flush=True)


if __name__ == "__main__":
    main()
