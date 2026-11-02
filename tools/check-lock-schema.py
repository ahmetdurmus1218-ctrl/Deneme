#!/usr/bin/env python3
"""Validate models.lock.json: schema, HTTPS + host allowlist, extension allowlist and hash format.

Mirrors the runtime rules in core (DownloadPolicy). Exit code 1 on any violation.
"""
import json
import re
import sys
from urllib.parse import urlparse

ALLOWED_HOSTS = [r"huggingface\.co", r".+\.huggingface\.co", r"hf\.co", r".+\.hf\.co",
                 r"download\.kiwix\.org", r".+\.download\.kiwix\.org",
                 r"github\.com", r"objects\.githubusercontent\.com", r"release-assets\.githubusercontent\.com"]
EXT_ANDROID = (".litertlm", ".onnx", ".gguf", ".zim", ".tar.bz2", ".json", ".txt")
EXT_DESKTOP_BINARY = (".zip",)
FORBIDDEN = (".apk", ".dex", ".so", ".jar", ".exe", ".dll", ".bat", ".cmd", ".ps1", ".sh", ".msi")
KINDS = {"llm", "stt", "tts", "zim", "embedding", "binary"}
PLATFORMS = {"android", "desktop"}
REQUIRED = {"id": str, "kind": str, "platforms": list, "title": str, "group": str, "url": str, "host": str,
            "fileName": str, "size": int, "sha256": str, "revision": str, "origin": str}


def main(path):
    errors = []
    lock = json.load(open(path, encoding="utf-8"))
    if lock.get("schemaVersion") != 1:
        errors.append("schemaVersion must be 1")
    ids = set()
    for i, it in enumerate(lock.get("items", [])):
        tag = it.get("id", f"#{i}")
        for k, t in REQUIRED.items():
            if not isinstance(it.get(k), t):
                errors.append(f"{tag}: field '{k}' missing or not {t.__name__}")
        if tag in ids:
            errors.append(f"{tag}: duplicate id")
        ids.add(tag)
        if it.get("kind") not in KINDS:
            errors.append(f"{tag}: unknown kind {it.get('kind')}")
        if not set(it.get("platforms", [])) <= PLATFORMS or not it.get("platforms"):
            errors.append(f"{tag}: bad platforms {it.get('platforms')}")
        url = urlparse(it.get("url", ""))
        if url.scheme != "https":
            errors.append(f"{tag}: url must be https")
        if url.hostname != it.get("host"):
            errors.append(f"{tag}: host field does not match url")
        if not any(re.fullmatch(p, url.hostname or "") for p in ALLOWED_HOSTS):
            errors.append(f"{tag}: host {url.hostname} not in allowlist")
        name = it.get("fileName", "").lower()
        if name.endswith(FORBIDDEN):
            errors.append(f"{tag}: forbidden extension {name}")
        if "android" in it.get("platforms", []) and not name.endswith(EXT_ANDROID):
            errors.append(f"{tag}: extension not allowed on android: {name}")
        if not name.endswith(EXT_ANDROID) and not (it.get("kind") == "binary" and name.endswith(EXT_DESKTOP_BINARY)
                                                    and it.get("platforms") == ["desktop"]):
            errors.append(f"{tag}: extension not allowed: {name}")
        if not re.fullmatch(r"[0-9a-f]{64}", it.get("sha256", "")):
            errors.append(f"{tag}: sha256 must be 64 lowercase hex chars")
        if not isinstance(it.get("size"), int) or it["size"] <= 0:
            errors.append(f"{tag}: size must be positive")
    for e in errors:
        print("ERROR", e)
    print(f"{path}: {len(lock.get('items', []))} items, {len(errors)} errors")
    return 1 if errors else 0


if __name__ == "__main__":
    sys.exit(main(sys.argv[1] if len(sys.argv) > 1 else "models.lock.json"))
