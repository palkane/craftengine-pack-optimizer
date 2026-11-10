package io.github.palkane.cepackoptimizer;

import com.google.gson.*;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import java.util.regex.Pattern;

/** Works exclusively on CE's assembled, unprotected staging tree. */
public final class PackOptimizer {
    private static final Gson JSON = new GsonBuilder().disableHtmlEscaping().create();
    private static final Set<String> DYNAMIC_TEXTURES = Set.of(
            "entity", "gui", "environment", "colormap", "misc", "mob_effect",
            "painting", "particle", "map", "trims", "effect");

    public record Result(int beforeFiles, int afterFiles, long beforeBytes, long afterBytes,
                         int removedModels, int removedTextures, int removedMetadata,
                         int flattenedOverlays, int deduplicatedModels, List<String> removedPaths) {
        public String summary() {
            return "files " + beforeFiles + " -> " + afterFiles + ", bytes " + beforeBytes
                    + " -> " + afterBytes + ", removed models=" + removedModels
                    + ", textures=" + removedTextures + ", metadata=" + removedMetadata
                    + ", flattened overlays=" + flattenedOverlays + ", shared models=" + deduplicatedModels;
        }
    }

    public static Result optimize(Path root, boolean flatten, List<String> keepGlobs) throws IOException {
        Map<String, byte[]> original = readTree(root);
        Map<String, byte[]> files = new TreeMap<>(original);
        // Parse everything before changing the staging tree. A malformed input aborts publication.
        parseJson(files);
        int overlays = flatten ? flattenExactVersion(files) : 0;
        Map<String, JsonElement> documents = parseJson(files);
        Map<String, String> candidates = new HashMap<>();
        Map<String, Set<String>> aliases = new HashMap<>();
        List<Pattern> keeps = keepGlobs.stream().map(PackOptimizer::glob).toList();
        int sharedModels = shareModels(documents, keeps);
        for (String path : files.keySet()) {
            String[] p = path.split("/", 4);
            if (p.length != 4 || !p[0].equals("assets")) continue;
            boolean model = p[2].equals("models") && p[3].endsWith(".json");
            boolean texture = p[2].equals("textures") && p[3].endsWith(".png");
            if (!model && !texture) continue;
            String id = p[1] + ":" + p[3].substring(0, p[3].lastIndexOf('.'));
            candidates.put(path, id);
            aliases.computeIfAbsent(id, k -> new HashSet<>()).add(path);
            aliases.computeIfAbsent(id + (texture ? ".png" : ".json"), k -> new HashSet<>()).add(path);
        }

        Set<String> live = new HashSet<>();
        Deque<String> queue = new ArrayDeque<>();
        for (String path : files.keySet()) {
            String[] p = path.split("/", 4);
            boolean candidate = candidates.containsKey(path);
            boolean dynamic = candidate && (p[1].equals("minecraft")
                    || p[2].equals("textures") && DYNAMIC_TEXTURES.contains(p[3].split("/")[0]));
            if (!candidate || dynamic || keeps.stream().anyMatch(g -> g.matcher(path).matches())) {
                if (live.add(path)) queue.add(path);
            }
        }
        // Atlas directory/prefix sources and custom shaders can refer to textures by convention.
        // Retain their namespace's textures rather than guessing their runtime lookup semantics.
        for (String path : files.keySet()) {
            String[] p = path.split("/", 4);
            if (p.length == 4 && p[0].equals("assets")
                    && (p[2].equals("atlases") || p[2].equals("shaders"))) {
                Set<String> namespaces = new HashSet<>(Set.of(p[1]));
                if (p[2].equals("atlases") && documents.containsKey(path)) {
                    visit(documents.get(path), v -> {
                        if (v.contains(":")) namespaces.add(v.substring(0, v.indexOf(':')));
                    });
                }
                for (String asset : candidates.keySet()) {
                    String[] a = asset.split("/", 4);
                    if (a[2].equals("textures") && namespaces.contains(a[1]) && live.add(asset)) queue.add(asset);
                }
            }
        }
        while (!queue.isEmpty()) {
            String path = queue.removeFirst();
            JsonElement json = documents.get(path);
            if (json == null) continue;
            String[] p = path.split("/", 4);
            String namespace = p.length == 4 && p[0].equals("assets") ? p[1] : "minecraft";
            visit(json, value -> {
                if (value.startsWith("#")) return;
                mark(value.contains(":") ? value : "minecraft:" + value, aliases, live, queue);
                // Nonstandard third-party files sometimes resolve bare IDs relative to their pack.
                if (!value.contains(":")) mark(namespace + ":" + value, aliases, live, queue);
            });
        }

        int models = 0, textures = 0, metadata = 0;
        for (String path : new TreeSet<>(candidates.keySet())) {
            if (live.contains(path)) continue;
            files.remove(path);
            if (path.endsWith(".json")) models++;
            else {
                textures++;
                if (files.remove(path + ".mcmeta") != null) metadata++;
            }
        }
        // Compact JSON only; leave all image/audio/shader bytes and external IDs unchanged.
        for (var entry : documents.entrySet()) {
            if (files.containsKey(entry.getKey())) {
                files.put(entry.getKey(), JSON.toJson(entry.getValue()).getBytes(StandardCharsets.UTF_8));
            }
        }
        List<String> removed = original.keySet().stream().filter(p -> !files.containsKey(p)).sorted().toList();
        Result result = new Result(original.size(), files.size(), bytes(original), bytes(files),
                models, textures, metadata, overlays, sharedModels, removed);
        for (var entry : files.entrySet()) {
            if (!Arrays.equals(original.get(entry.getKey()), entry.getValue())) {
                Path target = root.resolve(entry.getKey());
                Files.createDirectories(target.getParent());
                Files.write(target, entry.getValue());
            }
        }
        for (String path : removed) Files.delete(root.resolve(path));
        // Empty overlay directories also count as layers to some ZIP exporters.
        try (var paths = Files.walk(root)) {
            for (Path path : paths.sorted(Comparator.reverseOrder()).toList()) {
                if (!path.equals(root) && Files.isDirectory(path)) {
                    try (var children = Files.list(path)) {
                        if (children.findAny().isEmpty()) Files.delete(path);
                    }
                }
            }
        }
        return result;
    }

