#!/usr/bin/env bash
set -euo pipefail

# No live apply: fixtures replace ALL gh/curl/signing actions with temporary local stand-ins.
# Actual synthetic Ed25519 operations remain real. No Gradle or backend service is needed.
script_dir=$(cd "$(dirname "$0")" && pwd)
PYTHONDONTWRITEBYTECODE=1 python3 -B -m unittest discover -s "$script_dir" -p 'test_source_bootstrap_*.py' -v
