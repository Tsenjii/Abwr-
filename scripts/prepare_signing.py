#!/usr/bin/env python3
"""Restore the pinned distribution identity. Never create a replacement signing key."""
import base64
import os
from pathlib import Path


def prepare():
    encoded = os.environ.get("ABWR_SIGNING_KEY_B64", "").strip()
    if encoded:
        raw = base64.b64decode(encoded, validate=True)
        if not 1000 <= len(raw) <= 65536:
            raise RuntimeError("Signing secret is not a valid-sized keystore")
        target = Path(os.environ["RUNNER_TEMP"]) / "abwr-distribution.jks"
        target.write_bytes(raw)
        target.chmod(0o600)
        migration = False
    else:
        target = Path.home() / ".android/debug.keystore"
        if not target.is_file():
            raise RuntimeError("Pinned signing key missing. Configure ABWR_SIGNING_KEY_B64; generating a new key is forbidden.")
        migration = True
    with open(os.environ["GITHUB_ENV"], "a") as env:
        env.write("ABWR_KEYSTORE_PATH=" + str(target) + "\n")
    with open(os.environ["GITHUB_OUTPUT"], "a") as output:
        output.write("needs-migration=" + str(migration).lower() + "\n")
    print("Signing key restored from " + ("existing pinned cache; repository-secret migration required" if migration else "repository secret"))


if __name__ == "__main__":
    prepare()
