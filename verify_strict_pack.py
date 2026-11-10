#!/usr/bin/env python3
"""Resolve effective 26.3 resources, including atlas sprites, rather than just file names."""
import argparse
import json
import re
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
                elif kind == "filter":
                    pattern = entry["pattern"]
                    sprites = {sprite for sprite in sprites if not all(
                        re.fullmatch(pattern[key], sprite.split(":", 1)[index])
                        for index, key in enumerate(("namespace", "path")) if key in pattern)}
                elif kind == "paletted_permutations":
                    for texture in entry["textures"]:
                        require(path_for("textures", texture, ".png"), name, "atlas_texture")
                        sprites.update(resource_id(texture) + "_" + suffix
                                       for suffix in entry["permutations"])
                else:
                    failures.add(("unknown_atlas_source", name, kind))
        atlases[atlas_id] = sprites

    models = {name: json.loads(data) for name, data in view.items()
              if "/models/" in name and name.endswith(".json")}
    resolved = {}

    def resolve(name, parents=()):
        if name in resolved:
            return resolved[name]
        if name in parents or name not in models:
            return {}, None
        doc = models[name]
        textures, elements = ({}, None)
        if "parent" in doc:
            textures, elements = resolve(path_for("models", doc["parent"], ".json"), (*parents, name))
        textures = {**textures, **doc.get("textures", {})}
        result = (textures, doc.get("elements", elements))
        resolved[name] = result
        return result

    def materials(name):
        textures, elements = resolve(name)
        references = [face["texture"] for element in elements or []
                      for face in element.get("faces", {}).values()]
        if elements is None:
            references = [key for key in textures if key.startswith("layer")]
            references = ["#" + key for key in references]
        result = set()
        for reference in references:
            # Faces name a texture slot, with or without the optional '#'.
            reference = textures.get(reference.removeprefix("#"))
            seen = set()
            while isinstance(reference, str) and reference.startswith("#"):
                if reference in seen:
                    reference = None
                    break
                seen.add(reference)
                reference = textures.get(reference[1:])
            if isinstance(reference, dict):
                reference = reference.get("sprite")
            if isinstance(reference, str):
                result.add(resource_id(reference))
        return result

    blocks = atlases.get("minecraft:blocks", set())
    items = atlases.get("minecraft:items", set())
    block_atlas_failures = set()

    def selected(sprite):
        return "items" if sprite in items else "blocks" if sprite in blocks else "missing"

    def referenced_models(value):
        if isinstance(value, dict):
            if isinstance(value.get("model"), str):
                yield path_for("models", value["model"], ".json")
            for child in value.values():
                yield from referenced_models(child)
        elif isinstance(value, list):
            for child in value:
                yield from referenced_models(child)

    for name, data in view.items():
        if not name.endswith(".json"):
            continue
        if "/blockstates/" in name:
            for model in set(referenced_models(json.loads(data))):
                for sprite in materials(model):
                    checks += 1
                    if selected(sprite) != "blocks":
                        block_atlas_failures.add((name, model, sprite, selected(sprite)))
        elif "/items/" in name:
            for model in set(referenced_models(json.loads(data))):
                used = {selected(sprite) for sprite in materials(model)}
                checks += 1
                if len(used) > 1:
                    failures.add(("mixed_item_atlases", name, model))

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
            "failures": sorted(failures), "block_atlas_failures": sorted(block_atlas_failures),
            "sprites": {k: len(v) for k, v in atlases.items()}}


