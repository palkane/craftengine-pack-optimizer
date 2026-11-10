package io.github.palkane.cepackoptimizer;

import java.nio.file.*;
import java.util.*;

public final class PackOptimizerTest {
    private static int checks;
    private static Path pack() throws Exception {
        Path root = Files.createTempDirectory("ce-slim-test-");
        write(root, "pack.mcmeta", """
                {"pack":{"min_format":[97,1],"max_format":[97,1],"description":"test"}}
                """);
        return root;
    }
    private static void write(Path root, String path, String content) throws Exception {
        Path p = root.resolve(path);
        Files.createDirectories(p.getParent());
        Files.writeString(p, content);
    }
    private static void check(boolean result, String message) {
        checks++;
        if (!result) throw new AssertionError(message);
    }
    private static boolean exists(Path p, String path) { return Files.exists(p.resolve(path)); }
    private static PackOptimizer.Result run(Path p) throws Exception {
        return PackOptimizer.optimize(p, true, List.of("assets/dynamic/textures/runtime/**"));
    }
    private static void delete(Path root) throws Exception {
        try (var paths = Files.walk(root)) {
            for (Path path : paths.sorted(Comparator.reverseOrder()).toList()) Files.delete(path);
        }
    }

    public static void main(String[] args) throws Exception {
        Path p = pack();
        try {
            write(p, "assets/test/items/root.json", """
                    {"model":{"type":"minecraft:model","model":"test:child"}}
                    """);
            write(p, "assets/test/models/child.json", """
                    {"parent":"test:parent","textures":{"all":"test:used","#alias":"#all"}}
                    """);
            write(p, "assets/test/models/parent.json", "{\"parent\":\"minecraft:block/cube_all\"}");
            write(p, "assets/test/textures/used.png", "pixels");
            write(p, "assets/test/textures/used.png.mcmeta", "{\"animation\":{\"frametime\":2}}");
            write(p, "assets/test/models/orphan.json", "{\"textures\":{\"all\":\"test:orphan\"}}");
            write(p, "assets/test/textures/orphan.png", "orphan");
            write(p, "assets/test/textures/orphan.png.mcmeta", "{\"animation\":{}}");
            write(p, "assets/test/models/cycle_a.json", "{\"parent\":\"test:cycle_b\"}");
            write(p, "assets/test/models/cycle_b.json", "{\"parent\":\"test:cycle_a\"}");
            write(p, "assets/test/font/default.json", """
                    {"providers":[{"type":"bitmap","file":"test:font.png","chars":["x"]}]}
                    """);
            write(p, "assets/test/textures/font.png", "font");
            write(p, "assets/test/sounds.json", "{\"bell\":{\"sounds\":[\"test:bell\"]}}");
            write(p, "assets/test/sounds/bell.ogg", "sound");
            write(p, "assets/test/textures/entity/robot.png", "entity");
            write(p, "assets/minecraft/textures/block/original.png", "vanilla");
            write(p, "assets/minecraft/models/block/original.json", "{}");
            write(p, "assets/dynamic/textures/runtime/effect.png", "dynamic");
            var result = run(p);
            check(exists(p, "assets/test/models/child.json"), "item model root lost");
            check(exists(p, "assets/test/models/parent.json"), "parent model lost");
            check(Files.readString(p.resolve("assets/test/textures/used.png")).equals("pixels"), "texture changed");
            check(exists(p, "assets/test/textures/used.png.mcmeta"), "animation metadata lost");
            check(exists(p, "assets/test/textures/font.png"), "font bitmap lost");
            check(exists(p, "assets/test/sounds/bell.ogg"), "sound lost");
            check(exists(p, "assets/test/textures/entity/robot.png"), "dynamic entity texture lost");
            check(exists(p, "assets/minecraft/textures/block/original.png"), "implicit vanilla texture lost");
            check(exists(p, "assets/minecraft/models/block/original.json"), "implicit vanilla model lost");
            check(exists(p, "assets/dynamic/textures/runtime/effect.png"), "keep glob ignored");
            check(!exists(p, "assets/test/models/orphan.json"), "unused model retained");
            check(!exists(p, "assets/test/models/cycle_a.json"), "unreachable cycle retained");
            check(!exists(p, "assets/test/textures/orphan.png"), "unused texture retained");
            check(!exists(p, "assets/test/textures/orphan.png.mcmeta"), "orphan metadata retained");
            check(result.removedModels() == 3 && result.removedTextures() == 1
                    && result.removedMetadata() == 1, "incorrect deletion counts");
            check(run(p).beforeBytes() == run(p).afterBytes(), "not idempotent");
        } finally { delete(p); }

        p = pack();
        try {
            write(p, "pack.mcmeta", """
                    {"pack":{"min_format":[97,1],"max_format":[97,1]},
                     "overlays":{"entries":[
                      {"directory":"old","min_format":[69,0],"max_format":[96,999]},
                      {"directory":"first","min_format":[97,0],"max_format":[97,1]},
                      {"directory":"last","min_format":[97,1],"max_format":[97,1]}]}}
                    """);
            write(p, "assets/test/items/item.json", "{\"value\":\"base\"}");
            write(p, "old/assets/test/items/item.json", "{\"value\":\"old\"}");
            write(p, "first/assets/test/items/item.json", "{\"value\":\"first\"}");
            write(p, "last/assets/test/items/item.json", "{\"value\":\"last\"}");
            var result = run(p);
            check(Files.readString(p.resolve("assets/test/items/item.json")).contains("last"), "overlay precedence wrong");
            check(!exists(p, "old") && !exists(p, "first") && !exists(p, "last"), "overlay files retained");
            check(!Files.readString(p.resolve("pack.mcmeta")).contains("overlays"), "overlay metadata retained");
            check(result.flattenedOverlays() == 3, "incorrect overlay count");
        } finally { delete(p); }

        p = pack();
        try {
            write(p, "pack.mcmeta", """
                    {"pack":{"min_format":[97,0],"max_format":[97,1]},
                     "overlays":{"entries":[{"directory":"patch","min_format":[97,1],"max_format":[97,1]}]}}
                    """);
            write(p, "patch/assets/test/items/item.json", "{}");
            check(run(p).flattenedOverlays() == 0, "multi-version pack collapsed");
            check(exists(p, "patch/assets/test/items/item.json"), "multi-version overlay lost");
            write(p, "assets/test/items/broken.json", "{");
            var before = Files.readAllBytes(p.resolve("pack.mcmeta"));
            try {
                run(p);
                throw new AssertionError("malformed JSON accepted");
            } catch (java.io.IOException expected) {
                check(Arrays.equals(before, Files.readAllBytes(p.resolve("pack.mcmeta"))), "failed analysis changed staging");
            }
        } finally { delete(p); }

        p = pack();
        try {
            write(p, "assets/custom/atlases/blocks.json", """
                    {"sources":[{"type":"directory","source":"block","prefix":"block/"}]}
                    """);
            write(p, "assets/custom/textures/block/implicit.png", "implicit");
            run(p);
            check(exists(p, "assets/custom/textures/block/implicit.png"), "atlas directory texture removed");
            write(p, "assets/custom/atlases/blocks.json", """
                    {"sources":[{"type":"directory","source":"foreign:block","prefix":"block/"}]}
                    """);
            write(p, "assets/foreign/textures/block/implicit.png", "implicit");
            run(p);
            check(exists(p, "assets/foreign/textures/block/implicit.png"), "cross-namespace atlas texture removed");
        } finally { delete(p); }
        p = pack();
        try {
            write(p, "assets/test/items/a.json", "{\"model\":{\"type\":\"minecraft:model\",\"model\":\"test:a\"}}");
            write(p, "assets/test/items/b.json", "{\"model\":{\"type\":\"minecraft:model\",\"model\":\"test:b\"}}");
            write(p, "assets/test/models/a.json", "{\"parent\":\"minecraft:item/generated\",\"textures\":{\"layer0\":\"test:used\"}}");
            write(p, "assets/test/models/b.json", "{\"textures\":{\"layer0\":\"test:used\"},\"parent\":\"minecraft:item/generated\"}");
            write(p, "assets/test/models/opaque.json", "{\"parent\":\"minecraft:item/generated\",\"textures\":{\"layer0\":\"test:used\"}}");
            write(p, "assets/test/font/custom.json", "{\"unknown\":\"test:opaque\"}");
            write(p, "assets/test/textures/used.png", "pixels");
            var result = run(p);
            check(exists(p, "assets/test/items/a.json") && exists(p, "assets/test/items/b.json"), "public item IDs changed");
            check(!exists(p, "assets/test/models/b.json"), "identical model not shared");
            check(Files.readString(p.resolve("assets/test/items/b.json")).contains("test:a"), "model reference not rewritten");
            check(exists(p, "assets/test/models/opaque.json"), "opaque reference target lost");
            check(result.deduplicatedModels() == 1, "duplicate model count wrong");
        } finally { delete(p); }
        System.out.println("PASS " + checks + " behavior checks, failures 0");
    }
}
