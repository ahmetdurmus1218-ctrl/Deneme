#!/usr/bin/env python3
"""Resolve latest stable library versions and candidate model files from official sources.

Runs in CI (the dev container has no access to Google Maven / Hugging Face / Kiwix).
Output is plain text in the job log; nothing is written to the repo.
"""
import json
import os
import re
import sys
import urllib.parse
import urllib.request

UNSTABLE = re.compile(r"(alpha|beta|rc|dev|eap|snapshot|-m\d|preview|pre)", re.I)
TOKEN = os.environ.get("GITHUB_TOKEN")


def get(url, accept=None):
    req = urllib.request.Request(url, headers={"User-Agent": "jarvis-probe"})
    if accept:
        req.add_header("Accept", accept)
    if TOKEN and "api.github.com" in url:
        req.add_header("Authorization", f"Bearer {TOKEN}")
    with urllib.request.urlopen(req, timeout=60) as r:
        return r.read().decode("utf-8", "replace")


def vkey(v):
    return [int(p) if p.isdigit() else p for p in re.split(r"[.\-]", v)]


def maven(base, coord, n=3):
    g, a = coord.split(":")
    url = f"{base}/{g.replace('.', '/')}/{a}/maven-metadata.xml"
    try:
        xml = get(url)
        vs = [v for v in re.findall(r"<version>([^<]+)</version>", xml) if not UNSTABLE.search(v)]
        vs.sort(key=lambda v: [str(x).zfill(8) if isinstance(x, int) else x for x in vkey(v)])
        print(f"MAVEN {coord}: {' '.join(vs[-n:])}")
    except Exception as e:  # noqa: BLE001
        print(f"MAVEN {coord}: ERROR {e}")


GOOGLE = "https://dl.google.com/android/maven2"
CENTRAL = "https://repo1.maven.org/maven2"
PORTAL = "https://plugins.gradle.org/m2"

GOOGLE_COORDS = [
    "com.android.tools.build:gradle",
    "com.android.application:com.android.application.gradle.plugin",
    "com.android.kotlin.multiplatform.library:com.android.kotlin.multiplatform.library.gradle.plugin",
    "androidx.core:core-ktx",
    "androidx.activity:activity-compose",
    "androidx.biometric:biometric",
    "androidx.fragment:fragment-ktx",
    "androidx.compose.ui:ui",
    "androidx.work:work-runtime-ktx",
    "androidx.room:room-runtime",
    "androidx.sqlite:sqlite-bundled",
    "androidx.lifecycle:lifecycle-runtime-ktx",
    "androidx.lifecycle:lifecycle-service",
    "androidx.test.ext:junit",
    "com.google.ai.edge.litertlm:litertlm-android",
    "com.google.ai.edge.litert:litert",
]
CENTRAL_COORDS = [
    "org.jetbrains.kotlin:kotlin-gradle-plugin",
    "org.jetbrains.kotlinx:kotlinx-coroutines-core",
    "org.jetbrains.kotlinx:kotlinx-serialization-json",
    "org.jetbrains.kotlinx:kotlinx-datetime",
    "org.jetbrains.compose.material3:material3",
    "org.jetbrains.compose.ui:ui",
    "org.jetbrains.compose.material:material-icons-core",
    "com.squareup.okhttp3:okhttp",
    "net.zetetic:sqlcipher-android",
    "io.github.willena:sqlite-jdbc",
    "org.kiwix:libkiwix",
    "org.kiwix.kiwixlib:kiwixlib",
    "com.k2fsa.sherpa.onnx:sherpa-onnx",
]
PORTAL_COORDS = [
    "org.jetbrains.compose:org.jetbrains.compose.gradle.plugin",
]


def hf_search(q, author=None, limit=15):
    url = f"https://huggingface.co/api/models?search={q}&limit={limit}&sort=downloads"
    if author:
        url += f"&author={author}"
    try:
        for m in json.loads(get(url)):
            print(f"HF-SEARCH {q}: {m['id']}  downloads={m.get('downloads')}")
    except Exception as e:  # noqa: BLE001
        print(f"HF-SEARCH {q}: ERROR {e}")


def hf_files(repo, pattern=r"."):
    try:
        info = json.loads(get(f"https://huggingface.co/api/models/{repo}?blobs=true"))
        print(f"HF-REPO {repo}: sha={info.get('sha')} license={info.get('cardData', {}).get('license')} gated={info.get('gated')}")
        for s in info.get("siblings", []):
            if re.search(pattern, s["rfilename"]):
                lfs = s.get("lfs") or {}
                print(f"  {s['rfilename']} size={s.get('size') or lfs.get('size')} sha256={lfs.get('sha256')}")
    except Exception as e:  # noqa: BLE001
        print(f"HF-REPO {repo}: ERROR {e}")


def gh_release(repo, pattern, n=1):
    try:
        rels = json.loads(get(f"https://api.github.com/repos/{repo}/releases?per_page=5"))
        rels = [r for r in rels if not r.get("draft")][:n]
        for r in rels:
            print(f"GH {repo}: {r['tag_name']} {r['published_at']}")
            for a in r["assets"]:
                if re.search(pattern, a["name"]):
                    print(f"  {a['name']} size={a['size']} digest={a.get('digest')} url={a['browser_download_url']}")
    except Exception as e:  # noqa: BLE001
        print(f"GH {repo}: ERROR {e}")


