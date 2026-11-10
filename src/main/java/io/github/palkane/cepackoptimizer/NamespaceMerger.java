package io.github.palkane.cepackoptimizer;

import com.google.gson.*;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import java.util.regex.Pattern;

/** Offline candidate builder. Runtime resource IDs must be migrated before publication. */
public final class NamespaceMerger {
    private static final Gson JSON = new GsonBuilder().disableHtmlEscaping().create();
    private static final Pattern ID = Pattern.compile("[a-z0-9_.-]+:[a-z0-9/._-]+");
    private static final Pattern IMPORT = Pattern.compile(
            "(#(?:moj_import|include)\\s+[<\"])([a-z0-9_.-]+:[a-z0-9/._-]+)([>\"])");
    private static final Set<String> LITERAL_FIELDS = Set.of(
            "type", "property", "component", "text", "translate", "description", "subtitle");

    public record Result(int files, int moved, int rewritten, Set<String> namespacesBefore,
                         Set<String> namespacesAfter, Map<String, Map<String, String>> runtimeIds) {}
    private record Asset(String layer, String namespace, String path) {}

    private static Asset asset(String path, Set<String> layers) {
        for (String layer : layers) {
            String prefix = layer + "assets/";
            if (!path.startsWith(prefix)) continue;
            String rest = path.substring(prefix.length());
            int slash = rest.indexOf('/');
            if (slash < 1) return null;
            String namespace = rest.substring(0, slash);
            if (!namespace.matches("[a-z0-9_.-]+"))
                throw new IllegalArgumentException("Invalid namespace: " + namespace);
            return new Asset(layer, namespace, rest.substring(slash + 1));
        }
        return null;
    }

    private static String remapId(String value, Set<String> namespaces, String targetNamespace) {
        if (!ID.matcher(value).matches()) return value;
        int colon = value.indexOf(':');
        String namespace = value.substring(0, colon);
        if (namespace.equals("minecraft") || !namespaces.contains(namespace)) return value;
        return targetNamespace + ":" + namespace + "/" + value.substring(colon + 1);
    }

    private static JsonElement rewrite(JsonElement value, String field, Set<String> namespaces, String targetNamespace) {
        if (value.isJsonObject()) {
            JsonObject result = new JsonObject();
            for (var entry : value.getAsJsonObject().entrySet())
                result.add(entry.getKey(), rewrite(entry.getValue(), entry.getKey(), namespaces, targetNamespace));
            return result;
        }
        if (value.isJsonArray()) {
            JsonArray result = new JsonArray();
            for (JsonElement child : value.getAsJsonArray()) result.add(rewrite(child, field, namespaces, targetNamespace));
            return result;
        }
        if (value.isJsonPrimitive() && value.getAsJsonPrimitive().isString()
                && !LITERAL_FIELDS.contains(field))
            return new JsonPrimitive(remapId(value.getAsString(), namespaces, targetNamespace));
        return value.deepCopy();
    }

    private static String destination(Asset asset, String targetNamespace) {
        String base = asset.layer + "assets/";
        if (asset.namespace.equals("minecraft")) return base + "minecraft/" + asset.path;
        String target = base + targetNamespace + "/";
        if (asset.path.startsWith("textures/entity/equipment/")) {
            String rest = asset.path.substring("textures/entity/equipment/".length());
            int slash = rest.indexOf('/');
            if (slash > 0) return target + "textures/entity/equipment/"
                    + rest.substring(0, slash + 1) + asset.namespace + "/" + rest.substring(slash + 1);
        }
        if (asset.path.equals("sounds.json")) return target + "sounds.json";
        if (asset.path.startsWith("lang/")) return target + asset.path;
        if (asset.path.startsWith("shaders/include/"))
            return target + "shaders/include/" + asset.namespace + "/"
                    + asset.path.substring("shaders/include/".length());
        int slash = asset.path.indexOf('/');
        if (slash < 0) return target + asset.namespace + "/" + asset.path;
        return target + asset.path.substring(0, slash + 1)
                + asset.namespace + "/" + asset.path.substring(slash + 1);
    }

    private static void mergeJson(Map<String, byte[]> plan, String target, JsonObject value) {
        JsonObject combined = plan.containsKey(target)
                ? JsonParser.parseString(new String(plan.get(target), StandardCharsets.UTF_8)).getAsJsonObject()
                : new JsonObject();
        for (var entry : value.entrySet()) {
            if (combined.has(entry.getKey()) && !combined.get(entry.getKey()).equals(entry.getValue()))
                throw new IllegalArgumentException("Conflicting merged JSON: " + target + "/" + entry.getKey());
            combined.add(entry.getKey(), entry.getValue());
        }
        plan.put(target, JSON.toJson(combined).getBytes(StandardCharsets.UTF_8));
    }

