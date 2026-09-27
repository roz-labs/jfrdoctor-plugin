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

# `git ls-files -s` gives each entry's mode, so symlinks (120000) and
# submodules (160000) are reported instead of followed.
# The Python program below is wrapped in bash single quotes: it must not
# contain an apostrophe anywhere, comments included.
git ls-files -s -z | python3 -c '
import os, sys

LIMIT = 256 * 1024            # non-image files
MEDIA_LIMIT = 5 * 1024 * 1024 # images/fonts (the directory caps any file at 5 MiB)
MAX_FILES = 512

# Images and fonts are identified by content, not by name, so a jar renamed
# to logo.png is still caught — and by structure, not only their first bytes:
# a PNG/JPEG/GIF must end where the format ends and the RIFF size of a WebP must
# match, so nothing can ride along after a valid header. Any media file that
# contains a zip directory or a class-file header is rejected outright.
MAGIC = {
    ".png": [b"\x89PNG\r\n\x1a\n"],
    ".jpg": [b"\xff\xd8\xff"], ".jpeg": [b"\xff\xd8\xff"],
    ".gif": [b"GIF87a", b"GIF89a"],
    ".webp": [b"RIFF"],
    ".woff": [b"wOFF"], ".woff2": [b"wOF2"],
    ".ttf": [b"\x00\x01\x00\x00", b"true"], ".otf": [b"OTTO"],
}

entries = [e for e in sys.stdin.buffer.read().decode().split("\0") if e]
problems = []
if len(entries) > MAX_FILES:
    problems.append(f"{len(entries)} files tracked; the directory holds plugins over {MAX_FILES}")

for entry in entries:
    meta, path = entry.split("\t", 1)
    mode = meta.split()[0]
    if mode == "120000":
        problems.append(f"{path}: symbolic link (commit the file itself)")
        continue
    if mode == "160000":
        problems.append(f"{path}: git submodule (commit regular files)")
        continue
    with open(path, "rb") as fh:
        data = fh.read()
    ext = os.path.splitext(path)[1].lower()
    if ext in MAGIC:
        ok = any(data.startswith(m) for m in MAGIC[ext])
        if ext == ".png":
            ok = ok and data.endswith(b"IEND\xaeB`\x82")
        elif ext in (".jpg", ".jpeg"):
            ok = ok and data.rstrip(b"\x00").endswith(b"\xff\xd9")
        elif ext == ".gif":
            ok = ok and data.endswith(b";")
        elif ext == ".webp":
            ok = ok and data[8:12] == b"WEBP" and int.from_bytes(data[4:8], "little") + 8 == len(data)
        if ok and (b"PK\x03\x04" in data or b"PK\x05\x06" in data or b"\xca\xfe\xba\xbe" in data):
            ok = False
        if not ok:
            problems.append(f"{path}: named like an image/font but its content is not one")
        elif len(data) > MEDIA_LIMIT:
            problems.append(f"{path}: {len(data)} bytes, over the {MEDIA_LIMIT}-byte per-file limit")
        continue
    if len(data) > LIMIT:
        problems.append(f"{path}: {len(data)} bytes, over the {LIMIT}-byte limit for non-image files")
    if b"\0" in data:
        problems.append(f"{path}: binary content (NUL bytes) in a non-image file")
        continue
    try:
        data.decode("utf-8")
    except UnicodeDecodeError:
        problems.append(f"{path}: not valid UTF-8 text")

if problems:
    print("ERROR: plugin files the directory scan would hold for a reviewer:")
    for p in problems:
        print("  " + p)
    sys.exit(1)
print(f"OK: {len(entries)} tracked files, all readable text (or real images) within the size limits.")
'