def kiwix(path, pattern, limit=4):
    try:
        html = get(f"https://download.kiwix.org/{path}")
        names = sorted(set(n for n in re.findall(r'href="([^"]+)"', html) if re.search(pattern, n)))
        for nme in names[-limit:]:
            print(f"KIWIX {path}{nme}")
    except Exception as e:  # noqa: BLE001
        print(f"KIWIX {path}: ERROR {e}")


def android_sdk():
    try:
        xml = get("https://dl.google.com/android/repository/repository2-3.xml")
        plats = sorted(set(re.findall(r'path="platforms;android-(\d+(?:\.\d+)?)"', xml)), key=lambda v: float(v))
        bts = sorted(set(re.findall(r'path="build-tools;([\d.]+)"', xml)), key=vkey)
        print("SDK platforms:", " ".join(plats[-8:]))
        print("SDK build-tools:", " ".join(bts[-5:]))
    except Exception as e:  # noqa: BLE001
        print(f"SDK: ERROR {e}")


def redirects(url, depth=0):
    if depth > 5:
        return
    try:
        req = urllib.request.Request(url, method="HEAD", headers={"User-Agent": "jarvis-probe"})
        class NoRedirect(urllib.request.HTTPRedirectHandler):
            def redirect_request(self, *a, **k):
                return None
        opener = urllib.request.build_opener(NoRedirect)
        try:
            r = opener.open(req, timeout=60)
            print(f"REDIRECT {url}: {r.status} (no redirect)")
        except urllib.error.HTTPError as e:
            loc = e.headers.get('Location')
            print(f"REDIRECT {url[:100]}: {e.code} -> {(loc or '')[:120]}")
            if loc and e.code in (301, 302, 303, 307, 308):
                redirects(urllib.parse.urljoin(url, loc), depth + 1)
    except Exception as e:  # noqa: BLE001
        print(f"REDIRECT {url}: ERROR {e}")


def main():
    section = sys.argv[1] if len(sys.argv) > 1 else "all"
    if section in ("all", "maven"):
        for c in GOOGLE_COORDS:
            maven(GOOGLE, c)
        for c in CENTRAL_COORDS:
            maven(CENTRAL, c)
        for c in PORTAL_COORDS:
            maven(PORTAL, c)
    if section in ("all", "sdk"):
        android_sdk()
    if section == "all":
        print("GRADLE 9.8.2 sha256:", get("https://services.gradle.org/distributions/gradle-9.8.2-bin.zip.sha256").strip())
    if section in ("all", "models"):
        hf_search("gemma-4", author="litert-community")
        hf_files("litert-community/gemma-4-E4B-it-litert-lm", r"\.litertlm$")
        hf_search("gemma-4-E4B")
        hf_search("Qwen3.5-9B-GGUF")
        hf_search("gemma-4-E4B-it-GGUF")
        hf_search("sherpa-onnx-whisper", limit=8)
        hf_files("litert-community/gemma-4-E2B-it-litert-lm", r"^[^/]*\.litertlm$")
        hf_files("unsloth/Qwen3.5-9B-GGUF", r"Q4_K_M")
        hf_files("ggml-org/gemma-4-E4B-it-GGUF", r"Q4")
        hf_files("rhasspy/piper-voices", r"^tr/.*(onnx|json)$")

        kiwix("zim/wikipedia/", r"wikipedia_tr_all")
        kiwix("release/kiwix-tools/", r"win-x86_64.*zip$")
        kiwix("release/kiwix-tools/", r"win-x86_64.*sha256$")
        kiwix("zim/wikipedia/", r"wikipedia_tr_all_nopic.*(sha256|md5)$")
        redirects("https://download.kiwix.org/zim/wikipedia/wikipedia_tr_all_nopic_2026-09.zim")
        for u in ["https://download.kiwix.org/zim/wikipedia/wikipedia_tr_all_nopic_2026-09.zim.sha256",
                  "https://download.kiwix.org/release/kiwix-tools/kiwix-tools_win-x86_64-3.8.1.zip.sha256"]:
            try:
                print(f"SHA {u}: {get(u).strip()[:200]}")
            except Exception as e:  # noqa: BLE001
                print(f"SHA {u}: ERROR {e}")
        redirects("https://huggingface.co/rhasspy/piper-voices/resolve/main/tr/tr_TR/dfki/medium/tr_TR-dfki-medium.onnx")
        redirects("https://github.com/k2-fsa/sherpa-onnx/releases/download/v2.0.1/sherpa-onnx-2.0.1.aar")
        gh_release("ggml-org/llama.cpp", r"win.*x64")
        gh_release("k2-fsa/sherpa-onnx", r"(\.aar$|win-x64.*jni|sherpa-onnx-v[\d.]+\.jar|java)")
        gh_release("google-ai-edge/LiteRT-LM", r"android|aar")


if __name__ == "__main__":
    main()
