import json
import tempfile
import unittest
from pathlib import Path
from zipfile import ZipFile

from verify_strict_pack import inspect, model_origins


class ModelIdentityTest(unittest.TestCase):
    def test_unreferenced_model_requires_full_content_identity(self):
        before = {"assets/a/models/orphan.json": json.dumps({"textures": {"all": "a:missing"}, "ambientocclusion": False})}
        after = {
            "assets/minecraft/models/random_x.json": json.dumps({"textures": {"all": "minecraft:a/missing"}, "ambientocclusion": False}),
            "assets/minecraft/models/random_y.json": json.dumps({"textures": {"all": "minecraft:a/missing"}, "ambientocclusion": True}),
        }
        result = model_origins(before, after, {}, lambda value: value.replace("minecraft:a/", "a:"))
        self.assertEqual(result["assets/minecraft/models/random_x.json"], {"assets/a/models/orphan.json"})
        self.assertNotIn("assets/minecraft/models/random_y.json", result)

    def test_obfuscated_models_keep_separate_origins_and_parent_links(self):
        before = {
            "assets/a/items/first.json": {"model": {"type": "model", "model": "a:first"}},
            "assets/a/items/second.json": {"model": {"type": "model", "model": "a:second"}},
            "assets/a/models/first.json": {"parent": "a:base"},
            "assets/a/models/second.json": {},
            "assets/a/models/base.json": {},
        }
        after = {
            "assets/minecraft/items/a/first.json": {"model": {"type": "model", "model": "minecraft:random_x"}},
            "assets/minecraft/items/a/second.json": {"model": {"type": "model", "model": "minecraft:random_y"}},
            "assets/minecraft/models/random_x.json": {"parent": "minecraft:random_base"},
            "assets/minecraft/models/random_y.json": {},
            "assets/minecraft/models/random_base.json": {},
        }
        result = model_origins({k: json.dumps(v) for k, v in before.items()},
                               {k: json.dumps(v) for k, v in after.items()},
                               {"items": {"a:first": "minecraft:a/first", "a:second": "minecraft:a/second"}})
        self.assertEqual(result["assets/minecraft/models/random_x.json"], {"assets/a/models/first.json"})
        self.assertEqual(result["assets/minecraft/models/random_y.json"], {"assets/a/models/second.json"})
        self.assertEqual(result["assets/minecraft/models/random_base.json"], {"assets/a/models/base.json"})


class VanillaAtlasTest(unittest.TestCase):
    def inspect_sources(self, sources, extra=None):
        vanilla = {
            "assets/minecraft/atlases/blocks.json": {
                "sources": [{"type": "directory", "source": "block", "prefix": "block/"}]},
            "assets/minecraft/atlases/items.json": {"sources": []},
        }
        for block in ("glass", "dead_tube_coral_fan", "heavy_core"):
            vanilla[f"assets/minecraft/blockstates/{block}.json"] = {
                "variants": {"": {"model": "block/" + block}}}
            vanilla[f"assets/minecraft/models/block/{block}.json"] = {
                "textures": {"all": "block/" + block},
                "elements": [{"faces": {"north": {
                    "texture": "all" if block == "heavy_core" else "#all"}}}]}
            vanilla[f"assets/minecraft/textures/block/{block}.png"] = b"pixels"
        vanilla.update(extra or {})
        vanilla = {key: value if isinstance(value, bytes) else json.dumps(value).encode()
                   for key, value in vanilla.items()}
        with tempfile.TemporaryDirectory() as directory:
            path = Path(directory) / "pack.zip"
            with ZipFile(path, "w") as archive:
                archive.writestr("pack.mcmeta", '{"pack":{"min_format":[97,1],"max_format":[97,1]}}')
                archive.writestr("assets/minecraft/atlases/items.json",
                                 json.dumps({"sources": sources}))
            return inspect(path, vanilla)

    def test_detects_vanilla_fallback_blocks_using_item_atlas(self):
        result = self.inspect_sources([
            {"type": "single", "resource": "block/glass"},
            {"type": "single", "resource": "block/dead_tube_coral_fan"}])
        self.assertEqual({row[2] for row in result["block_atlas_failures"]},
                         {"minecraft:block/glass", "minecraft:block/dead_tube_coral_fan"})

    def test_terminal_filter_restores_block_atlas_selection(self):
        result = self.inspect_sources([
            {"type": "single", "resource": "block/glass"},
            {"type": "filter", "pattern": {"namespace": "^minecraft$", "path": "^block/.*"}}])
        self.assertEqual(result["block_atlas_failures"], [])

    def test_later_source_can_restore_filtered_sprite(self):
        result = self.inspect_sources([
            {"type": "filter", "pattern": {"namespace": "^minecraft$", "path": "^block/.*"}},
            {"type": "single", "resource": "block/glass"}])
        self.assertEqual(len(result["block_atlas_failures"]), 1)

    def test_trim_permutations_do_not_report_false_mixed_atlases(self):
        result = self.inspect_sources([
            {"type": "single", "resource": "item/boots"},
            {"type": "paletted_permutations", "textures": ["trims/items/boots"],
             "palette_key": "trim_base", "permutations": {"emerald": "trim/emerald"}}], {
                "assets/minecraft/models/item/boots.json": {
                    "parent": "item/generated",
                    "textures": {"layer0": "item/boots", "layer1": "trims/items/boots_emerald"}},
                "assets/minecraft/items/boots.json": {
                    "model": {"type": "model", "model": "item/boots"}},
                "assets/minecraft/textures/item/boots.png": b"pixels",
                "assets/minecraft/textures/trims/items/boots.png": b"pixels",
            })
        self.assertEqual(result["block_atlas_failures"], [])
        self.assertEqual(result["failures"], [])


if __name__ == "__main__":
    unittest.main()
