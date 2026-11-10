package io.github.palkane.cepackoptimizer;

import com.google.gson.*;
import java.nio.file.*;
import java.util.*;
import java.util.zip.*;

public final class NamespaceCompatibilityTest {
    private static int checks;
    private static void check(boolean value, String message) {
        checks++;
        if (!value) throw new AssertionError(message);
    }
    private static void put(Path root, String file, String value) throws Exception {
        Path path = root.resolve(file);
        Files.createDirectories(path.getParent());
        Files.writeString(path, value);
    }
    private static JsonObject read(Path root, String file) throws Exception {
        return JsonParser.parseString(Files.readString(root.resolve(file))).getAsJsonObject();
    }
    public static void main(String[] args) throws Exception {
        Path work = Files.createTempDirectory("namespace-compat-"), root = work.resolve("pack");
        try {
            var validation = new net.momirealms.craftengine.core.pack.workflow.PackWorkflowValidation(work, Map.of());
            validation.generate();
            try {
                NamespaceCompatibility.validateBinding(validation);
                throw new AssertionError("binding before protected ZIP accepted");
            } catch (IllegalArgumentException expected) {
                check(true, "binding order checked");
            }
            validation.zip("./generated/resource_pack.zip", true);
            NamespaceCompatibility.validateBinding(validation);
            check(true, "binding after protected ZIP rejected");
            put(root, "pack.mcmeta", "{\"pack\":{\"pack_format\":97},\"overlays\":{\"entries\":[{\"directory\":\"patch\",\"formats\":97}]}}");
            put(root, "assets/ui/font/main.json", "{\"providers\":[{\"type\":\"bitmap\",\"file\":\"ui:font/a.png\",\"chars\":[\"x\"]}]}");
            put(root, "patch/assets/ui/font/main.json", "{\"providers\":[]}");
            put(root, "assets/ui/textures/font/a.png", "font-image");
            put(root, "assets/ui/items/icon.json", "{\"model\":{\"type\":\"minecraft:model\",\"model\":\"ui:item/a\"}}");
            put(root, "assets/ui/models/item/a.json", "{\"textures\":{\"layer0\":\"ui:item/a\"}}");
            put(root, "assets/ui/sounds.json", "{\"click\":{\"subtitle\":\"ui.subtitle\",\"sounds\":[\"ui:click\"]}}");
            put(root, "assets/ui/sounds/click.ogg", "sound-bytes");
            put(root, "assets/ui/equipment/hat.json", "{\"layers\":{\"humanoid\":[{\"texture\":\"ui:hat\"}]}}");
            put(root, "assets/ui/textures/entity/equipment/humanoid/hat.png", "hat-image");
            put(root, "assets/ui/textures/item/break.png", "dynamic-image");
            put(root, "assets/ui/textures/item/break.png.mcmeta", "{\"animation\":{}}");
            put(root, "assets/ui/blockstates/a.json", "{\"variants\":{\"\":{\"model\":\"ui:item/a\"}}}");
            put(root, "assets/minecraft/atlases/items.json", "{\"sources\":[{\"type\":\"single\",\"resource\":\"ui:corners/a\",\"sprite\":\"ui:corners/a\"}]}");
            put(root, "assets/ui/textures/corners/a.png", "corner-image");
            var result = NamespaceCompatibility.prepare(root, List.of("assets/ui/textures/item/break.png"));
            check(result.itemModels().get("ui:icon").equals("custom:ui/icon"), "client item map missing");
            check(!Files.exists(root.resolve("assets/ui/items/icon.json")), "item identities still inflate compatibility ZIP");
            check(read(root, "assets/ui/font/main.json").getAsJsonArray("providers").get(0)
                    .getAsJsonObject().get("id").getAsString().equals("custom:ui/main"), "old font ID not bridged");
            check(Files.exists(root.resolve("patch/assets/ui/font/main.json")), "font overlay alias lost");
            check(Files.readString(root.resolve("assets/custom/textures/ui/font/a.png")).equals("font-image"),
                    "font texture bytes changed");
            JsonObject sound = read(root, "assets/ui/sounds.json").getAsJsonObject("click");
            check(sound.getAsJsonArray("sounds").get(0).getAsJsonObject().get("name").getAsString()
                    .equals("custom:ui/click"), "old sound ID not bridged");
            check(sound.get("subtitle").getAsString().equals("ui.subtitle"), "sound subtitle lost");
            check(read(root, "assets/ui/equipment/hat.json").getAsJsonObject("layers").getAsJsonArray("humanoid")
                    .get(0).getAsJsonObject().get("texture").getAsString().equals("ui:hat"), "equipment texture addressing changed");
            check(Files.readString(root.resolve("assets/ui/textures/entity/equipment/humanoid/hat.png")).equals("hat-image"),
                    "server-addressed equipment texture missing");
            check(Files.readString(root.resolve("assets/ui/textures/item/break.png")).equals("dynamic-image"),
                    "server-addressed dynamic texture missing");
            check(Files.exists(root.resolve("assets/ui/textures/item/break.png.mcmeta")), "dynamic texture metadata missing");
            check(read(root, "assets/ui/blockstates/a.json").getAsJsonObject("variants").getAsJsonObject("")
                    .get("model").getAsString().equals("custom:ui/item/a"), "blockstate alias references old model");
            JsonArray atlas = read(root, "assets/minecraft/atlases/items.json").getAsJsonArray("sources");
            check(atlas.size() == 2 && result.spriteAliases() == 1, "external sprite ID not bridged");
            check(atlas.get(1).getAsJsonObject().get("sprite").getAsString().equals("ui:corners/a")
                    && atlas.get(1).getAsJsonObject().get("resource").getAsString().equals("custom:ui/corners/a"),
                    "sprite alias does not use migrated PNG");
            Path zipFile = work.resolve("source.zip");
            try (var zip = new ZipOutputStream(Files.newOutputStream(zipFile)); var paths = Files.walk(root)) {
                for (Path path : paths.filter(Files::isRegularFile).sorted().toList()) {
                    zip.putNextEntry(new ZipEntry(root.relativize(path).toString()));
                    Files.copy(path, zip); zip.closeEntry();
                }
            }
            Path state = work.resolve("published");
            Files.createDirectories(state);
            Files.writeString(state.resolve("current.json"), "old pack and mappings");
            var binding = new NamespaceCompatibility.Binding(
                    NamespaceShards.sha1(Files.readAllBytes(zipFile)), result);
            int[] uploads = {0};
            try {
                NamespaceShardHost.publishCompatibilityGroup(zipFile, state, binding, (file, bytes, hash) -> {
                    if (++uploads[0] == 2) throw new java.io.IOException("upload failure");
                });
                throw new AssertionError("partial upload accepted");
            } catch (java.io.IOException expected) {
                check(Files.readString(state.resolve("current.json")).equals("old pack and mappings"),
                        "partial upload replaced active mappings");
            }
            try {
                NamespaceShardHost.publishCompatibilityGroup(zipFile, state,
                        new NamespaceCompatibility.Binding("wrong-hash", result), (f, b, h) -> {});
                throw new AssertionError("mismatched binding accepted");
            } catch (java.io.IOException expected) {
                check(Files.readString(state.resolve("current.json")).equals("old pack and mappings"),
                        "stale mapping replaced active publication");
            }
            var missing = new NamespaceCompatibility.Result(result.merge(), result.compatibilityNamespaces(),
                    result.aliases(), result.spriteAliases(), Map.of("ui:icon", "custom:missing"));
            try {
                NamespaceShardHost.publishCompatibilityGroup(zipFile, state,
                        new NamespaceCompatibility.Binding(binding.sourceSha1(), missing), (f, b, h) -> {
                            throw new AssertionError("upload began with missing model");
                        });
                throw new AssertionError("missing model accepted");
            } catch (java.io.IOException expected) {
                check(Files.readString(state.resolve("current.json")).equals("old pack and mappings"),
                        "missing model replaced active publication");
            }
            var manifest = NamespaceShardHost.publishCompatibilityGroup(zipFile, state, binding, (file, bytes, hash) -> {
                check(Files.readString(state.resolve("current.json")).equals("old pack and mappings"),
                        "publication committed before both downloads ready");
            });
            check(manifest.shards().size() == 2, "not one main and one compatibility pack");
            check(new HashSet<>(manifest.shards().get(0).namespaces()).equals(Set.of("minecraft", "custom")),
                    "main pack contains old namespaces");
            check(manifest.shards().get(1).namespaces().equals(List.of("ui")), "compatibility entry points misplaced");
            check(manifest.itemModels().equals(result.itemModels()), "published pack and mapping differ");
            Set<String> union = new HashSet<>();
            for (var shard : manifest.shards()) try (var zip = new ZipFile(state.resolve("objects").resolve(shard.file()).toFile())) {
                check(zip.getEntry("pack.mcmeta") != null, "pack cannot mount independently");
                for (var entry : zip.stream().toList()) {
                    if (entry.getName().equals("pack.mcmeta")) continue;
                    check(union.add(entry.getName()), "resource in both ZIPs");
                    check(Arrays.equals(zip.getInputStream(entry).readAllBytes(), Files.readAllBytes(root.resolve(entry.getName()))),
                            "published resource bytes changed");
                }
            }
            try (var paths = Files.walk(root)) {
                check(union.size() == paths.filter(Files::isRegularFile).count() - 1,
                        "resources lost in publication");
            }
        } finally {
            try (var paths = Files.walk(work)) {
                for (Path path : paths.sorted(Comparator.reverseOrder()).toList()) Files.delete(path);
            }
        }
        System.out.println("Namespace compatibility checks passed: " + checks);
    }
}
