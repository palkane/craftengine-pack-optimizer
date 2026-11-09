#!/usr/bin/env python3
"""Resolve effective 26.3 resources, including atlas sprites, rather than just file names."""
import argparse
import json
from pathlib import Path
import struct
import zipfile
import zlib


def contents(path):
    raw = Path(path).read_bytes()
    result = {}
    with zipfile.ZipFile(path) as archive:
        for entry in archive.infolist():
            if entry.is_dir() and entry.file_size == 0:
                continue
            offset = entry.header_offset
            name_length, extra_length = struct.unpack_from("<HH", raw, offset + 26)
            start = offset + 30 + name_length + extra_length
            data = raw[start:start + entry.compress_size]
            if entry.compress_type == zipfile.ZIP_DEFLATED:
                data = zlib.decompress(data, -15)
            elif entry.compress_type != zipfile.ZIP_STORED:
                raise ValueError("Unsupported compression")
            name = entry.filename.rstrip("/")
            if name in result:
                raise ValueError("Duplicate resource " + name)
            result[name] = data
    return result


def resource_id(value):
    return value if ":" in value else "minecraft:" + value


def path_for(kind, value, suffix):
    namespace, name = resource_id(value).split(":", 1)
    return f"assets/{namespace}/{kind}/{name}{suffix}"


def effective(files):
    metadata = json.loads(files["pack.mcmeta"])
    layers = [""]
    for entry in metadata.get("overlays", {}).get("entries", []):
        low = entry.get("min_format", entry.get("formats", {}).get("min_inclusive", 0)
                        if isinstance(entry.get("formats"), dict) else entry.get("formats", 0))
        high = entry.get("max_format", entry.get("formats", {}).get("max_inclusive", 2147483647)
                         if isinstance(entry.get("formats"), dict) else entry.get("formats", 2147483647))
        low = tuple(low) if isinstance(low, list) else (low, 0)
        high = tuple(high) if isinstance(high, list) else (high, 2147483647)
        if low <= (97, 1) <= high:
            layers.append(entry["directory"] + "/")
    result = {}
    for layer in layers:
        prefix = layer + "assets/"
        result.update({key[len(layer):]: value for key, value in files.items() if key.startswith(prefix)})
    return result


