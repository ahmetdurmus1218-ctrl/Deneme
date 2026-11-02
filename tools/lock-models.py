#!/usr/bin/env python3
"""Resolve every entry of tools/models.select.json to an exact, verifiable download and write models.lock.json.

For each item the lock records the immutable URL (Hugging Face commit revision, GitHub release asset,
Kiwix master-mirror path), the byte size and the SHA-256. Sources:
  * Hugging Face: /api/models/<repo>?blobs=true  (commit sha + LFS sha256); non-LFS files are hashed locally.
  * GitHub Releases: asset "digest" field; hashed locally when absent.
  * Kiwix ZIM: the official <file>.sha256 published next to each ZIM.
  * Kiwix release binaries: hashed locally (no published checksum).

Usage: python3 tools/lock-models.py [--select tools/models.select.json] [--out models.lock.json]
Needs network access to huggingface.co, api.github.com and download.kiwix.org (run it in CI).
"""
import argparse
import datetime as dt
import hashlib
import json
import os
import re
import sys
import urllib.parse
import urllib.request

UA = {"User-Agent": "jarvis-lock-models/1"}
TOKEN = os.environ.get("GITHUB_TOKEN")
KIWIX_LIST = "https://download.kiwix.org/"
KIWIX_MIRROR = "https://mirror.download.kiwix.org/"


def request(url, method="GET"):
    req = urllib.request.Request(url, method=method, headers=dict(UA))
    if TOKEN and url.startswith("https://api.github.com/"):
        req.add_header("Authorization", f"Bearer {TOKEN}")
        req.add_header("Accept", "application/vnd.github+json")
    return urllib.request.urlopen(req, timeout=120)


def get_text(url):
    with request(url) as r:
        return r.read().decode("utf-8", "replace")


def get_json(url):
    return json.loads(get_text(url))


def sha256_of_url(url):
    h = hashlib.sha256()
    size = 0
    with request(url) as r:
        while True:
            chunk = r.read(1 << 20)
            if not chunk:
                break
            h.update(chunk)
            size += len(chunk)
    return h.hexdigest(), size


def head_no_redirect(url):
    class NoRedirect(urllib.request.HTTPRedirectHandler):
        def redirect_request(self, *a, **k):
            return None

    opener = urllib.request.build_opener(NoRedirect)
    req = urllib.request.Request(url, method="HEAD", headers=dict(UA))
    with opener.open(req, timeout=120) as r:
        return r.status, int(r.headers.get("Content-Length") or 0)


def version_key(v):
    return [int(x) for x in re.findall(r"\d+", v)]


def resolve_hf(src):
    repo, file = src["repo"], src["file"]
    info = get_json(f"https://huggingface.co/api/models/{repo}?blobs=true")
    rev = info["sha"]
    sib = next((s for s in info.get("siblings", []) if s["rfilename"] == file), None)
    if sib is None:
        raise SystemExit(f"{repo}: file {file} not found")
    url = f"https://huggingface.co/{repo}/resolve/{rev}/{urllib.parse.quote(file)}"
    lfs = sib.get("lfs") or {}
    if lfs.get("sha256"):
        sha, size = lfs["sha256"], int(lfs.get("size") or sib.get("size"))
    else:
        sha, size = sha256_of_url(url)
    return {"url": url, "fileName": file.split("/")[-1], "size": size, "sha256": sha,
            "revision": rev, "origin": f"hf:{repo}@{rev}/{file}"}


def release_assets(repo, rel):
    assets, page = [], 1
    while True:
        batch = get_json(f"https://api.github.com/repos/{repo}/releases/{rel['id']}/assets?per_page=100&page={page}")
        assets += batch
        if len(batch) < 100:
            break
        page += 1
    return assets


def github_assets(repo, tag, matches):
    """Returns (release, assets). For tag "latest" picks the newest non-draft release that has a
    matching asset (some projects, e.g. llama.cpp, mark every build as a pre-release)."""
    if tag != "latest":
        rel = get_json(f"https://api.github.com/repos/{repo}/releases/tags/{tag}")
        return rel, release_assets(repo, rel)
    for rel in get_json(f"https://api.github.com/repos/{repo}/releases?per_page=10"):
        if rel.get("draft"):
            continue
        assets = release_assets(repo, rel)
        if any(matches(a["name"]) for a in assets):
            return rel, assets
    raise SystemExit(f"{repo}: no recent release with a matching asset")


