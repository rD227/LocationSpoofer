#!/usr/bin/env python3
# SPDX-License-Identifier: GPL-3.0-only
# Copyright (C) 2026 xvsu
"""Build a reproducible, source-complete root manager module ZIP."""
import argparse
import hashlib
from pathlib import Path
import stat
import zipfile

ROOT = Path(__file__).resolve().parents[1]


def build(output: Path) -> Path:
    source = ROOT / "modules" / "pocketswap"
    props = dict(line.split("=", 1) for line in
                 (source / "module.prop").read_text(encoding="utf-8").splitlines() if "=" in line)
    license_data = (source / "LICENSE").read_bytes()
    if b"GNU GENERAL PUBLIC LICENSE" not in license_data or b"Version 3, 29 June 2007" not in license_data:
        raise ValueError("Expected the complete supplied GNU GPL v3 LICENSE")
    output.mkdir(parents=True, exist_ok=True)
    target = output / f"pocketswap-v{props['version']}.zip"
    with zipfile.ZipFile(target, "w", compression=zipfile.ZIP_DEFLATED, compresslevel=9) as archive:
        for item in sorted(source.rglob("*")):
            if not item.is_file():
                continue
            if item.is_symlink():
                raise ValueError(f"Symlink in module source: {item}")
            relative = item.relative_to(source).as_posix()
            data = item.read_bytes()
            if item.suffix == ".sh" or relative.endswith(("update-binary", "updater-script")):
                data = data.replace(b"\r\n", b"\n")
                if b"\r" in data or b"\x00" in data:
                    raise ValueError(f"Invalid shell text: {relative}")
            info = zipfile.ZipInfo(relative, date_time=(2026, 10, 5, 0, 0, 0))
            info.create_system = 3
            mode = 0o755 if item.suffix == ".sh" or relative.endswith("update-binary") else 0o644
            info.external_attr = (stat.S_IFREG | mode) << 16
            info.compress_type = zipfile.ZIP_DEFLATED
            archive.writestr(info, data)
    with zipfile.ZipFile(target) as archive:
        if archive.testzip() is not None or archive.read("LICENSE") != license_data:
            raise ValueError("ZIP verification failed")
    digest = hashlib.sha256(target.read_bytes()).hexdigest()
    target.with_suffix(".zip.sha256").write_text(f"{digest}  {target.name}\n", encoding="ascii")
    return target


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--output", type=Path, default=ROOT / "build" / "pocketswap")
    print(build(parser.parse_args().output))
