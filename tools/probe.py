#!/usr/bin/env python3
"""Resolve latest stable library versions and candidate model files from official sources.

Runs in CI (the dev container has no access to Google Maven / Hugging Face / Kiwix).
Output is plain text in the job log; nothing is written to the repo.
"""
import json
import os
import re
import sys
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
        rels = [r for r in rels if not r.get("prerelease") and not r.get("draft")][:n]
        for r in rels:
            print(f"GH {repo}: {r['tag_name']} {r['published_at']}")
            for a in r["assets"]:
                if re.search(pattern, a["name"]):
                    print(f"  {a['name']} size={a['size']} digest={a.get('digest')} url={a['browser_download_url']}")
    except Exception as e:  # noqa: BLE001
        print(f"GH {repo}: ERROR {e}")


def kiwix(path, pattern):
    try:
        html = get(f"https://download.kiwix.org/{path}")
        names = sorted(set(re.findall(r'href="([^"]+)"', html)))
        for nme in names:
            if re.search(pattern, nme):
                print(f"KIWIX {path}{nme}")
    except Exception as e:  # noqa: BLE001
        print(f"KIWIX {path}: ERROR {e}")


def main():
    section = sys.argv[1] if len(sys.argv) > 1 else "all"
    if section in ("all", "maven"):
        for c in GOOGLE_COORDS:
            maven(GOOGLE, c)
        for c in CENTRAL_COORDS:
            maven(CENTRAL, c)
        for c in PORTAL_COORDS:
            maven(PORTAL, c)
    if section in ("all", "models"):
        hf_search("gemma-4", author="litert-community")
        hf_search("gemma-4-E4B")
        hf_search("Qwen3.5-9B-GGUF")
        hf_search("gemma-4-E4B-it-GGUF")
        hf_search("sherpa-onnx-whisper")
        hf_search("multilingual-e5-small")
        for repo in ["litert-community/gemma-4-E4B-it-litert-lm", "litert-community/gemma-4-E2B-it-litert-lm",
                     "Qwen/Qwen3.5-9B-GGUF", "unsloth/Qwen3.5-9B-GGUF", "ggml-org/gemma-4-E4B-it-GGUF",
                     "unsloth/gemma-4-E4B-it-GGUF"]:
            hf_files(repo, r"\.(litertlm|gguf|task)$|README")
        hf_files("rhasspy/piper-voices", r"^tr/")
        hf_files("csukuangfj/sherpa-onnx-whisper-turbo", r".")
        hf_files("csukuangfj/sherpa-onnx-whisper-small", r".")
        kiwix("zim/wikipedia/", r"wikipedia_tr_all")
        kiwix("release/kiwix-tools/", r"win|linux-x86_64")
        gh_release("ggml-org/llama.cpp", r"win.*(vulkan|cpu).*x64")
        gh_release("k2-fsa/sherpa-onnx", r"(\.aar$|win-x64.*jni|sherpa-onnx-v[\d.]+\.jar|java)")
        gh_release("kiwix/java-libkiwix", r".")
        gh_release("google-ai-edge/LiteRT-LM", r".")
        gh_release("k2-fsa/sherpa-onnx", r"vits-piper-tr", n=1)


if __name__ == "__main__":
    main()