def inspect(pack, vanilla):
    files = effective(contents(pack))
    view = dict(vanilla)
    view.update(files)
    failures = set()
    checks = 0
    atlases = {}

    def require(path, source, kind):
        nonlocal checks
        checks += 1
        if path not in view:
            failures.add((kind, source, path))

    for name, data in view.items():
        if "/atlases/" not in name or not name.endswith(".json"):
            continue
        namespace, local = name.split("/", 2)[1:]
        atlas_id = namespace + ":" + local[len("atlases/"):-5]
        sprites = set()
        # Atlas resource JSONs are stacked; a pack's sources do not replace vanilla sources.
        documents = ([json.loads(vanilla[name])] if name in vanilla and name in files else [])
        documents.append(json.loads(data))
        for doc in documents:
            for entry in doc.get("sources", []):
                kind = entry["type"].removeprefix("minecraft:")
                if kind == "single":
                    require(path_for("textures", entry["resource"], ".png"), name, "atlas_texture")
                    sprites.add(resource_id(entry.get("sprite", entry["resource"])))
                elif kind == "unstitch":
                    require(path_for("textures", entry["resource"], ".png"), name, "atlas_texture")
                    sprites.update(resource_id(r["sprite"]) for r in entry["regions"])
                elif kind == "directory":
                    source = entry["source"].rstrip("/") + "/"
                    for texture in view:
                        parts = texture.split("/", 3)
                        if len(parts) == 4 and parts[2] == "textures" and parts[3].startswith(source) and texture.endswith(".png"):
                            sprites.add(parts[1] + ":" + entry["prefix"] + parts[3][len(source):-4])
                elif kind not in ("filter", "paletted_permutations"):
                    failures.add(("unknown_atlas_source", name, kind))
        atlases[atlas_id] = sprites

    def model_references(value, source):
        if isinstance(value, list):
            for child in value:
                model_references(child, source)
        elif isinstance(value, dict):
            if isinstance(value.get("model"), str):
                require(path_for("models", value["model"], ".json"), source, "model")
            for child in value.values():
                model_references(child, source)

    for name, data in files.items():
        if not name.endswith(".json"):
            continue
        doc = json.loads(data)
        if "/models/" in name:
            if isinstance(doc.get("parent"), str) and not doc["parent"].startswith(("builtin/", "minecraft:builtin/")):
                require(path_for("models", doc["parent"], ".json"), name, "parent")
            for value in doc.get("textures", {}).values():
                if isinstance(value, str):
                    if value.startswith("#"):
                        continue
                    sprite = resource_id(value)
                    available = atlases.get("minecraft:blocks", set()) | atlases.get("minecraft:items", set())
                elif isinstance(value, dict) and "sprite" in value:
                    sprite = resource_id(value["sprite"])
                    available = atlases.get(resource_id(value.get("atlas", "minecraft:blocks")), set())
                else:
                    continue
                checks += 1
                if sprite not in available:
                    failures.add(("model_sprite", name, sprite))
        elif "/items/" in name or "/blockstates/" in name:
            model_references(doc, name)
        elif "/font/" in name:
            for provider in doc.get("providers", []):
                kind = provider.get("type", "").removeprefix("minecraft:")
                if kind == "bitmap":
                    require(path_for("textures", provider["file"], ""), name, "font_texture")
                elif kind == "reference":
                    require(path_for("font", provider["id"], ".json"), name, "font_reference")
        elif "/equipment/" in name:
            for layer, entries in doc.get("layers", {}).items():
                for entry in entries:
                    require(path_for("textures", entry["texture"], ".png").replace("/textures/", "/textures/entity/equipment/" + layer + "/"),
                            name, "equipment_texture")
    return {"checks": checks, "namespaces": sorted({n.split("/")[1] for n in files}),
            "failures": sorted(failures), "sprites": {k: len(v) for k, v in atlases.items()}}


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("baseline")
    parser.add_argument("candidate")
    parser.add_argument("vanilla")
    parser.add_argument("mapping")
    parser.add_argument("report")
    args = parser.parse_args()
    vanilla = {k: v for k, v in contents(args.vanilla).items() if k.startswith("assets/")}
    before = inspect(args.baseline, vanilla)
    after = inspect(args.candidate, vanilla)
    mappings = json.loads(Path(args.mapping).read_text())["merge"]["runtimeIds"]
    reverse = {v: k for group in mappings.values() for k, v in group.items()}

    def normalize(value):
        if value in reverse:
            return reverse[value]
        if value.startswith("custom:") and "/" in value:
            namespace, local = value[len("custom:"):].split("/", 1)
            return namespace + ":" + local
        if value.startswith("assets/custom/"):
            parts = value.split("/", 4)
            if len(parts) == 5:
                return "assets/" + parts[3] + "/" + parts[2] + "/" + parts[4]
        return value

    old_errors = {tuple(row) for row in before["failures"]}
    added = [row for row in after["failures"]
             if tuple(normalize(v) for v in row) not in old_errors]
    result = {"baseline": before, "candidate": after, "new_failures": added,
              "passed": not added and after["namespaces"] == ["custom", "minecraft"]}
    Path(args.report).write_text(json.dumps(result, ensure_ascii=False, indent=2) + "\n")
    print(f"Baseline: {before['checks']} checks, {len(before['failures'])} existing failures")
    print(f"Candidate: {after['checks']} checks, {len(added)} new failures; namespaces={after['namespaces']}")
    print(json.dumps(added[:8], ensure_ascii=False))
    raise SystemExit(0 if result["passed"] else 1)


if __name__ == "__main__":
    main()
