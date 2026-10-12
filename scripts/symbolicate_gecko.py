#!/usr/bin/env python3
"""Resolve public Gecko build offsets; never submit tombstones or page data."""
import argparse
import json
from pathlib import Path
import re
import urllib.request


def debug_id(build_id):
    raw = bytes.fromhex(build_id)[:16].ljust(16, b"\0")
    return (raw[:4][::-1] + raw[4:6][::-1] + raw[6:8][::-1] + raw[8:]).hex().upper() + "0"


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--build-id")
    parser.add_argument("--offsets")
    parser.add_argument("--tombstone", type=Path)
    parser.add_argument("--output", type=Path, default=Path("native-symbols.json"))
    args = parser.parse_args()
    modules = []
    stack = []
    if args.tombstone:
        if not args.tombstone.is_file():
            return
        text = args.tombstone.read_text()
        for offset, name, identifier in re.findall(r"\bpc ([0-9a-f]+) [^\n]*?/(libxul\.so|libmozglue\.so)[^\n]*?\(BuildId: ([0-9a-f]+)\)", text):
            module = [name, debug_id(identifier)]
            if module not in modules:
                modules.append(module)
            stack.append([modules.index(module), int(offset, 16)])
    else:
        assert args.build_id and args.offsets
        modules = [["libxul.so", debug_id(args.build_id)]]
        stack = [[0, int(offset, 0)] for offset in args.offsets.split(",")]
    if not stack:
        return
    payload = {"jobs": [{"memoryMap": modules, "stacks": [stack[:40]]}]}
    request = urllib.request.Request("https://symbolication.services.mozilla.com/symbolicate/v5",
        data=json.dumps(payload).encode(), headers={"Content-Type": "application/json",
        "User-Agent": "Abwr-CI-Symbols/0.13 (+https://github.com/Tsenjii/Abwr-)"})
    try:
        with urllib.request.urlopen(request, timeout=45) as response:
            result = json.loads(response.read(2_000_001))
        args.output.write_text(json.dumps({"modules": modules, "offsets": stack, "response": result}, indent=2))
        print("Saved Gecko symbolication result")
    except Exception as error:
        args.output.write_text(json.dumps({"modules": modules, "error": type(error).__name__,
            "status": getattr(error, "code", None)}, indent=2))
        print("Gecko symbols unavailable:", type(error).__name__)


if __name__ == "__main__":
    main()