    private static int shareModels(Map<String, JsonElement> documents, List<Pattern> keeps) {
        Map<String, String> canonical = new HashMap<>();
        Map<String, String> redirect = new HashMap<>();
        for (var entry : documents.entrySet()) {
            String[] p = entry.getKey().split("/", 4);
            if (p.length != 4 || !p[0].equals("assets") || !p[2].equals("models")
                    || p[1].equals("minecraft") || !p[3].endsWith(".json")
                    || keeps.stream().anyMatch(g -> g.matcher(entry.getKey()).matches())) continue;
            String id = p[1] + ":" + p[3].substring(0, p[3].length() - 5);
            String previous = canonical.putIfAbsent(JSON.toJson(sorted(entry.getValue())), id);
            if (previous != null) redirect.put(id, previous);
        }
        // An ID used by an unknown schema or as a texture ID is not safe to rewrite.
        for (JsonElement document : documents.values()) protectOpaqueReferences(document, "", redirect);
        for (JsonElement document : documents.values()) rewriteModels(document, redirect);
        return redirect.size();
    }

    private static JsonElement sorted(JsonElement value) {
        if (value.isJsonObject()) {
            JsonObject result = new JsonObject();
            new TreeMap<>(value.getAsJsonObject().asMap()).forEach((k, v) -> result.add(k, sorted(v)));
            return result;
        }
        if (value.isJsonArray()) {
            JsonArray result = new JsonArray();
            value.getAsJsonArray().forEach(v -> result.add(sorted(v)));
            return result;
        }
        return value;
    }

    private static void protectOpaqueReferences(JsonElement value, String field, Map<String, String> redirects) {
        if (value.isJsonPrimitive() && value.getAsJsonPrimitive().isString()) {
            if (!field.equals("model") && !field.equals("parent")) redirects.remove(value.getAsString());
        } else if (value.isJsonArray()) {
            value.getAsJsonArray().forEach(v -> protectOpaqueReferences(v, "", redirects));
        } else if (value.isJsonObject()) {
            value.getAsJsonObject().entrySet().forEach(e -> {
                redirects.remove(e.getKey());
                protectOpaqueReferences(e.getValue(), e.getKey(), redirects);
            });
        }
    }

    private static void rewriteModels(JsonElement value, Map<String, String> redirects) {
        if (value.isJsonArray()) value.getAsJsonArray().forEach(v -> rewriteModels(v, redirects));
        else if (value.isJsonObject()) {
            for (var entry : value.getAsJsonObject().entrySet()) {
                JsonElement v = entry.getValue();
                if ((entry.getKey().equals("model") || entry.getKey().equals("parent"))
                        && v.isJsonPrimitive() && v.getAsJsonPrimitive().isString()
                        && redirects.containsKey(v.getAsString())) {
                    entry.setValue(new JsonPrimitive(redirects.get(v.getAsString())));
                } else rewriteModels(v, redirects);
            }
        }
    }

    private static void mark(String id, Map<String, Set<String>> aliases, Set<String> live, Deque<String> queue) {
        for (String path : aliases.getOrDefault(id, Set.of())) {
            if (live.add(path)) queue.add(path);
        }
    }

    private static void visit(JsonElement value, java.util.function.Consumer<String> consumer) {
        if (value.isJsonPrimitive() && value.getAsJsonPrimitive().isString()) consumer.accept(value.getAsString());
        else if (value.isJsonArray()) value.getAsJsonArray().forEach(v -> visit(v, consumer));
        else if (value.isJsonObject()) value.getAsJsonObject().entrySet().forEach(e -> {
            consumer.accept(e.getKey());
            visit(e.getValue(), consumer);
        });
    }

