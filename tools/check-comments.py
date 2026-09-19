#!/usr/bin/env python3
"""Run from the repo root: python3 tools/check-comments.py [dir]

Guards the Kotlin trap that eats whole files: block comments NEST, so any stray `/*`
inside a KDoc swallows the rest of the file. Reports files whose block-comment depth does not
return to zero. Line comments and strings (including raw `\"\"\"`) are skipped."""
import glob
import sys

ROOT = sys.argv[1] if len(sys.argv) > 1 else 'android/app/src'
files = glob.glob(ROOT + '/**/*.kt', recursive=True)
bad = []
for f in files:
    src = open(f, encoding='utf-8', errors='replace').read()
    i, d, n = 0, 0, len(src)
    while i < n:
        if d > 0:
            # inside a block comment, Kotlin still nests /* */ - and apostrophes in prose ("app's")
            # are NOT string starts, so nothing else is scanned here
            if src.startswith('/*', i):
                d += 1
                i += 2
                continue
            if src.startswith('*/', i):
                d -= 1
                i += 2
                continue
            i += 1
            continue
        if src.startswith('/*', i):
            d += 1
            i += 2
            continue
        if src.startswith('//', i):
            j = src.find('\n', i)
            i = n if j < 0 else j
            continue
        if src[i] == '"':
            if src.startswith('"""', i):
                j = src.find('"""', i + 3)
                i = n if j < 0 else j + 3
                continue
            i += 1
            while i < n:
                if src[i] == '\\':
                    i += 2
                    continue
                if src[i] == '"':
                    i += 1
                    break
                i += 1
            continue
        if src[i] == "'":
            i += 1
            while i < n:
                if src[i] == '\\':
                    i += 2
                    continue
                if src[i] == "'":
                    i += 1
                    break
                i += 1
            continue
        i += 1
    if d != 0:
        bad.append((f, 'unbalanced block comments, depth=%d' % d))

print('scanned %d kotlin files' % len(files))
for f, why in bad:
    print('BAD', f, '->', why)
if not bad:
    print('comments balanced in every file')
# CI reads the exit code, not the words: an unbalanced block comment is a build-breaking bug, and a
# report nobody pipes into `tail -1` is a report nobody reads.
sys.exit(1 if bad else 0)
