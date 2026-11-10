package io.github.palkane.cepackoptimizer;

import com.google.gson.*;
import java.io.IOException;
import java.nio.file.*;
import java.util.*;

/** Keeps server-addressed entry points in a small sibling pack, not in the large ZIP. */
public final class NamespaceCompatibility {
    private static final Gson JSON = new GsonBuilder().disableHtmlEscaping().create();
    public record Result(NamespaceMerger.Result merge, Set<String> compatibilityNamespaces,
                         int aliases, int spriteAliases, Map<String, String> itemModels) {}
    public record Binding(String sourceSha1, Result result) {}

    static void validateBinding(net.momirealms.craftengine.core.pack.workflow.PackWorkflowValidation validation) {
        if (!validation.usesProtection())
            throw new IllegalArgumentException("namespace_bind must follow protected ZIP packaging");
        validation.resolvePath("./generated/resource_pack.zip");
    }

    public static Result prepare(Path root, List<String> keep) throws IOException {
        Map<String, byte[]> originals = new TreeMap<>();
        try (var paths = Files.walk(root)) {
            for (Path path : paths.filter(Files::isRegularFile).toList())
                originals.put(root.relativize(path).toString().replace('\\', '/'), Files.readAllBytes(path));
        }
        var merged = NamespaceMerger.optimize(root);
        Set<String> namespaces = new TreeSet<>();
        int aliases = 0, sprites = 0;
        List<PathMatcher> matchers = keep.stream()
                .map(pattern -> root.getFileSystem().getPathMatcher("glob:" + pattern)).toList();
        for (var entry : originals.entrySet()) {
            String path = entry.getKey();
            int start = path.startsWith("assets/") ? 0 : path.indexOf("/assets/") + 1;
            if (start < 0 || !path.substring(start).startsWith("assets/")) continue;
            String layer = path.substring(0, start);
            String[] parts = path.substring(start).split("/", 3);
            if (parts.length != 3) continue;
            String ns = parts[1], local = parts[2];
            String id = ns + ":" + local.substring(local.indexOf('/') + 1).replaceFirst("\\.json$", "");
            Path originalPath = root.resolve(path);
            byte[] alias = null;
            if (!ns.equals("minecraft") && local.startsWith("font/") && local.endsWith(".json")) {
                String target = merged.runtimeIds().getOrDefault("font", Map.of()).get(id);
                if (target == null) throw new IOException("Missing font mapping: " + id);
                JsonObject font = JsonParser.parseString(new String(entry.getValue(),
                        java.nio.charset.StandardCharsets.UTF_8)).getAsJsonObject();
                JsonArray providers = new JsonArray();
                JsonObject reference = new JsonObject();
                reference.addProperty("type", "reference");
                reference.addProperty("id", target);
                providers.add(reference);
                font.add("providers", providers);
                alias = bytes(font);
            } else if (!ns.equals("minecraft") && local.equals("sounds.json")) {
                JsonObject original = JsonParser.parseString(new String(entry.getValue(),
                        java.nio.charset.StandardCharsets.UTF_8)).getAsJsonObject();
                JsonObject sounds = new JsonObject();
                for (var event : original.entrySet()) {
                    String target = merged.runtimeIds().getOrDefault("sound", Map.of())
                            .get(ns + ":" + event.getKey());
                    if (target == null) throw new IOException("Missing sound mapping: " + event.getKey());
                    JsonObject value = new JsonObject();
                    JsonObject reference = new JsonObject();
                    reference.addProperty("name", target);
                    reference.addProperty("type", "event");
                    JsonArray entries = new JsonArray();
                    entries.add(reference);
                    value.add("sounds", entries);
                    if (event.getValue().getAsJsonObject().has("replace"))
                        value.add("replace", event.getValue().getAsJsonObject().get("replace").deepCopy());
                    if (event.getValue().getAsJsonObject().has("subtitle"))
                        value.add("subtitle", event.getValue().getAsJsonObject().get("subtitle").deepCopy());
                    sounds.add(event.getKey(), value);
                }
                alias = bytes(sounds);
            } else if (local.startsWith("equipment/") && local.endsWith(".json")) {
                // Equipment texture IDs are relative to a layer-specific texture directory.
                alias = entry.getValue();
                if (!ns.equals("minecraft"))
                    Files.write(root.resolve(layer + "assets/custom/equipment/" + ns + "/"
                            + local.substring("equipment/".length())), alias);
            } else if (!ns.equals("minecraft") && local.startsWith("blockstates/")) {
                alias = Files.readAllBytes(root.resolve(layer + "assets/custom/blockstates/" + ns + "/"
                        + local.substring("blockstates/".length())));
            } else if (!ns.equals("minecraft") && (local.startsWith("textures/entity/equipment/")
                    || matchers.stream().anyMatch(m -> m.matches(root.getFileSystem().getPath(
                            "assets/" + ns + "/" + local.replaceFirst("\\.mcmeta$", "")))))) {
                alias = entry.getValue();
            }
            if (alias != null) {
                Files.createDirectories(originalPath.getParent());
                Files.write(originalPath, alias);
                aliases++;
                if (!ns.equals("minecraft") && !ns.equals("custom")) namespaces.add(ns);
            }
            if (local.startsWith("atlases/") && local.endsWith(".json")) {
                Path atlasPath = ns.equals("minecraft") ? originalPath
                        : root.resolve(layer + "assets/custom/atlases/" + ns + "/"
                                + local.substring("atlases/".length()));
                JsonObject atlas = JsonParser.parseString(Files.readString(atlasPath)).getAsJsonObject();
                JsonArray before = JsonParser.parseString(new String(entry.getValue(),
                        java.nio.charset.StandardCharsets.UTF_8)).getAsJsonObject().getAsJsonArray("sources");
                JsonArray after = atlas.getAsJsonArray("sources");
                int count = before.size();
                for (int i = 0; i < count; i++) {
                    JsonObject old = before.get(i).getAsJsonObject();
                    JsonObject moved = after.get(i).getAsJsonObject();
                    if (old.has("sprite") && !old.get("sprite").equals(moved.get("sprite"))) {
                        JsonObject copy = moved.deepCopy();
                        copy.add("sprite", old.get("sprite").deepCopy());
                        after.add(copy);
                        sprites++;
                    }
                }
                Files.write(atlasPath, bytes(atlas));
            }
        }
        return new Result(merged, Set.copyOf(namespaces), aliases, sprites,
                Map.copyOf(merged.runtimeIds().getOrDefault("items", Map.of())));
    }

    private static byte[] bytes(JsonElement value) {
        return JSON.toJson(value).getBytes(java.nio.charset.StandardCharsets.UTF_8);
    }

    public static void main(String[] args) throws Exception {
        if (args.length < 2) throw new IllegalArgumentException("Usage: <isolated-tree> <report.json> [keep-glob ...]");
        Path root = OptimizerCli.isolatedRoot(args[0]);
        Result result = prepare(root, Arrays.asList(args).subList(2, args.length));
        Files.writeString(Path.of(args[1]), new GsonBuilder().setPrettyPrinting().create().toJson(result));
        System.out.println("Prepared " + result.aliases + " compatibility files, "
                + result.spriteAliases + " sprite aliases and " + result.itemModels.size() + " client item mappings.");
    }
}
