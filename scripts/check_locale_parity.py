#!/usr/bin/env python3
"""Locale key-parity check for compose-resources modules.

Fails (exit 1) if any `values-<loc>/` is missing a string key present in the default `values/`
(such keys silently fall back to English at runtime). Mirrors the Gradle `:ui:checkLocaleKeyParity`
task, but also covers `:composeApp` so you can verify both Res catalogs from one place / CI step.

Usage:
    python3 scripts/check_locale_parity.py            # checks :ui and :composeApp
    python3 scripts/check_locale_parity.py <dir> ...  # check specific composeResources dirs
"""
import os
import re
import sys

DEFAULT_ROOTS = [
    "ui/src/commonMain/composeResources",
    "composeApp/src/commonMain/composeResources",
]

# `(?![-\w])` after "string" excludes <string-array> (a bare word boundary would match it).
STRING_RE = re.compile(r'<string(?![-\w])([^>]*?)\bname="([^"]+)"([^>]*?)>', re.DOTALL)
TRANSLATABLE_FALSE_RE = re.compile(r'translatable\s*=\s*"false"')


def keys_in(values_dir):
    keys = set()
    if not os.path.isdir(values_dir):
        return keys
    for fn in os.listdir(values_dir):
        if not fn.endswith(".xml"):
            continue
        with open(os.path.join(values_dir, fn), encoding="utf-8") as f:
            text = f.read()
        # Strip XML comments first so a commented-out <string name=...> can't be counted.
        text = re.sub(r"<!--.*?-->", "", text, flags=re.DOTALL)
        for m in STRING_RE.finditer(text):
            attrs = m.group(1) + m.group(3)
            if TRANSLATABLE_FALSE_RE.search(attrs):
                continue
            keys.add(m.group(2))
    return keys


def check_root(root):
    """Return (ok, default_count, {loc: [missing_keys]})."""
    default = keys_in(os.path.join(root, "values"))
    problems = {}
    if not os.path.isdir(root):
        return True, 0, problems
    for name in sorted(os.listdir(root)):
        loc_dir = os.path.join(root, name)
        if not (os.path.isdir(loc_dir) and name.startswith("values-")):
            continue
        missing = sorted(default - keys_in(loc_dir))
        if missing:
            problems[name] = missing
    return (not problems), len(default), problems


def main():
    roots = sys.argv[1:] or DEFAULT_ROOTS
    failed = False
    for root in roots:
        if not os.path.isdir(root):
            print(f"SKIP  {root} (not found)")
            continue
        ok, count, problems = check_root(root)
        if ok:
            print(f"OK    {root}: {count} default keys present in every locale.")
        else:
            failed = True
            print(f"FAIL  {root}: keys present in values/ but missing in a locale:")
            for loc, missing in problems.items():
                shown = ", ".join(missing[:15])
                more = f" … (+{len(missing) - 15} more)" if len(missing) > 15 else ""
                print(f"        {loc}: {len(missing)} missing: {shown}{more}")
    sys.exit(1 if failed else 0)


if __name__ == "__main__":
    main()
