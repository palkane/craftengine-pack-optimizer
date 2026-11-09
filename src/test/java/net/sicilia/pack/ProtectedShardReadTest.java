package net.sicilia.pack;

import com.google.gson.Gson;
import com.google.gson.JsonParser;
import java.nio.file.*;
import java.util.*;
import java.util.zip.*;

/** Exercises the JDK ZipFile lookup/stream primitives used by vanilla, without a player/client. */
public final class ProtectedShardReadTest {
    public static void main(String[] args) throws Exception {
        Path state = Path.of(args[1]);
        Path manifestFile = state.resolve("current.json");
        if (!Files.exists(manifestFile)) manifestFile = state.resolve("candidate.json");
        Path objects = Files.isDirectory(state.resolve("objects")) ? state.resolve("objects") : state;
        var manifest = new Gson().fromJson(Files.readString(manifestFile), NamespaceShards.Manifest.class);
        int checks = 0;
        Set<String> names = new HashSet<>();
        try (ZipFile original = new ZipFile(args[0])) {
            for (var shard : manifest.shards()) {
                try (ZipFile zip = new ZipFile(objects.resolve(shard.file()).toFile())) {
                    if (zip.getEntry("pack.mcmeta") == null) throw new AssertionError("Root lookup failed");
                    checks++;
                    for (var entry : zip.stream().toList()) {
                        var old = original.getEntry(entry.getName());
                        if (old == null) throw new AssertionError("Resource invented: " + entry.getName());
                        byte[] before = original.getInputStream(old).readAllBytes();
                        byte[] after = zip.getInputStream(entry).readAllBytes();
                        if (entry.getName().replaceAll("/+$", "").equals("pack.mcmeta")) {
                            var oldMetadata = JsonParser.parseString(new String(before,
                                    java.nio.charset.StandardCharsets.UTF_8)).getAsJsonObject();
                            var newMetadata = JsonParser.parseString(new String(after,
                                    java.nio.charset.StandardCharsets.UTF_8)).getAsJsonObject();
                            var overlays = oldMetadata.remove("overlays");
                            var shardOverlays = newMetadata.remove("overlays");
                            if (!oldMetadata.equals(newMetadata))
                                throw new AssertionError("Unrelated metadata changed");
                            var expected = new com.google.gson.JsonArray();
                            if (overlays != null)
                                for (var overlay : overlays.getAsJsonObject().getAsJsonArray("entries")) {
                                    String prefix = overlay.getAsJsonObject().get("directory").getAsString() + "/";
                                    if (zip.stream().anyMatch(e -> e.getName().startsWith(prefix))) expected.add(overlay);
                                }
                            if (expected.isEmpty() ? shardOverlays != null : shardOverlays == null
                                    || !expected.equals(shardOverlays.getAsJsonObject().getAsJsonArray("entries")))
                                throw new AssertionError("Overlay priority/content changed");
                        } else if (!Arrays.equals(before, after))
                            throw new AssertionError("JDK ZipFile payload differs: " + entry.getName());
                        checks++;
                        names.add(entry.getName());
                    }
                }
            }
            for (var entry : original.stream().toList())
                if (!(entry.isDirectory() && entry.getSize() == 0) && !names.contains(entry.getName()))
                    throw new AssertionError("JDK resource lost: " + entry.getName());
        }
        System.out.println("PASS " + checks + " protected JDK ZipFile reads, failures 0");
    }
}
