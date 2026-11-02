#!/usr/bin/env bash
# :core must contain no networking code (section 7). Any import of a network API breaks the build.
set -euo pipefail
cd "$(dirname "$0")/.."
PATTERN='^\s*import\s+(java\.net\.|javax\.net\.|okhttp3\.|io\.ktor\.client|java\.net\.http|android\.net\.|com\.squareup\.okhttp|org\.apache\.http|retrofit2\.)'
if grep -RInE "$PATTERN" core/src; then
  echo "::error::Network API imported in :core (see lines above)"
  exit 1
fi
# Also forbid fully-qualified usages without import.
if grep -RInE '\b(java\.net\.(URL|Socket|HttpURLConnection|URI)|okhttp3\.|io\.ktor\.client)' core/src; then
  echo "::error::Network API referenced in :core (see lines above)"
  exit 1
fi
# And forbid network libraries in core's build script.
if grep -nE 'okhttp|ktor|retrofit' core/build.gradle.kts; then
  echo "::error::Network library declared in core/build.gradle.kts"
  exit 1
fi
echo "OK: :core has no network code."
