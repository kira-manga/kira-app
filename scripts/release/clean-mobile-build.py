#!/usr/bin/env python3
"""Remove only disposable outputs in this hosted-runner checkout, after evidence upload."""
import os
from pathlib import Path
import shutil
import subprocess

root = Path(__file__).resolve().parents[2]
if os.environ.get("GITHUB_ACTIONS") != "true" or Path(os.environ.get("GITHUB_WORKSPACE", "")).resolve() != root:
    raise SystemExit("Cleanup is restricted to its own GitHub Actions checkout")

# Do not run Gradle clean: it can resolve plugins/start workers and is unnecessary here.
# Never walk native-app, source/configuration, signing inputs or global dependency caches.
modules = ["app", "composeApp", "core", "domain", "data", "data/local", "data/remote",
           "data/download", "platform", "presentation", "ui", "sources/contracts",
           "sources/engine", "sources/config", "sources/legacy"]
paths = ["build", ".gradle", ".kotlin"] + [f"{module}/build" for module in modules]
tracked = subprocess.check_output(["git", "ls-files", "-z"], cwd=root).decode().split("\0")
for relative in paths:
    if any(name == relative or name.startswith(relative + "/") for name in tracked if name):
        raise SystemExit(f"Refusing to clean tracked path: {relative}")
    target = root / relative
    # Refuse even an intermediate symlink rather than deleting anything outside the checkout.
    if any(path.is_symlink() for path in [target, *target.parents] if path != root):
        raise SystemExit(f"Refusing symlinked output: {relative}")
    if target.is_dir():
        shutil.rmtree(target)
print("Owned build outputs removed; source, evidence artifacts and global dependencies retained")