def resolve_github(src):
    if "asset" in src:
        matches = lambda name: name == src["asset"]  # noqa: E731
    else:
        rx = re.compile(src["assetRegex"])
        matches = lambda name: bool(rx.search(name))  # noqa: E731
    rel, assets = github_assets(src["repo"], src.get("tag", "latest"), matches)
    match = [a for a in assets if matches(a["name"])]
    if len(match) != 1:
        raise SystemExit(f"{src['repo']}@{rel['tag_name']}: expected 1 asset, found {[a['name'] for a in match]}")
    a = match[0]
    url = a["browser_download_url"]
    digest = a.get("digest") or ""
    if digest.startswith("sha256:"):
        sha, size = digest.split(":", 1)[1], a["size"]
    else:
        sha, size = sha256_of_url(url)
    return {"url": url, "fileName": a["name"], "size": size, "sha256": sha,
            "revision": rel["tag_name"], "origin": f"github:{src['repo']}@{rel['tag_name']}/{a['name']}"}


def kiwix_listing(directory):
    html = get_text(KIWIX_LIST + directory)
    return sorted(set(re.findall(r'href="([^"?/][^"]*)"', html)))


def resolve_kiwix_zim(src):
    names = [n for n in kiwix_listing(src["dir"]) if n.startswith(src["prefix"]) and n.endswith(".zim")]
    if not names:
        raise SystemExit(f"no ZIM with prefix {src['prefix']}")
    name = sorted(names)[-1]
    sha_line = get_text(KIWIX_LIST + src["dir"] + name + ".sha256").split()
    sha = sha_line[0].lower()
    url = KIWIX_MIRROR + src["dir"] + name
    status, size = head_no_redirect(url)
    if status != 200 or size <= 0:
        raise SystemExit(f"mirror did not serve {url} directly (status {status})")
    return {"url": url, "fileName": name, "size": size, "sha256": sha,
            "revision": name.rsplit("_", 1)[-1].removesuffix(".zim"), "origin": f"kiwix:{src['dir']}{name}"}


def resolve_kiwix_release(src):
    rx = re.compile(src["regex"])
    names = [n for n in kiwix_listing(src["dir"]) if rx.search(n)]
    if not names:
        raise SystemExit(f"no release matches {src['regex']}")
    name = max(names, key=lambda n: version_key(rx.search(n).group(1)))
    url = KIWIX_MIRROR + src["dir"] + name
    status, _ = head_no_redirect(url)
    if status != 200:
        raise SystemExit(f"mirror did not serve {url} directly (status {status})")
    sha, size = sha256_of_url(url)
    return {"url": url, "fileName": name, "size": size, "sha256": sha,
            "revision": rx.search(name).group(1), "origin": f"kiwix:{src['dir']}{name}"}


RESOLVERS = {"hf": resolve_hf, "github": resolve_github, "kiwix": resolve_kiwix_zim, "kiwix-release": resolve_kiwix_release}


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--select", default="tools/models.select.json")
    ap.add_argument("--out", default="models.lock.json")
    args = ap.parse_args()
    select = json.load(open(args.select, encoding="utf-8"))
    items = []
    for entry in select["items"]:
        src = entry["source"]
        resolved = RESOLVERS[src["type"]](src)
        item = {k: v for k, v in entry.items() if k != "source"}
        item.update(resolved)
        item["host"] = urllib.parse.urlparse(item["url"]).hostname
        items.append(item)
        print(f"locked {item['id']}: {item['fileName']} {item['size']} B sha256={item['sha256']}", file=sys.stderr)
    lock = {
        "schemaVersion": 1,
        "generatedAt": dt.datetime.now(dt.timezone.utc).replace(microsecond=0).isoformat().replace("+00:00", "Z"),
        "generator": "tools/lock-models.py",
        "items": items,
    }
    with open(args.out, "w", encoding="utf-8") as f:
        json.dump(lock, f, indent=2, ensure_ascii=False)
        f.write("\n")


if __name__ == "__main__":
    main()
