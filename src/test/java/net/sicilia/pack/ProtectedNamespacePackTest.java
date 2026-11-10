package net.sicilia.pack;

import com.google.gson.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import java.util.zip.*;

public final class ProtectedNamespacePackTest {
    private static int checks;
    private static void check(boolean value, String message) {
        checks++;
        if (!value) throw new AssertionError(message);
    }
    private static void put(Path root, String name, String text) throws Exception {
        Path file = root.resolve(name);
        Files.createDirectories(file.getParent());
        Files.writeString(file, text);
    }
    private static JsonObject json(Path root, String path) throws Exception {
        return JsonParser.parseString(Files.readString(root.resolve(path))).getAsJsonObject();
    }
    private static void clean(Path root) throws Exception {
        try (var paths = Files.walk(root)) {
            for (Path path : paths.sorted(Comparator.reverseOrder()).toList()) Files.delete(path);
        }
    }
    public static void main(String[] args) throws Exception {
        Path temp = Files.createTempDirectory("protected-prefix-");
        try {
            Path root = temp.resolve("pack");
            put(root, "pack.mcmeta", "{\"pack\":{\"min_format\":[97,1],\"max_format\":[97,1]}}");
            put(root, "assets/minecraft/models/item/keep.json", "{\"parent\":\"minecraft:item/generated\"}");
            put(root, "assets/minecraft/sounds.json", "{\"keep\":{\"sounds\":[]}}");
            put(root, "assets/minecraft/lang/en_us.json", "{\"vanilla.name\":\"Vanilla\"}");
            put(root, "assets/demo/lang/en_us.json", "{\"demo.name\":\"Demo\"}");
            put(root, "assets/demo/sounds.json", "{\"click\":{\"sounds\":[\"demo:click\"]}}");
            put(root, "assets/demo/sounds/click.ogg", "audio");
            put(root, "assets/demo/items/icon.json", "{\"model\":{\"type\":\"minecraft:model\",\"model\":\"demo:item/icon\"}}");
            put(root, "assets/demo/models/item/icon.json", "{\"parent\":\"minecraft:item/generated\",\"textures\":{\"layer0\":\"demo:item/icon\"}}");
            put(root, "assets/demo/textures/item/icon.png", "pixels");
            put(root, "assets/demo/font/main.json", "{\"providers\":[{\"type\":\"bitmap\",\"file\":\"demo:item/icon.png\",\"chars\":[\"x\"]}]}");
            put(root, "assets/custom/items/old.json", "{\"model\":{\"type\":\"minecraft:empty\"}}");
            put(root, "assets/demo/equipment/armor.json", "{\"layers\":{\"humanoid\":[{\"texture\":\"demo:armor\"}]}}");
            put(root, "assets/demo/textures/entity/equipment/humanoid/armor.png", "armor");
            put(root, "assets/minecraft/atlases/items.json", "{\"sources\":[]}");
            put(root, "assets/demo/shaders/include/color.glsl", "#moj_import <custom:shared.glsl>");
            put(root, "assets/custom/shaders/include/shared.glsl", "// shared");
            byte[] vanilla = Files.readAllBytes(root.resolve("assets/minecraft/models/item/keep.json"));
            var prepared = ProtectedNamespacePack.prepare(root);
            check(prepared.merge().namespacesAfter().equals(Set.of("minecraft")), "not a single namespace");
            check(!Files.exists(root.resolve("assets/custom")) && !Files.exists(root.resolve("assets/demo")), "old namespace remains");
            check(Files.exists(root.resolve("assets/minecraft/items/custom/old.json")), "custom input was incorrectly exempt");
            check(Arrays.equals(vanilla, Files.readAllBytes(root.resolve("assets/minecraft/models/item/keep.json"))), "vanilla content changed");
            check(json(root, "assets/minecraft/sounds.json").keySet().equals(Set.of("keep", "demo/click")), "vanilla sound lost");
            check(json(root, "assets/minecraft/lang/en_us.json").keySet().equals(Set.of("vanilla.name", "demo.name")), "language merge lost values");
            check(json(root, "assets/minecraft/items/demo/icon.json").getAsJsonObject("model").get("model")
                    .getAsString().equals("minecraft:demo/item/icon"), "item model not translated");
            check(json(root, "assets/minecraft/font/demo/main.json").getAsJsonArray("providers").get(0)
                    .getAsJsonObject().get("file").getAsString().equals("minecraft:demo/item/icon.png"), "font reference not translated");
            check(Files.exists(root.resolve("assets/minecraft/textures/entity/equipment/humanoid/demo/armor.png")), "equipment layout broken");
            check(Files.readString(root.resolve("assets/minecraft/shaders/include/demo/color.glsl"))
                    .equals("#moj_import <minecraft:custom/shared.glsl>"), "shader include broken");
            check(json(root, "assets/minecraft/atlases/items.json").toString().contains("\"source\":\"demo/item\""),
                    "relocated item directory missing");
            check(!json(root, "assets/minecraft/atlases/items.json").toString().contains("\"resource\""),
                    "early single sources would dangle after native texture atlasing");
            check(prepared.merge().runtimeIds().get("sprites").get("demo:item/icon").equals("minecraft:demo/item/icon"),
                    "directory sprite mapping missing");
            var mappings = new ClientResourceMappings(prepared.merge().runtimeIds());
            for (String type : List.of("items", "font", "sound", "equipment"))
                for (var entry : prepared.merge().runtimeIds().get(type).entrySet())
                    check(mappings.inverse().id(type, mappings.id(type, entry.getKey())).equals(entry.getKey()),
                            "resource roundtrip failed: " + type);
            check(mappings.id("items", "minecraft:stick").equals("minecraft:stick"), "vanilla ID changed");
            check(mappings.id("items", "external:missing").equals("external:missing"), "unpublished resource changed");

            // A nonempty slash-ended entry and deliberately wrong central CRC must survive sealing.
            Path archive = temp.resolve("protected.zip");
            try (var zip = new ZipOutputStream(Files.newOutputStream(archive)); var paths = Files.walk(root)) {
                for (Path file : paths.filter(Files::isRegularFile).sorted().toList()) {
                    zip.putNextEntry(new ZipEntry(root.relativize(file).toString() + "/"));
                    zip.write(Files.readAllBytes(file));
                    zip.closeEntry();
                }
            }
            byte[] bytes = Files.readAllBytes(archive);
            int central = -1;
            for (int i = 0; i < bytes.length - 46; i++)
                if (bytes[i] == 0x50 && bytes[i+1] == 0x4b && bytes[i+2] == 1 && bytes[i+3] == 2) {
                    central = i;
                    bytes[i+16] ^= 0x7f;
                    break;
                }
            check(central >= 0, "invalid test archive");
            Files.write(archive, bytes);
            var binding = ProtectedNamespacePack.seal(archive, prepared);
            Path bindingPath = temp.resolve("binding.json");
            ProtectedNamespacePack.saveBinding(bindingPath, binding);
            check(ProtectedNamespacePack.readBinding(bindingPath, archive).equals(binding), "binding did not reload");
            check(Arrays.equals(bytes, Files.readAllBytes(archive)), "protection bytes changed during binding");
            byte[] changed = bytes.clone();
            changed[10] ^= 1;
            Files.write(archive, changed);
            try {
                ProtectedNamespacePack.readBinding(bindingPath, archive);
                throw new AssertionError("mismatched protected ZIP accepted");
            } catch (java.io.IOException expected) { checks++; }
            check(NamespaceMerger.optimize(root, "minecraft").moved() == 0, "not idempotent");
        } finally { clean(temp); }

        for (boolean sound : List.of(false, true)) {
            temp = Files.createTempDirectory("prefix-collision-");
            try {
                put(temp, "pack.mcmeta", "{\"pack\":{}}");
                String original = sound ? "{\"hit\":{\"sounds\":[]}}" : "{\"model\":{\"type\":\"minecraft:empty\"}}";
                String path = sound ? "sounds.json" : "items/tool.json";
                put(temp, "assets/demo/" + path, original);
                put(temp, sound ? "assets/minecraft/sounds.json" : "assets/minecraft/items/demo/tool.json",
                        sound ? "{\"demo/hit\":{\"sounds\":[]}}" : original);
                try {
                    NamespaceMerger.optimize(temp, "minecraft");
                    throw new AssertionError("vanilla/prefix collision accepted");
                } catch (IllegalArgumentException expected) { checks++; }
                check(Files.readString(temp.resolve("assets/demo/" + path)).equals(original), "collision mutated source");
            } finally { clean(temp); }
        }
        System.out.println("Protected namespace pack checks passed: " + checks);
    }
}
