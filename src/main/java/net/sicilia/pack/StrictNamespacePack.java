package net.sicilia.pack;

import com.google.gson.*;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import java.util.zip.*;

/** Runs after CE protection so the obfuscator cannot add another namespace afterwards. */
public final class StrictNamespacePack {
    public record Result(NamespaceMerger.Result merge, int directorySprites, String sourceSha1,
                         String outputSha1) {}

    static void extract(Path source, Path root) throws IOException {
        Files.createDirectories(root);
        try (ZipFile zip = new ZipFile(source.toFile())) {
            var entries = zip.entries();
            Set<String> seen = new HashSet<>();
            while (entries.hasMoreElements()) {
                ZipEntry entry = entries.nextElement();
                // CE uses nonempty slash-terminated entries as file protection.
                if (entry.isDirectory() && entry.getSize() == 0) continue;
                String name = entry.getName().replaceFirst("/+$", "");
                Path target = root.resolve(name).normalize();
                if (!target.startsWith(root) || !seen.add(target.toString()))
                    throw new IOException("Unsafe or duplicate protected ZIP entry: " + name);
                Files.createDirectories(target.getParent());
                try (var input = zip.getInputStream(entry)) { Files.copy(input, target); }
            }
        }
    }

    public static Result build(Path source, Path output, Path work) throws Exception {
        if (Files.exists(work)) throw new IOException("Use a new isolated work directory");
        Path root = work.toAbsolutePath().normalize();
        extract(source, root);
        int sprites = expandDirectories(root);
        var merge = NamespaceMerger.optimize(root);
        if (!merge.namespacesAfter().equals(Set.of("minecraft", "custom")))
            throw new IOException("Final pack must contain exactly minecraft and custom");
        Path temporary = output.resolveSibling(output.getFileName() + ".tmp");
        Files.createDirectories(output.toAbsolutePath().getParent());
        try (var zip = new ZipOutputStream(Files.newOutputStream(temporary));
             var paths = Files.walk(root)) {
            for (Path file : paths.filter(Files::isRegularFile).sorted().toList()) {
                ZipEntry entry = new ZipEntry(root.relativize(file).toString().replace('\\', '/'));
                entry.setTime(0);
                zip.putNextEntry(entry);
                Files.copy(file, zip);
                zip.closeEntry();
            }
        }
        Files.move(temporary, output, StandardCopyOption.REPLACE_EXISTING);
        return new Result(merge, sprites, NamespaceShards.sha1(Files.readAllBytes(source)),
                NamespaceShards.sha1(Files.readAllBytes(output)));
    }

    /** Directory sources are paths, not resource IDs. Materialize their original sprite set. */
    static int expandDirectories(Path root) throws IOException {
        return rewriteDirectories(root, null);
    }

    static int rewriteDirectories(Path root, Map<String, String> prefixSprites) throws IOException {
        List<Path> files;
        try (var paths = Files.walk(root)) { files = paths.filter(Files::isRegularFile).toList(); }
        int added = 0;
        for (Path file : files) {
            String relative = root.relativize(file).toString().replace('\\', '/');
            if (!relative.contains("/atlases/") || !relative.endsWith(".json")) continue;
            String atlasLayer = relative.substring(0, relative.indexOf("assets/"));
            JsonObject atlas = JsonParser.parseString(Files.readString(file)).getAsJsonObject();
            if (!atlas.has("sources")) continue;
            JsonArray sources = atlas.getAsJsonArray("sources"), expanded = new JsonArray();
            JsonArray inputs = new JsonArray();
            // This source lives in the vanilla pack, not in the CE atlas JSON.
            if (relative.endsWith("assets/minecraft/atlases/items.json")
                    && sources.asList().stream().map(JsonElement::getAsJsonObject).noneMatch(e ->
                    e.has("source") && e.get("source").getAsString().equals("item"))) {
                JsonObject implicit = new JsonObject();
                implicit.addProperty("type", "directory");
                implicit.addProperty("source", "item");
                implicit.addProperty("prefix", "item/");
                inputs.add(implicit);
            }
            inputs.addAll(sources.deepCopy());
            for (JsonElement value : inputs) {
                expanded.add(value);
                JsonObject entry = value.getAsJsonObject();
                String type = entry.get("type").getAsString();
                if (!type.equals("directory") && !type.equals("minecraft:directory")) continue;
                String prefix = entry.get("prefix").getAsString();
                String source = entry.get("source").getAsString().replaceFirst("/+$", "") + "/";
                Set<String> directories = new HashSet<>();
                for (Path texture : files) {
                    String name = root.relativize(texture).toString().replace('\\', '/');
                    int assets = name.startsWith("assets/") ? 0 : name.indexOf("/assets/") + 1;
                    if (assets < 0 || !name.substring(assets).startsWith("assets/")) continue;
                    String textureLayer = name.substring(0, assets);
                    if (!textureLayer.isEmpty() && !textureLayer.equals(atlasLayer)) continue;
                    String[] parts = name.substring(assets).split("/", 4);
                    if (parts.length != 4 || parts[1].equals("minecraft")
                            || !parts[2].equals("textures") || !parts[3].startsWith(source)
                            || !parts[3].endsWith(".png")) continue;
                    String resource = parts[1] + ":" + parts[3].substring(0, parts[3].length() - 4);
                    String sprite = parts[1] + ":" + prefix
                            + parts[3].substring(source.length(), parts[3].length() - 4);
                    if (prefixSprites != null) {
                        prefixSprites.put(sprite, "minecraft:" + sprite.replace(':', '/'));
                        if (directories.add(parts[1])) {
                            JsonObject directory = new JsonObject();
                            directory.addProperty("type", "directory");
                            directory.addProperty("source", parts[1] + "/" + source.substring(0, source.length() - 1));
                            directory.addProperty("prefix", parts[1] + "/" + prefix);
                            expanded.add(directory);
                        }
                        added++;
                        continue;
                    }
                    JsonObject single = new JsonObject();
                    single.addProperty("type", "single");
                    single.addProperty("resource", resource);
                    single.addProperty("sprite", sprite);
                    expanded.add(single);
                    added++;
                }
            }
            // MaterialBaker prefers the item atlas even for world block geometry.
            // CE's item previews can register vanilla block sprites there too.
            if (relative.endsWith("assets/minecraft/atlases/items.json")) {
                JsonObject filter = new JsonObject(), pattern = new JsonObject();
                filter.addProperty("type", "filter");
                pattern.addProperty("namespace", "^minecraft$");
                pattern.addProperty("path", "^block/.*");
                filter.add("pattern", pattern);
                expanded.add(filter);
            }
            if (!expanded.equals(sources)) {
                atlas.add("sources", expanded);
                Files.writeString(file, new Gson().toJson(atlas), StandardCharsets.UTF_8);
            }
        }
        return added;
    }

    public static void main(String[] args) throws Exception {
        if (args.length != 4) throw new IllegalArgumentException("Usage: source.zip output.zip work-dir report.json");
        Result result = build(Path.of(args[0]), Path.of(args[1]), Path.of(args[2]));
        Files.writeString(Path.of(args[3]), new GsonBuilder().setPrettyPrinting().create().toJson(result));
        System.out.println("Strict pack: " + result.merge().namespacesAfter()
                + "; directory sprites=" + result.directorySprites() + "; SHA1=" + result.outputSha1());
    }
}