    private static Map<String, JsonElement> parseJson(Map<String, byte[]> files) throws IOException {
        Map<String, JsonElement> result = new TreeMap<>();
        for (var entry : files.entrySet()) {
            if (!entry.getKey().endsWith(".json") && !entry.getKey().endsWith(".mcmeta")) continue;
            try {
                result.put(entry.getKey(), JsonParser.parseString(new String(entry.getValue(), StandardCharsets.UTF_8)));
            } catch (RuntimeException ex) {
                throw new IOException("Invalid JSON: " + entry.getKey(), ex);
            }
        }
        return result;
    }

    private static int flattenExactVersion(Map<String, byte[]> files) throws IOException {
        byte[] metadata = files.get("pack.mcmeta");
        if (metadata == null) throw new IOException("Missing pack.mcmeta");
        JsonObject meta = JsonParser.parseString(new String(metadata, StandardCharsets.UTF_8)).getAsJsonObject();
        JsonObject pack = meta.getAsJsonObject("pack");
        // Do not collapse a multi-version pack, or old metadata whose range we cannot prove.
        if (pack == null || !pack.has("min_format") || !pack.has("max_format")
                || !pack.get("min_format").equals(pack.get("max_format")) || !meta.has("overlays")) return 0;
        int[] target = version(pack.get("min_format"));
        JsonArray entries = meta.getAsJsonObject("overlays").getAsJsonArray("entries");
        if (entries == null) throw new IOException("Missing overlays.entries");
        Set<String> directories = new LinkedHashSet<>();
        List<String> active = new ArrayList<>();
        for (JsonElement entry : entries) {
            JsonObject overlay = entry.getAsJsonObject();
            String directory = overlay.get("directory").getAsString();
            if (!directory.matches("[a-zA-Z0-9_.-]+") || directory.equals(".") || directory.equals("..")
                    || directory.equals("assets")) throw new IOException("Invalid overlay directory");
            // Legacy overlay ranges remain unchanged; never silently discard an unknown format.
            if (!overlay.has("min_format") || !overlay.has("max_format")) return 0;
            directories.add(directory);
            if (compare(target, version(overlay.get("min_format"))) >= 0
                    && compare(target, version(overlay.get("max_format"))) <= 0) active.add(directory);
        }
        // Later entries override earlier ones, matching the client's overlay priority.
        for (String directory : active) {
            String prefix = directory + "/";
            for (var entry : new TreeMap<>(files).entrySet()) {
                if (entry.getKey().startsWith(prefix + "assets/")) {
                    files.put(entry.getKey().substring(prefix.length()), entry.getValue());
                }
            }
        }
        files.keySet().removeIf(path -> directories.stream().anyMatch(d -> path.startsWith(d + "/")));
        meta.remove("overlays");
        files.put("pack.mcmeta", JSON.toJson(meta).getBytes(StandardCharsets.UTF_8));
        return entries.size();
    }

    private static int[] version(JsonElement value) throws IOException {
        try {
            if (value.isJsonArray() && value.getAsJsonArray().size() == 2)
                return new int[]{value.getAsJsonArray().get(0).getAsInt(), value.getAsJsonArray().get(1).getAsInt()};
            if (value.isJsonPrimitive()) return new int[]{value.getAsInt(), 0};
        } catch (RuntimeException ex) {
            throw new IOException("Invalid resource pack format", ex);
        }
        throw new IOException("Unsupported resource pack format: " + value);
    }

    private static int compare(int[] a, int[] b) {
        int c = Integer.compare(a[0], b[0]);
        return c == 0 ? Integer.compare(a[1], b[1]) : c;
    }

    private static Pattern glob(String glob) {
        StringBuilder regex = new StringBuilder("^");
        for (int i = 0; i < glob.length(); i++) {
            char c = glob.charAt(i);
            if (c == '*') {
                if (i + 1 < glob.length() && glob.charAt(i + 1) == '*') { regex.append(".*"); i++; }
                else regex.append("[^/]*");
            } else regex.append(Pattern.quote(String.valueOf(c)));
        }
        return Pattern.compile(regex.append('$').toString());
    }

    private static Map<String, byte[]> readTree(Path root) throws IOException {
        Map<String, byte[]> files = new TreeMap<>();
        try (var paths = Files.walk(root)) {
            for (Path path : paths.filter(Files::isRegularFile).toList()) {
                files.put(root.relativize(path).toString().replace('\\', '/'), Files.readAllBytes(path));
            }
        }
        return files;
    }

    private static long bytes(Map<String, byte[]> files) {
        return files.values().stream().mapToLong(b -> b.length).sum();
    }
}
