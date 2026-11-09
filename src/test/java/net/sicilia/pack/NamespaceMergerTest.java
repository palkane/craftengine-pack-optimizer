package net.sicilia.pack;

import com.google.gson.*;
import java.nio.file.*;
import java.util.*;

public final class NamespaceMergerTest {
    private static int checks;
    private static void check(boolean value, String message) {
        checks++;
        if (!value) throw new AssertionError(message);
    }
    private static void put(Path root, String name, String data) throws Exception {
        Path path = root.resolve(name);
        Files.createDirectories(path.getParent());
        Files.writeString(path, data);
    }
    private static JsonObject read(Path root, String name) throws Exception {
        return JsonParser.parseString(Files.readString(root.resolve(name))).getAsJsonObject();
    }
    private static void clean(Path root) throws Exception {
        try (var paths = Files.walk(root)) {
            for (Path path : paths.sorted(Comparator.reverseOrder()).toList()) Files.delete(path);
        }
    }
    public static void main(String[] args) throws Exception {
        Path root = Files.createTempDirectory("ce-namespace-merge-");
        try {
            put(root, "pack.mcmeta", "{\"pack\":{\"min_format\":[97,1],\"max_format\":[97,1]},"
                    + "\"overlays\":{\"entries\":[{\"directory\":\"layer\",\"formats\":97}]}}");
            put(root, "pack.png", "root-picture");
            put(root, "assets/a/models/item/icon.json",
                    "{\"parent\":\"minecraft:item/generated\",\"textures\":{\"layer0\":\"b:item/icon\"},"
                    + "\"text\":\"a:literal\",\"type\":\"a:registry_type\",\"unknown\":\"preserved\"}");
            put(root, "assets/b/textures/item/icon.png", "image-bytes");
            put(root, "assets/a/items/icon.json", "{\"model\":{\"type\":\"minecraft:model\",\"model\":\"a:item/icon\"}}");
            put(root, "assets/a/font/main.json",
                    "{\"providers\":[{\"type\":\"bitmap\",\"file\":\"b:item/icon.png\",\"chars\":[\"a\"]},"
                    + "{\"type\":\"reference\",\"id\":\"b:fallback\"}]}");
            put(root, "assets/b/font/fallback.json", "{\"providers\":[]}");
            put(root, "assets/a/sounds.json", "{\"hit\":{\"sounds\":[\"b:hit\"]}}");
            put(root, "assets/b/sounds.json", "{\"hit\":{\"sounds\":[{\"name\":\"a:event\",\"type\":\"event\"}]}}");
            put(root, "assets/a/lang/en_us.json", "{\"name.a\":\"A\"}");
            put(root, "assets/b/lang/en_us.json", "{\"name.b\":\"B\"}");
            put(root, "assets/minecraft/models/keep.json", "{\"parent\":\"a:item/icon\"}");
            put(root, "assets/a/shaders/include/test.glsl", "#include <b:other.glsl>\n#moj_import <minecraft:fog.glsl>");
            put(root, "layer/assets/b/models/override.json", "{\"parent\":\"a:item/icon\"}");
            put(root, "inactive/assets/a/models/unused.json", "{\"parent\":\"b:other\"}");
            var result = NamespaceMerger.optimize(root);
            check(result.namespacesAfter().equals(Set.of("minecraft", "custom")), "not two namespaces");
            check(result.runtimeIds().get("items").get("a:icon").equals("custom:a/icon"), "missing runtime item map");
            check(result.runtimeIds().get("font").get("a:main").equals("custom:a/main"), "missing font map");
            check(result.runtimeIds().get("sound").get("a:hit").equals("custom:a/hit"), "missing sound event map");
            JsonObject model = read(root, "assets/custom/models/a/item/icon.json");
            check(model.get("parent").getAsString().equals("minecraft:item/generated"), "vanilla parent changed");
            check(model.getAsJsonObject("textures").get("layer0").getAsString().equals("custom:b/item/icon"), "texture not rewritten");
            check(model.get("text").getAsString().equals("a:literal"), "literal text changed");
            check(model.get("type").getAsString().equals("a:registry_type"), "registry type changed");
            check(model.get("unknown").getAsString().equals("preserved"), "unknown field changed");
            check(Files.readString(root.resolve("assets/custom/textures/b/item/icon.png")).equals("image-bytes"), "image changed");
            JsonArray providers = read(root, "assets/custom/font/a/main.json").getAsJsonArray("providers");
            check(providers.get(0).getAsJsonObject().get("file").getAsString().equals("custom:b/item/icon.png"), "font PNG not rewritten");
            check(providers.get(1).getAsJsonObject().get("id").getAsString().equals("custom:b/fallback"), "font reference not rewritten");
            check(read(root, "assets/custom/sounds.json").keySet().equals(Set.of("a/hit", "b/hit")), "sound event collision");
            check(read(root, "assets/custom/lang/en_us.json").keySet().equals(Set.of("name.a", "name.b")), "language loss");
            check(read(root, "assets/minecraft/models/keep.json").get("parent").getAsString().equals("custom:a/item/icon"), "vanilla reference not rewritten");
            check(Files.readString(root.resolve("assets/custom/shaders/include/a/test.glsl"))
                    .equals("#include <custom:b/other.glsl>\n#moj_import <minecraft:fog.glsl>"), "shader import broken");
            check(read(root, "layer/assets/custom/models/b/override.json").get("parent").getAsString().equals("custom:a/item/icon"), "overlay not migrated");
            check(read(root, "inactive/assets/custom/models/a/unused.json").get("parent").getAsString()
                    .equals("custom:b/other"), "inactive resource layer lost");
            check(!Files.readString(root.resolve("pack.mcmeta")).contains("inactive"),
                    "inactive resource layer enabled");
            check(!Files.exists(root.resolve("assets/a")), "old namespace directory remains");
            check(Files.readString(root.resolve("pack.png")).equals("root-picture"), "root metadata changed");
            check(NamespaceMerger.optimize(root).moved() == 0, "not idempotent");
        } finally { clean(root); }

        for (boolean malformed : List.of(false, true)) {
            root = Files.createTempDirectory("ce-namespace-reject-");
            try {
                put(root, "pack.mcmeta", "{\"pack\":{}}");
                put(root, "assets/a/lang/en_us.json", "{\"same\":\"A\"}");
                put(root, "assets/b/lang/en_us.json", malformed ? "broken" : "{\"same\":\"B\"}");
                boolean rejected = false;
                try { NamespaceMerger.optimize(root); } catch (RuntimeException expected) { rejected = true; }
                check(rejected, "bad input not rejected");
                check(Files.readString(root.resolve("assets/a/lang/en_us.json")).equals("{\"same\":\"A\"}"), "partial mutation");
                check(!Files.exists(root.resolve("assets/custom")), "partial output");
            } finally { clean(root); }
        }
        System.out.println("Namespace merger checks passed: " + checks);
    }
}
