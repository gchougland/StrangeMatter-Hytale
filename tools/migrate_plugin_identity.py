#!/usr/bin/env python3
"""Preview an exact Strange Matter identity update in explicitly named JSON files.

Run with the server or world closed. No files change unless --apply is provided.
Each changed file gets a byte exact sibling backup before atomic replacement.
Only native top level Mods, Plugins, ModLoadOrder and RequiredPlugins are edited.
This does not move plugin data, scan saves, or rename item and asset identifiers.
"""
from __future__ import annotations

import argparse
from dataclasses import dataclass
from datetime import datetime, timezone
import json
import os
from pathlib import Path
import stat
import sys
import tempfile
import uuid

OLD_ID = "Hexvane:StrangeMatter"
NEW_ID = "Hexvane:Strange Matter"
MAP_FIELDS = ("Mods", "Plugins", "RequiredPlugins")


class MigrationError(ValueError):
    """Invalid or conflicting input; no inferred settings should be written."""


@dataclass(frozen=True)
class Plan:
    path: Path
    original: bytes
    replacement: bytes
    fields: tuple[str, ...]

    @property
    def changed(self) -> bool:
        return bool(self.fields)


def _unique_object(pairs):
    result = {}
    for key, value in pairs:
        if key in result:
            raise MigrationError(f"Duplicate JSON key: {key}")
        result[key] = value
    return result


def _invalid_constant(value):
    raise MigrationError(f"Invalid JSON constant: {value}")


def _equivalent(a, b) -> bool:
    # JSON booleans and numbers must not compare equal through Python True == 1.
    return json.dumps(a, sort_keys=True, ensure_ascii=False) == json.dumps(b, sort_keys=True, ensure_ascii=False)


def prepare(path: Path) -> Plan:
    path = path.resolve(strict=True)
    original = path.read_bytes()
    try:
        document = json.loads(original.decode("utf-8-sig"), object_pairs_hook=_unique_object,
                              parse_constant=_invalid_constant)
    except (UnicodeError, json.JSONDecodeError, MigrationError) as exc:
        raise MigrationError(f"{path}: {exc}") from exc
    if not isinstance(document, dict):
        raise MigrationError(f"{path}: expected a JSON object")
    fields = []
    for field in MAP_FIELDS:
        if field not in document:
            continue
        mapping = document[field]
        if not isinstance(mapping, dict):
            raise MigrationError(f"{path}: {field} must be a JSON object")
        if OLD_ID not in mapping:
            continue
        if NEW_ID in mapping and not _equivalent(mapping[OLD_ID], mapping[NEW_ID]):
            raise MigrationError(f"{path}: conflicting old and new entries in {field}; choose the intended value manually")
        # Keep the old entry's position when introducing the new key, and keep an
        # existing new key's value and position when equivalent entries coexist.
        document[field] = {
            (NEW_ID if key == OLD_ID else key): value
            for key, value in mapping.items()
            if key != OLD_ID or NEW_ID not in mapping
        }
        fields.append(field)
    if "ModLoadOrder" in document:
        order = document["ModLoadOrder"]
        if not isinstance(order, list) or not all(isinstance(item, str) for item in order):
            raise MigrationError(f"{path}: ModLoadOrder must be an array of strings")
        if OLD_ID in order:
            migrated = []
            found = False
            for item in order:
                if item in (OLD_ID, NEW_ID):
                    if found:
                        continue
                    item, found = NEW_ID, True
                migrated.append(item)
            document["ModLoadOrder"] = migrated
            fields.append("ModLoadOrder")
    if not fields:
        return Plan(path, original, original, ())
    # Values and unrelated keys survive. Preserve UTF8 BOM and prevailing CRLF;
    # formatting is normalized to readable two space indentation on changed files.
    newline = "\r\n" if b"\r\n" in original else "\n"
    result = (json.dumps(document, ensure_ascii=False, indent=2, allow_nan=False) + "\n").replace("\n", newline)
    replacement = result.encode("utf-8")
    if original.startswith(b"\xef\xbb\xbf"):
        replacement = b"\xef\xbb\xbf" + replacement
    return Plan(path, original, replacement, tuple(fields))


def prepare_all(paths) -> list[Plan]:
    plans = []
    seen = set()
    for path in paths:
        plan = prepare(Path(path))
        if plan.path not in seen:
            plans.append(plan)
            seen.add(plan.path)
    return plans


def _check_unchanged(plan: Plan):
    if plan.path.read_bytes() != plan.original:
        raise MigrationError(f"{plan.path}: file changed after preview; rerun with the server or world closed")


def apply_plans(plans: list[Plan]) -> dict[Path, Path]:
    changed = [plan for plan in plans if plan.changed]
    # Check the whole batch before writing the first backup or config file.
    for plan in changed:
        _check_unchanged(plan)
    backups = {}
    stamp = datetime.now(timezone.utc).strftime("%Y%m%dT%H%M%S%fZ")
    for plan in changed:
        _check_unchanged(plan)
        token = uuid.uuid4().hex[:8]
        backup = plan.path.with_name(f"{plan.path.name}.before-strange-matter-{stamp}-{token}.bak")
        with backup.open("xb") as target:
            target.write(plan.original)
            target.flush()
            os.fsync(target.fileno())
        temp_path = None
        try:
            with tempfile.NamedTemporaryFile(prefix=f".{plan.path.name}.strange-matter-", suffix=".tmp",
                                             dir=plan.path.parent, delete=False) as target:
                temp_path = Path(target.name)
                target.write(plan.replacement)
                target.flush()
                os.fsync(target.fileno())
            os.chmod(temp_path, stat.S_IMODE(plan.path.stat().st_mode))
            _check_unchanged(plan)
            os.replace(temp_path, plan.path)
            backups[plan.path] = backup
        finally:
            if temp_path is not None and temp_path.exists():
                temp_path.unlink()
    return backups


def main(argv=None) -> int:
    parser = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    parser.add_argument("files", nargs="+", type=Path, help="Explicit server or world JSON files to inspect")
    parser.add_argument("--apply", action="store_true", help="Apply the previewed changes and save byte exact backups")
    args = parser.parse_args(argv)
    try:
        plans = prepare_all(args.files)
        backups = apply_plans(plans) if args.apply else {}
        print(json.dumps({
            "mode": "apply" if args.apply else "dry_run", "oldId": OLD_ID, "newId": NEW_ID,
            "files": [{"path": str(plan.path), "fields": list(plan.fields),
                       "status": ("updated" if args.apply else "would_update") if plan.changed else "unchanged",
                       "backup": str(backups[plan.path]) if plan.path in backups else None} for plan in plans]
        }, indent=2))
        return 0
    except (OSError, MigrationError) as exc:
        print(f"Migration stopped: {exc}", file=sys.stderr)
        return 1


if __name__ == "__main__":
    raise SystemExit(main())
