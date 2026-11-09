#!/usr/bin/env python3
"""Compare effective resource semantics, not the optimizer's own reachability result."""
import functools
import json
import pathlib
import sys
import zipfile


def effective(files):
    meta = json.loads(files["pack.mcmeta"])
    pack = meta["pack"]
    target = pack["min_format"]
    if target != pack["max_format"]:
        raise ValueError("This verification fixture requires an exact client format")
    entries = meta.get("overlays", {}).get("entries", [])
    for entry in entries:
        if entry["min_format"] <= target <= entry["max_format"]:
            prefix = entry["directory"] + "/"
            for name, data in list(files.items()):
                if name.startswith(prefix + "assets/"):
                    files[name[len(prefix):]] = data
    for entry in entries:
        prefix = entry["directory"] + "/"
        files = {name: data for name, data in files.items() if not name.startswith(prefix)}
    meta.pop("overlays", None)
    files["pack.mcmeta"] = json.dumps(meta).encode()
    return files


def main():
    source, optimized, report_path = map(pathlib.Path, sys.argv[1:])
    with zipfile.ZipFile(source) as pack:
        before = effective({n: pack.read(n) for n in pack.namelist() if not n.endswith("/")})
    after = effective({str(p.relative_to(optimized)): p.read_bytes()
                       for p in optimized.rglob("*") if p.is_file()})
    checks = 0

    def model_path(identifier):
        namespace, path = identifier.split(":", 1) if ":" in identifier else ("minecraft", identifier)
        return f"assets/{namespace}/models/{path}.json"

    def resolver(files):
        active = set()

        @functools.lru_cache(None)
        def model(identifier):
            path = model_path(identifier)
            if path not in files:
                return ("external", identifier if ":" in identifier else "minecraft:" + identifier)
            if path in active:
                raise AssertionError(f"Cyclic model: {path}")
            active.add(path)
            result = expand(json.loads(files[path]))
            active.remove(path)
            return result

        def expand(value):
            if isinstance(value, dict):
                return tuple((k, ("model", model(v)) if k in ("parent", "model") and isinstance(v, str)
                              else expand(v)) for k, v in sorted(value.items()))
            if isinstance(value, list):
                return tuple(expand(v) for v in value)
            return value

        return expand

    expand_before, expand_after = resolver(before), resolver(after)
    for name, data in before.items():
        if "/models/" in name or "/textures/" in name:
            continue
        assert name in after, f"Public resource removed: {name}"
        if name.endswith((".json", ".mcmeta")):
            assert expand_before(json.loads(data)) == expand_after(json.loads(after[name])), name
        else:
            assert data == after[name], name
        checks += 1
    for name, data in after.items():
        assert name in before, f"Unexpected asset: {name}"
        if name.endswith((".json", ".mcmeta")):
            assert expand_before(json.loads(before[name])) == expand_after(json.loads(data)), name
        else:
            assert before[name] == data, f"Binary changed: {name}"
        checks += 1
    removed = sorted(set(before) - set(after))
    assert all("/models/" in n or "/textures/" in n for n in removed), removed
    result = {"checks": checks, "failures": 0, "effective_before_files": len(before),
              "effective_after_files": len(after), "removed_effective_files": len(removed),
              "preserved_item_entrypoints": sum("/items/" in n for n in before),
              "client_rendering_verified": False}
    report_path.write_text(json.dumps(result, indent=2) + "\n")
    print(json.dumps(result))


if __name__ == "__main__":
    main()