def model_origins(before, after, mappings, normalize=lambda value: value):
    """Follow stable item/blockstate entries and parent links through native model renaming."""
    origins, seen, pending = {}, set(), []

    def paired(left, right):
        if isinstance(left, dict) and isinstance(right, dict):
            for key in left.keys() & right.keys():
                a, b = left[key], right[key]
                if key in ("model", "parent") and isinstance(a, str) and isinstance(b, str):
                    old, new = path_for("models", a, ".json"), path_for("models", b, ".json")
                    if old in before and new in after:
                        origins.setdefault(new, set()).add(old)
                        pending.append((old, new))
                else:
                    paired(a, b)
        elif isinstance(left, list) and isinstance(right, list) and len(left) == len(right):
            for a, b in zip(left, right):
                paired(a, b)

    for path in before:
        parts = path.split("/", 3)
        if len(parts) != 4 or parts[2] not in ("items", "blockstates") or not path.endswith(".json"):
            continue
        kind = parts[2]
        identifier = parts[1] + ":" + parts[3][:-5]
        target = path_for(kind, mappings.get(kind, {}).get(identifier, identifier), ".json")
        if target in after:
            paired(json.loads(before[path]), json.loads(after[target]))
    while pending:
        old, new = pending.pop()
        if (old, new) not in seen:
            seen.add((old, new))
            paired(json.loads(before[old]), json.loads(after[new]))
    # Unreferenced models have no entry-point path. Only accept full JSON equality,
    # after reversing resource IDs, rather than matching a single missing texture.
    def restore(value):
        if isinstance(value, dict):
            return {k: restore(v) for k, v in value.items()}
        if isinstance(value, list):
            return [restore(v) for v in value]
        return normalize(value) if isinstance(value, str) else value

    signatures = {}
    for path, data in before.items():
        if "/models/" in path and path.endswith(".json"):
            signature = json.dumps(json.loads(data), sort_keys=True)
            signatures.setdefault(signature, set()).add(path)
    for path, data in after.items():
        if path not in origins and "/models/" in path and path.endswith(".json"):
            signature = json.dumps(restore(json.loads(data)), sort_keys=True)
            if signature in signatures:
                origins[path] = signatures[signature]
    return origins


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
    merge = json.loads(Path(args.mapping).read_text())["merge"]
    mappings = merge["runtimeIds"]
    reverse = {v: k for group in mappings.values() for k, v in group.items()}
    target = "minecraft" if merge["namespacesAfter"] == ["minecraft"] else "custom"
    moved_namespaces = set(merge["namespacesBefore"]) - {"minecraft"}

    def normalize(value):
        if value in reverse:
            return reverse[value]
        if value.startswith(target + ":") and "/" in value:
            namespace, local = value[len(target) + 1:].split("/", 1)
            if namespace in moved_namespaces:
                return namespace + ":" + local
        if value.startswith("assets/" + target + "/"):
            parts = value.split("/", 4)
            if len(parts) == 5 and parts[3] in moved_namespaces:
                return "assets/" + parts[3] + "/" + parts[2] + "/" + parts[4]
        return value

    old_errors = {tuple(row) for row in before["failures"]}
    origins = model_origins(effective(contents(args.baseline)), effective(contents(args.candidate)), mappings, normalize)
    added = []
    for row in after["failures"]:
        normalized = tuple(normalize(v) for v in row)
        source_names = origins.get(row[1], {normalized[1]})
        if not all((normalized[0], source, *normalized[2:]) in old_errors for source in source_names):
            added.append(row)
    result = {"baseline": before, "candidate": after, "new_failures": added,
              "model_origins": {key: sorted(values) for key, values in origins.items()},
              "passed": not added and not after["block_atlas_failures"]
              and after["namespaces"] == sorted(merge["namespacesAfter"])}
    Path(args.report).write_text(json.dumps(result, ensure_ascii=False, indent=2) + "\n")
    print(f"Baseline: {before['checks']} checks, {len(before['failures'])} existing failures")
    print(f"Candidate: {after['checks']} checks, {len(added)} new failures; namespaces={after['namespaces']}")
    print(json.dumps(added[:8], ensure_ascii=False))
    print(f"World block materials using wrong/missing atlas: baseline={len(before['block_atlas_failures'])}, "
          f"candidate={len(after['block_atlas_failures'])}")
    raise SystemExit(0 if result["passed"] else 1)


if __name__ == "__main__":
    main()
