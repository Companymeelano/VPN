#!/usr/bin/env python3
"""
Swift structural check for ios/**. Fails (exit 1) on unbalanced braces/parens/brackets, unbalanced
`#if/#endif`, or unterminated strings/comments.

Why a script: the same bug class as tools/check-comments.py on Android - a stray `/*` or an unclosed
string swallows the rest of the file and surfaces as a confusing cascade in another one. Swift block
comments nest (like Kotlin's), so a nested opener does not self-heal. Ten seconds here instead of a
mysterious macOS CI failure later.
"""
import os
import sys

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
TARGET = os.path.join(ROOT, "ios")


def check(path: str):
    src = open(path, encoding="utf-8").read()
    problems = []
    i = 0
    n = len(src)
    stack = []  # ('{' | '(' | '[' | '/*' | '#if', line)
    line = 1

    def at(k):
        return src[k] if k < n else ""

    while i < n:
        c = src[i]
        if c == "\n":
            line += 1
            i += 1
            continue
        # line comment
        if c == "/" and at(i + 1) == "/":
            while i < n and src[i] != "\n":
                i += 1
            continue
        # block comment (nests)
        if c == "/" and at(i + 1) == "*":
            stack.append(("/*", line))
            i += 2
            while stack and i < n:
                if src[i] == "\n":
                    line += 1
                if src[i] == "/" and at(i + 1) == "*":
                    stack.append(("/*", line))
                    i += 2
                    continue
                if src[i] == "*" and at(i + 1) == "/":
                    stack.pop()
                    i += 2
                    continue
                i += 1
            continue
        # raw string #"..."# (##"..."##, ...) - regex patterns live in these
        if c == "#":
            k = 0
            while at(i + k) == "#":
                k += 1
            if at(i + k) == '"':
                close = '"' + "#" * k
                j = i + k + 1
                while j < n and not src.startswith(close, j):
                    if src[j] == "\n":
                        line += 1
                    j += 1
                if j >= n:
                    problems.append("line %d: unterminated raw string" % line)
                    break
                i = j + len(close)
                continue
        # multiline string
        if src.startswith('"""', i):
            i += 3
            while i < n and not src.startswith('"""', i):
                if src[i] == "\\":
                    i += 2
                    continue
                if src[i] == "\n":
                    line += 1
                i += 1
            if i >= n:
                problems.append("line %d: unterminated multiline string" % line)
                break
            i += 3
            continue
        # string literal
        if c == '"':
            i += 1
            while i < n and src[i] != '"':
                if src[i] == "\\":
                    i += 2
                    continue
                if src[i] == "\n":
                    problems.append("line %d: unterminated string literal" % line)
                    break
                i += 1
            i += 1
            continue
        # conditional compilation
        if src.startswith("#if", i) and not (i >= 1 and (src[i - 1].isalnum() or src[i - 1] == "_")):
            stack.append(("#if", line))
            i += 3
            continue
        if src.startswith("#endif", i):
            if not stack or stack[-1][0] != "#if":
                problems.append("line %d: #endif without matching #if" % line)
            else:
                stack.pop()
            i += 6
            continue
        if c in "{([":
            stack.append((c, line))
            i += 1
            continue
        if c in "})]":
            pair = {"}": "{", ")": "(", "]": "["}[c]
            if not stack or stack[-1][0] != pair:
                problems.append("line %d: stray '%s'" % (line, c))
            else:
                stack.pop()
            i += 1
            continue
        i += 1

    for kind, ln in stack:
        problems.append("line %d: unclosed %s" % (ln, kind))
    return problems


def main() -> int:
    if not os.path.isdir(TARGET):
        print("FAIL: ios/ is missing")
        return 1
    total = 0
    bad = 0
    for dirpath, _dirs, files in os.walk(TARGET):
        for f in sorted(files):
            if not f.endswith(".swift"):
                continue
            total += 1
            path = os.path.join(dirpath, f)
            problems = check(path)
            for p in problems:
                print("FAIL %s: %s" % (os.path.relpath(path, ROOT), p))
            if problems:
                bad += 1
    if total == 0:
        print("FAIL: no Swift files found under ios/")
        return 1
    if bad:
        print("FAIL: %d of %d Swift files have structural problems" % (bad, total))
        return 1
    print("swift-structure OK: %d files" % total)
    return 0


if __name__ == "__main__":
    sys.exit(main())