    private static void runtime(Map<String, Map<String, String>> mappings,
                                String type, String oldId, String newId) {
        if (oldId.equals(newId)) return;
        String previous = mappings.computeIfAbsent(type, ignored -> new TreeMap<>()).put(oldId, newId);
        if (previous != null && !previous.equals(newId))
            throw new IllegalArgumentException("Inconsistent runtime ID: " + oldId);
    }

    public static Result optimize(Path root) throws IOException {
        return optimize(root, "custom");
    }

    public static Result optimize(Path root, String targetNamespace) throws IOException {
        if (!Set.of("minecraft", "custom").contains(targetNamespace))
            throw new IllegalArgumentException("Unsupported target namespace");
        Path metadataFile = root.resolve("pack.mcmeta");
        JsonObject metadata = JsonParser.parseString(Files.readString(metadataFile)).getAsJsonObject();
        if (metadata.has("filter"))
            throw new IllegalArgumentException("Resource filters need explicit migration; candidate refused");
        Set<String> layers = new LinkedHashSet<>();
        layers.add("");
        if (metadata.has("overlays")) {
            for (JsonElement entry : metadata.getAsJsonObject("overlays").getAsJsonArray("entries")) {
                String layer = entry.getAsJsonObject().get("directory").getAsString();
                if (!layer.matches("[a-zA-Z0-9_.-]+") || layer.equals(".") || layer.equals(".."))
                    throw new IllegalArgumentException("Invalid overlay directory: " + layer);
                layers.add(layer + "/");
            }
        }
        // Preserve inactive resource layers without adding them to pack.mcmeta.
        try (var children = Files.list(root)) {
            for (Path child : children.toList())
                if (Files.isDirectory(child.resolve("assets")))
                    layers.add(child.getFileName().toString() + "/");
        }
        Map<String, byte[]> source = new TreeMap<>();
        Set<String> namespaces = new TreeSet<>();
        try (var paths = Files.walk(root)) {
            for (Path path : paths.sorted().toList()) {
                if (Files.isSymbolicLink(path)) throw new IllegalArgumentException("Symlink in pack: " + path);
                if (!Files.isRegularFile(path)) continue;
                String relative = root.relativize(path).toString().replace('\\', '/');
                source.put(relative, Files.readAllBytes(path));
                Asset asset = asset(relative, layers);
                if (asset != null) namespaces.add(asset.namespace);
            }
        }
        if (namespaces.stream().allMatch(n -> n.equals("minecraft") || n.equals(targetNamespace)))
            return new Result(source.size(), 0, 0, namespaces, namespaces, Map.of());
        Map<String, byte[]> plan = new TreeMap<>();
        Map<String, Map<String, String>> runtime = new TreeMap<>();
        int moved = 0, rewritten = 0;
        for (var entry : source.entrySet()) {
            String path = entry.getKey();
            byte[] bytes = entry.getValue();
            Asset asset = asset(path, layers);
            if (asset == null) {
                if (path.contains("/assets/") || path.startsWith("assets/"))
                    throw new IllegalArgumentException("Undeclared asset layer: " + path);
                plan.put(path, bytes);
                continue;
            }
            String target = destination(asset, targetNamespace);
            if (!path.equals(target)) moved++;
            if (path.endsWith(".json") || path.endsWith(".mcmeta")) {
                JsonElement original = JsonParser.parseString(new String(bytes, StandardCharsets.UTF_8));
                if (asset.path.startsWith("atlases/") && original.isJsonObject()) {
                    JsonArray sources = original.getAsJsonObject().getAsJsonArray("sources");
                    if (sources != null) for (JsonElement value : sources) {
                        JsonObject atlasSource = value.getAsJsonObject();
                        if (atlasSource.has("sprite")) {
                            String sprite = atlasSource.get("sprite").getAsString();
                            runtime(runtime, "sprites", sprite, remapId(sprite, namespaces, targetNamespace));
                        } else if (atlasSource.has("resource") && atlasSource.get("type").getAsString()
                                .replace("minecraft:", "").equals("single")) {
                            String sprite = atlasSource.get("resource").getAsString();
                            runtime(runtime, "sprites", sprite, remapId(sprite, namespaces, targetNamespace));
                        }
                        if (atlasSource.has("regions")) for (JsonElement region : atlasSource.getAsJsonArray("regions")) {
                            String sprite = region.getAsJsonObject().get("sprite").getAsString();
                            runtime(runtime, "sprites", sprite, remapId(sprite, namespaces, targetNamespace));
                        }
                    }
                }
                JsonElement updated = rewrite(original, "", namespaces, targetNamespace);
                if (!original.equals(updated)) {
                    bytes = JSON.toJson(updated).getBytes(StandardCharsets.UTF_8);
                    rewritten++;
                }
                if (asset.path.equals("sounds.json")) {
                    JsonObject sounds = new JsonObject();
                    for (var sound : updated.getAsJsonObject().entrySet()) {
                        String key = asset.namespace.equals("minecraft") ? sound.getKey()
                                : asset.namespace + "/" + sound.getKey();
                        if (targetNamespace.equals("minecraft") && plan.containsKey(target)
                                && JsonParser.parseString(new String(plan.get(target), StandardCharsets.UTF_8))
                                .getAsJsonObject().has(key))
                            throw new IllegalArgumentException("Sound event collision: " + target + "/" + key);
                        sounds.add(key, sound.getValue());
                        runtime(runtime, "sound", asset.namespace + ":" + sound.getKey(),
                                (asset.namespace.equals("minecraft") ? "minecraft" : targetNamespace) + ":" + key);
                    }
                    mergeJson(plan, target, sounds);
                    continue;
                }
                if (asset.path.startsWith("lang/")) {
                    mergeJson(plan, target, updated.getAsJsonObject());
                    continue;
                }
            } else if (path.endsWith(".glsl") || path.endsWith(".vsh") || path.endsWith(".fsh")) {
                String original = new String(bytes, StandardCharsets.UTF_8);
                String updated = IMPORT.matcher(original).replaceAll(match ->
                        match.group(1) + remapId(match.group(2), namespaces, targetNamespace) + match.group(3));
                if (!original.equals(updated)) {
                    bytes = updated.getBytes(StandardCharsets.UTF_8);
                    rewritten++;
                }
            }
            if (plan.putIfAbsent(target, bytes) != null)
                throw new IllegalArgumentException("Resource collision: " + target);
            int slash = asset.path.indexOf('/');
            if (slash > 0 && !asset.namespace.equals("minecraft")) {
                String type = asset.path.substring(0, slash);
                String idPath = asset.path.substring(slash + 1);
                if (idPath.endsWith(".json")) idPath = idPath.substring(0, idPath.length() - 5);
                else if (type.equals("textures") && idPath.endsWith(".png"))
                    idPath = idPath.substring(0, idPath.length() - 4);
                runtime(runtime, type, asset.namespace + ":" + idPath,
                        targetNamespace + ":" + asset.namespace + "/" + idPath);
            }
        }
        // Finish all parsing/collision checks before mutating the isolated staging tree.
        for (var entry : plan.entrySet()) {
            Path target = root.resolve(entry.getKey());
            Files.createDirectories(target.getParent());
            Files.write(target, entry.getValue());
        }
        for (String path : source.keySet()) if (!plan.containsKey(path)) Files.delete(root.resolve(path));
        try (var paths = Files.walk(root)) {
            for (Path path : paths.sorted(Comparator.reverseOrder()).toList()) {
                if (path.equals(root) || !Files.isDirectory(path)) continue;
                try (var children = Files.list(path)) {
                    if (children.findAny().isEmpty()) Files.delete(path);
                }
            }
        }
        Set<String> after = new TreeSet<>();
        for (String path : plan.keySet()) {
            Asset asset = asset(path, layers);
            if (asset != null) after.add(asset.namespace);
        }
        if (!after.stream().allMatch(n -> n.equals("minecraft") || n.equals(targetNamespace)))
            throw new AssertionError("Candidate still has old namespaces");
        return new Result(plan.size(), moved, rewritten, namespaces, after, runtime);
    }

    public static void main(String[] args) throws Exception {
        if (args.length != 2) throw new IllegalArgumentException("Usage: <isolated-pack-tree> <report.json>");
        Path root = OptimizerCli.isolatedRoot(args[0]);
        Result result = optimize(root);
        Files.writeString(Path.of(args[1]), new GsonBuilder().setPrettyPrinting().create().toJson(result));
        System.out.println("Offline candidate: " + result.namespacesBefore.size() + " -> "
                + result.namespacesAfter.size() + " namespaces; " + result.moved + " moved, "
                + result.rewritten + " rewritten; runtime resource IDs are NOT installed.");
    }
}
