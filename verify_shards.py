"""Independent raw-payload verification, including CE's intentionally invalid CRCs."""
import hashlib
import itertools
import json
from pathlib import Path
import re
import struct
import sys
import zipfile
import zlib


def resources(path):
    raw = Path(path).read_bytes()
    result = {}
    with zipfile.ZipFile(path) as archive:
        for entry in archive.infolist():
            if entry.is_dir() and entry.file_size == 0:
                continue
            offset = entry.header_offset
            names, extra = struct.unpack_from("<HH", raw, offset + 26)
            start = offset + 30 + names + extra
            compressed = raw[start:start + entry.compress_size]
            if entry.compress_type == zipfile.ZIP_DEFLATED:
                value = zlib.decompress(compressed, -15)
            elif entry.compress_type == zipfile.ZIP_STORED:
                value = compressed
            else:
                raise ValueError("Unsupported compression")
            assert len(value) == entry.file_size
            result[entry.filename] = (value, compressed, entry.CRC, entry.compress_type)
    return result


def verify(source, directory):
    directory = Path(directory)
    manifest_path = directory / "current.json"
    if not manifest_path.exists():
        manifest_path = directory / "candidate.json"
    manifest = json.loads(manifest_path.read_text())
    objects = directory / "objects" if (directory / "objects").is_dir() else directory
    expected = resources(source)
    actual = {}
    owners = {}
    assertions = 0
    summary = []
    metadata_name = next(name for name in expected if name.rstrip("/") == "pack.mcmeta")
    original_metadata = json.loads(expected[metadata_name][0])
    source_overlays = original_metadata.get("overlays", {}).get("entries", [])
    shard_resources = []
    shard_metadata = []
    optimized_visits = 0
    for index, shard in enumerate(manifest["shards"]):
        path = objects / shard["file"]
        assert hashlib.sha1(path.read_bytes()).hexdigest() == shard["sha1"]
        found = resources(path)
        metadata = json.loads(found[metadata_name][0])
        expected_metadata = dict(original_metadata)
        expected_metadata.pop("overlays", None)
        unrelated_metadata = dict(metadata)
        unrelated_metadata.pop("overlays", None)
        assert unrelated_metadata == expected_metadata, "Unrelated metadata changed"
        retained = [overlay for overlay in source_overlays
                    if any(name.startswith(overlay["directory"] + "/") for name in found)]
        assert metadata.get("overlays", {}).get("entries", []) == retained, "Overlay order changed"
        assertions += 2
        namespaces = set()
        for name, data in found.items():
            if name != metadata_name:
                assert expected[name] == data, name
            actual[name] = data
            match = re.match(r"^(?:[^/]+/)?assets/([^/]+)/", name)
            if match:
                namespace = match[1]
                assert owners.setdefault(namespace, index) == index, namespace
                namespaces.add(namespace)
            assertions += 2
        assert namespaces == set(shard["namespaces"])
        assertions += 1
        optimized_visits += len(namespaces) * len(found) * (1 + len(retained))
        shard_resources.append(found)
        shard_metadata.append(metadata)
        summary.append({"index": index, "entries": len(found), "bytes": path.stat().st_size,
                        "namespaces": sorted(namespaces)})
    assert actual.keys() == expected.keys(), "Union differs from source"
    # Compare resource lookup results with every possible combination of declared layers,
    # including base-only and layers overriding the same path. Metadata pruning must be inert.
    def effective(found, metadata, enabled):
        result = {}
        prefixes = [""] + [overlay["directory"] + "/" for overlay in
                          metadata.get("overlays", {}).get("entries", [])
                          if overlay["directory"] in enabled]
        for prefix in prefixes:
            for name, data in found.items():
                if name.startswith(prefix + "assets/"):
                    result[name[len(prefix):].rstrip("/")] = data[0]
        return result

    directories = [overlay["directory"] for overlay in source_overlays]
    for size in range(len(directories) + 1):
        for enabled in itertools.combinations(directories, size):
            union = {}
            for found, metadata in zip(shard_resources, shard_metadata):
                union.update(effective(found, metadata, enabled))
            assert union == effective(expected, original_metadata, enabled), "Effective resources changed"
            assertions += len(union)
    assert manifest["sourceSha1"] == hashlib.sha1(Path(source).read_bytes()).hexdigest()
    if manifest.get("scanCost"):
        assert optimized_visits == manifest["scanCost"]["optimizedShards"], "Scan report mismatch"
    assertions += 2
    report = {"passed": assertions, "failed": 0, "source_entries": len(expected),
              "namespaces": len(owners), "shards": summary, "client_acceptance": "pending"}
    report["modeled_entry_visits"] = manifest.get("scanCost")
    (directory / "verification.json").write_text(json.dumps(report, indent=2) + "\n")
    print(f"PASS {assertions} checks; {len(expected)} entries; {len(owners)} namespaces; "
          f"{len(summary)} standalone packs; bytes {sum(s['bytes'] for s in summary)}")
    return report


if __name__ == "__main__":
    verify(*sys.argv[1:])
