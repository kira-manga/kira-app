#!/usr/bin/env python3
"""Reject predecessor branding in shipping Compose values, not historical comments.

Only the two shipping resource roots are scanned. Historical docs, native-app and
legacy reference source are deliberately excluded; no shipping value is allowlisted.
The Arabic assertions also guard the actual Welcome/Settings resource keys.
"""
from pathlib import Path
import re
import sys
import xml.etree.ElementTree as ET


STALE_BRAND = re.compile(r"(?<!\w)(?:yami|يامي)(?!\w)", re.IGNORECASE)
CATALOGS = {
    "ui/src/commonMain/composeResources": {
        "welcome_title": "مرحبًا بك في كيرا مانجا",
        "use_kira_compressor": "استخدام ضاغط كيرا",
        "use_kira_compressor_desc": "استخدم ضاغط كيرا لتقليل حجم الفصول بأكثر من ٥٠٪ وتوفير المساحة التخزينية",
    },
    "composeApp/src/commonMain/composeResources": {
        "app_name": "كيرا مانجا",
        "welcome_title": "مرحبًا بك في كيرا مانجا",
        "use_kira_compressor": "استخدام ضاغط كيرا",
    },
}


def check_catalog(root: Path, expected_arabic: dict[str, str]) -> list[str]:
    errors = []
    for required in (root / "values", root / "values-ar"):
        if not required.is_dir() or not any(required.glob("*.xml")):
            errors.append(f"Missing shipping resource directory/XML: {required}")
    arabic = {}
    for path in sorted(root.glob("values*/*.xml")):
        try:
            document = ET.parse(path).getroot()
        except (OSError, ET.ParseError) as failure:
            errors.append(f"Invalid resource XML {path}: {failure}")
            continue
        for value in document:
            text = "".join(value.itertext())
            key = value.get("name", "<unnamed>")
            if STALE_BRAND.search(text):
                errors.append(f"Predecessor brand in {path}:{key}")
            if path.parent.name == "values-ar" and key in expected_arabic:
                if key in arabic:
                    errors.append(f"Duplicate Arabic branding key: {path}:{key}")
                arabic[key] = text
    for key, expected in expected_arabic.items():
        if arabic.get(key) != expected:
            errors.append(f"Missing/incorrect current Arabic brand: {root}:{key}")
    return errors


def main() -> int:
    repo = Path(__file__).resolve().parents[1]
    errors = [error for relative, expected in CATALOGS.items()
              for error in check_catalog(repo / relative, expected)]
    if errors:
        print("\n".join(errors), file=sys.stderr)
        return 1
    print("PASS: current branding and Arabic Welcome/Settings values in both shipping catalogs")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
