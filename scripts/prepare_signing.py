#!/usr/bin/env python3
"""Restore the pinned distribution identity. Never create a replacement signing key."""
import base64
import os
from pathlib import Path


def prepare():
    encoded = os.environ.get("ABWR_SIGNING_KEY_B64", "").strip()
    if not encoded:
        raise RuntimeError("Pinned signing secret missing. Configure ABWR_SIGNING_KEY_B64; cache fallback and replacement keys are forbidden.")
    raw = base64.b64decode(encoded, validate=True)
    if not 1000 <= len(raw) <= 65536:
        raise RuntimeError("Signing secret is not a valid-sized keystore")
    target = Path(os.environ["RUNNER_TEMP"]) / "abwr-distribution.jks"
    with os.fdopen(os.open(target, os.O_WRONLY | os.O_CREAT | os.O_TRUNC, 0o600), "wb") as key:
        os.fchmod(key.fileno(), 0o600)
        key.write(raw)
    with open(os.environ["GITHUB_ENV"], "a") as env:
        env.write("ABWR_KEYSTORE_PATH=" + str(target) + "\n")
    print("Signing key restored from repository secret")


if __name__ == "__main__":
    prepare()
