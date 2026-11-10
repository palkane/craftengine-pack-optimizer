#!/usr/bin/env python3
"""Independent whole-pack and ordered glyph-definition comparison."""
import argparse
import collections
import hashlib
import json
from pathlib import Path
import zipfile


def read_tree(path):
    if path.is_dir():
        return {p.relative_to(path).as_posix(): p.read_bytes()
                for p in path.rglob("*") if p.is_file()}
    with zipfile.ZipFile(path) as archive:
        assert archive.testzip() is None, "unprotected ZIP CRC failure"
        return {info.filename: archive.read(info)
                for info in archive.infolist() if not info.is_dir()}


def glyphs(document):
    mapped = collections.defaultdict(list)
    for provider in document["providers"]:
        if provider.get("type") not in ("bitmap", "minecraft:bitmap"):
            continue
        rows = provider["chars"]
        settings = {key: value for key, value in provider.items() if key != "chars"}
        local = {}
        for y, row in enumerate(rows):
            for x, char in enumerate(row):
                if char != "\0":
                    local[ord(char)] = {
                        "settings": settings, "rows": len(rows),
                        "columns": len(row), "x": x, "y": y,
                    }
        for code, descriptor in local.items():
            mapped[code].append(descriptor)
    return dict(mapped)


def verify(original, candidate, report):
    before = read_tree(original)
    after = read_tree(candidate)
    assert before.keys() == after.keys(), "resource paths changed"
    changed = [name for name in before if before[name] != after[name]]
    targets = [name for name in before if name == "assets/dialogmenu/font/sprites.json"
               or name.endswith("/assets/dialogmenu/font/sprites.json")]
    assert set(changed) <= set(targets), f"unexpected resource edits: {set(changed) - set(targets)}"
    fonts = []
    for name in targets:
        old, new = json.loads(before[name]), json.loads(after[name])
        assert {k: v for k, v in old.items() if k != "providers"} == {
            k: v for k, v in new.items() if k != "providers"
        }, f"font root metadata changed: {name}"
        assert glyphs(old) == glyphs(new), f"glyph pixel source/UV/metrics/filter/priority changed: {name}"
        for doc in (old, new):
            assert all(p.get("type") in ("bitmap", "minecraft:bitmap")
                       for p in doc["providers"]), "live oracle expects bitmap-only sprites"
        stats = {
            "font": name, "glyphs": len(glyphs(old)),
            "providers_before": len(old["providers"]),
            "providers_after": len(new["providers"]),
        }
        for label, doc in (("before", old), ("after", new)):
            stats["bitmap_files_" + label] = dict(sorted(collections.Counter(
                p["file"] for p in doc["providers"]).items()))
        fonts.append(stats)
    result = {
        "passed": True, "files": len(before), "changed": changed,
        "unchanged_files": len(before) - len(changed), "fonts": fonts,
        "original_sha1": hashlib.sha1(original.read_bytes()).hexdigest() if original.is_file() else None,
        "candidate_sha1": hashlib.sha1(candidate.read_bytes()).hexdigest() if candidate.is_file() else None,
        "scope": "unprotected pack; unchanged texture bytes plus identical ordered glyph source/UV/metrics/filter; not client timing",
    }
    report.write_text(json.dumps(result, ensure_ascii=False, indent=2) + "\n")
    print(json.dumps(result, ensure_ascii=False))


if __name__ == "__main__":
    parser = argparse.ArgumentParser()
    parser.add_argument("original", type=Path)
    parser.add_argument("candidate", type=Path)
    parser.add_argument("report", type=Path)
    args = parser.parse_args()
    verify(args.original, args.candidate, args.report)
