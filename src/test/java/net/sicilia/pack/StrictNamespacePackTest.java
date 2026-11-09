package net.sicilia.pack;

import com.google.gson.*;
import java.nio.file.*;
import java.util.*;
import java.util.zip.*;

public final class StrictNamespacePackTest {
    private static int checks;
    private static void check(boolean value, String message) {
        checks++;
        if (!value) throw new AssertionError(message);
    }
    private static void add(ZipOutputStream zip, String name, String value) throws Exception {
        zip.putNextEntry(new ZipEntry(name));
        zip.write(value.getBytes(java.nio.charset.StandardCharsets.UTF_8));
        zip.closeEntry();
    }
    public static void main(String[] args) throws Exception {
        Path temp = Files.createTempDirectory("strict-namespace-test-");
        try {
            Path source = temp.resolve("source.zip"), output = temp.resolve("strict.zip");
            try (var zip = new ZipOutputStream(Files.newOutputStream(source))) {
                add(zip, "pack.mcmeta", "{\"pack\":{\"min_format\":[97,1],\"max_format\":[97,1]}}");
                add(zip, "assets/minecraft/atlases/items.json/", "{\"sources\":[]}");
                add(zip, "assets/demo/textures/item/icon.png/", "test pixels");
                add(zip, "assets/demo/models/item/icon.json/", "{\"textures\":{\"layer0\":\"demo:item/icon\"}}");
                add(zip, "assets/demo/items/icon.json/", "{\"model\":{\"type\":\"minecraft:model\",\"model\":\"demo:item/icon\"}}");
                add(zip, "assets/demo/equipment/armor.json/", "{\"layers\":{\"humanoid\":[{\"texture\":\"demo:armor\"}]}}");
                add(zip, "assets/demo/textures/entity/equipment/humanoid/armor.png/", "armor pixels");
                add(zip, "assets/demo/font/main.json/", "{\"providers\":[]}");
                add(zip, "assets/demo/sounds.json/", "{\"click\":{\"sounds\":[]}}");
                add(zip, "inactive/assets/demo/textures/item/hidden.png/", "inactive");
            }
            byte[] original = Files.readAllBytes(source);
            var result = StrictNamespacePack.build(source, output, temp.resolve("work"));
            StrictNamespaceHost.validate(output, result);
            check(true, "strict publication rejected");
            try {
                StrictNamespaceHost.validate(source, result);
                throw new AssertionError("mismatched ZIP and mappings accepted");
            } catch (java.io.IOException expected) {
                check(true, "binding rejected");
            }
            check(result.merge().namespacesAfter().equals(Set.of("custom", "minecraft")), "not exactly two namespaces");
            check(Arrays.equals(original, Files.readAllBytes(source)), "source pack modified");
            try (var zip = new ZipFile(output.toFile())) {
                check(zip.stream().noneMatch(e -> e.getName().startsWith("assets/demo/")), "legacy namespace retained");
                check(zip.getEntry("assets/custom/items/demo/icon.json") != null, "mapped item entry absent");
                check(zip.getEntry("assets/custom/textures/entity/equipment/humanoid/demo/armor.png") != null,
                        "equipment texture not in layer-relative directory");
                check(zip.getEntry("assets/custom/textures/demo/entity/equipment/humanoid/armor.png") == null,
                        "equipment uses the generic, invalid texture layout");
                var entry = zip.getEntry("assets/minecraft/atlases/items.json");
                JsonArray sources = JsonParser.parseString(new String(zip.getInputStream(entry).readAllBytes(),
                        java.nio.charset.StandardCharsets.UTF_8)).getAsJsonObject().getAsJsonArray("sources");
                check(sources.asList().stream().anyMatch(e -> e.getAsJsonObject().has("resource")
                        && e.getAsJsonObject().get("resource").getAsString().equals("custom:demo/item/icon")),
                        "implicit vanilla item directory sprite was lost");
                check(sources.asList().stream().noneMatch(e -> e.getAsJsonObject().has("resource")
                        && e.getAsJsonObject().get("resource").getAsString().endsWith("/hidden")),
                        "inactive texture enabled by base atlas");
            }
            check(result.merge().runtimeIds().get("items").get("demo:icon").equals("custom:demo/icon"),
                    "client item model mapping absent");
            check(result.merge().runtimeIds().get("font").get("demo:main").equals("custom:demo/main"),
                    "client font mapping absent");
            check(result.merge().runtimeIds().get("sound").get("demo:click").equals("custom:demo/click"),
                    "client sound mapping absent");
            System.out.println("Strict namespace pack checks passed: " + checks);
        } finally {
            try (var paths = Files.walk(temp)) {
                for (Path path : paths.sorted(Comparator.reverseOrder()).toList()) Files.delete(path);
            }
        }
    }
}
