#!/usr/bin/env bash
# Keeps the plugin folder inside the Claude plugin directory's file rules, so
# a version is never held for "Files or downloads the validator couldn't
# inspect" or "Ships code the scan can't read" again
# (https://claude.com/docs/plugins/pre-submission-checklist):
#
#   - only text files, plus PNG/JPEG/GIF/WebP images and fonts — no jars,
#     class files, archives or other binaries;
#   - every file that isn't an image or font under 256 KiB;
#   - at most 512 files.
#
# jfrdoc once shipped a 6 MB compiled jar; it now ships source only, and this
# check is what keeps a binary from creeping back in. It inspects the files
# git tracks, since those are what the directory fetches.
set -euo pipefail

REPO_ROOT="$(cd "$(dirname "$0")/.." && pwd)"
cd "$REPO_ROOT"

git ls-files -z | python3 -c '
import os, sys

MEDIA = (".png", ".jpg", ".jpeg", ".gif", ".webp", ".woff", ".woff2", ".ttf", ".otf")
LIMIT = 256 * 1024
MAX_FILES = 512

files = [f for f in sys.stdin.buffer.read().decode().split("\0") if f]
problems = []
if len(files) > MAX_FILES:
    problems.append(f"{len(files)} files tracked; the directory holds plugins over {MAX_FILES}")

for f in files:
    if f.lower().endswith(MEDIA):
        continue
    size = os.path.getsize(f)
    if size > LIMIT:
        problems.append(f"{f}: {size} bytes, over the {LIMIT}-byte limit for non-image files")
    with open(f, "rb") as fh:
        data = fh.read()
    if b"\0" in data:
        problems.append(f"{f}: binary content (NUL bytes) in a non-image file")
        continue
    try:
        data.decode("utf-8")
    except UnicodeDecodeError:
        problems.append(f"{f}: not valid UTF-8 text")

if problems:
    print("ERROR: plugin files the directory scan would hold for a reviewer:")
    for p in problems:
        print("  " + p)
    sys.exit(1)
print(f"OK: {len(files)} tracked files, all readable text (or images) under {LIMIT // 1024} KiB.")
'
