package io.github.palkane.cepackoptimizer;

import com.google.gson.JsonParser;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import java.util.zip.*;

public final class NamespaceShardsTest {
    private static int checks;
    private static void check(boolean result, String message) {
        checks++;
        if (!result) throw new AssertionError(message);
    }

    public static void main(String[] args) throws Exception {
        Path work = Files.createTempDirectory("ce-shards-test-");
        Path source = work.resolve("source.zip");
        Map<String, byte[]> original = new LinkedHashMap<>();
        original.put("pack.mcmeta", """
                {"pack":{"min_format":[97,1],"max_format":[97,1]},
                 "overlays":{"entries":[{"directory":"patch","min_format":[97,1],"max_format":[97,1]}]}}
                """.getBytes(StandardCharsets.UTF_8));
        original.put("assets/alpha/items/a.json", "{\"model\":\"beta:b\"}".getBytes(StandardCharsets.UTF_8));
        original.put("assets/beta/models/b.json", "{\"textures\":{\"all\":\"gamma:c\"}}".getBytes(StandardCharsets.UTF_8));
        original.put("assets/gamma/textures/c.png", new byte[]{1, 2, 3});
        original.put("assets/sound/sounds.json/", "{\"bell\":{\"sounds\":[\"alpha:bell\"]}}".getBytes(StandardCharsets.UTF_8));
        original.put("patch/assets/alpha/items/a.json", "{\"model\":\"beta:patch\"}".getBytes(StandardCharsets.UTF_8));
        try (ZipOutputStream out = new ZipOutputStream(Files.newOutputStream(source))) {
            for (var entry : original.entrySet()) {
                out.putNextEntry(new ZipEntry(entry.getKey()));
                out.write(entry.getValue());
                out.closeEntry();
            }
            out.putNextEntry(new ZipEntry("assets/empty/"));
            out.closeEntry();
        }
        Path state = work.resolve("state");
        var manifest = NamespaceShards.split(source, state, 8);
        check(manifest.shards().size() == 4, "namespaces not separated");
        Map<String, Integer> owners = new HashMap<>();
        Map<String, byte[]> union = new HashMap<>();
        int index = 0;
        for (var shard : manifest.shards()) {
            byte[] bytes = Files.readAllBytes(state.resolve(shard.file()));
            check(NamespaceShards.sha1(bytes).equals(shard.sha1()), "incorrect download hash");
            for (String namespace : shard.namespaces()) {
                check(owners.put(namespace, index) == null, "namespace split across packs");
            }
            try (ZipFile zip = new ZipFile(state.resolve(shard.file()).toFile())) {
                check(zip.getEntry("pack.mcmeta") != null, "standalone pack metadata missing");
                check(zip.getEntry("assets/empty/") == null, "empty namespace retained");
                for (var entry : zip.stream().toList()) {
                    byte[] value = zip.getInputStream(entry).readAllBytes();
                    if (entry.getName().equals("pack.mcmeta")) {
                        var metadata = JsonParser.parseString(new String(value, StandardCharsets.UTF_8))
                                .getAsJsonObject();
                        check(metadata.has("overlays") == shard.namespaces().contains("alpha"),
                                "empty overlay still scans unrelated shard");
                        var before = JsonParser.parseString(new String(original.get("pack.mcmeta"),
                                StandardCharsets.UTF_8)).getAsJsonObject();
                        check(metadata.get("pack").equals(before.get("pack")), "pack metadata changed");
                    } else {
                        check(Arrays.equals(original.get(entry.getName()), value), "resource bytes changed");
                    }
                    union.put(entry.getName(), value);
                }
                if (shard.namespaces().contains("alpha"))
                    check(zip.getEntry("patch/assets/alpha/items/a.json") != null, "overlay priority split");
                if (shard.namespaces().contains("sound"))
                    check(zip.getInputStream(zip.getEntry("assets/sound/sounds.json")).readAllBytes().length > 0,
                            "nonempty protected sound entry removed");
            }
            index++;
        }
        check(union.keySet().equals(original.keySet()), "resources lost or invented");
        check(NamespaceShards.split(source, state, 8).equals(manifest), "same input changes download identities");
        check(manifest.scanCost().optimizedShards() < manifest.scanCost().previousShards(),
                "empty overlays did not reduce scanning");

        Path grouped = work.resolve("grouped.zip");
        int[] counts = {100, 80, 50, 20, 10, 4, 2, 1, 1, 1, 1, 1, 1, 1, 1, 1};
        int[] oldLoads = new int[3], oldNamespaces = new int[3];
        try (ZipOutputStream out = new ZipOutputStream(Files.newOutputStream(grouped))) {
            out.putNextEntry(new ZipEntry("pack.mcmeta"));
            out.write("{\"pack\":{\"pack_format\":97}}".getBytes(StandardCharsets.UTF_8));
            out.closeEntry();
            for (int n = 0; n < counts.length; n++) {
                int target = 0;
                for (int i = 1; i < 3; i++) if (oldLoads[i] < oldLoads[target]) target = i;
                oldLoads[target] += counts[n];
                oldNamespaces[target]++;
                for (int f = 0; f < counts[n]; f++) {
                    out.putNextEntry(new ZipEntry("assets/n" + n + "/models/f" + f + ".json"));
                    out.write("{}".getBytes(StandardCharsets.UTF_8));
                    out.closeEntry();
                }
            }
        }
        long previousVisits = 0;
        for (int i = 0; i < 3; i++) previousVisits += (long)oldNamespaces[i] * (oldLoads[i] + 1);
        Path optimized = work.resolve("grouped-result");
        var groupedManifest = NamespaceShards.split(grouped, optimized, 3);
        long actualVisits = 0;
        Set<String> groupedUnion = new HashSet<>();
        for (var shard : groupedManifest.shards()) {
            try (ZipFile zip = new ZipFile(optimized.resolve(shard.file()).toFile())) {
                actualVisits += (long)shard.namespaces().size() * zip.size();
                zip.stream().filter(e -> e.getName().startsWith("assets/"))
                        .forEach(e -> check(groupedUnion.add(e.getName()), "grouped resource duplicated"));
            }
        }
        check(groupedManifest.shards().size() == 3, "pack count changed");
        check(actualVisits < previousVisits, "file-count balancing still repeats expensive scans");
        check(actualVisits == groupedManifest.scanCost().optimizedShards(), "scan report is incorrect");
        check(groupedUnion.size() == Arrays.stream(counts).sum(), "grouped resources lost");
        check(NamespaceShards.split(grouped, optimized, 3).equals(groupedManifest), "grouping is unstable");

        Path publication = work.resolve("published");
        Files.createDirectories(publication);
        Files.writeString(publication.resolve("current.json"), "previous group");
        int[] sent = {0};
        try {
            NamespaceShardHost.publishGroup(source, publication, 8, (file, bytes, hash) -> {
                if (++sent[0] == 2) throw new java.io.IOException("simulated network failure");
            });
            throw new AssertionError("upload failure accepted");
        } catch (java.io.IOException expected) {
            check(Files.readString(publication.resolve("current.json")).equals("previous group"),
                    "partial upload published a broken group");
        }
        sent[0] = 0;
        NamespaceShardHost.publishGroup(source, publication, 8, (file, bytes, hash) -> {
            check(Files.readString(publication.resolve("current.json")).equals("previous group"),
                    "manifest published before all uploads");
            sent[0]++;
        });
        check(sent[0] == 4 && Files.readString(publication.resolve("current.json")).contains(manifest.sourceSha1()),
                "complete group not published");
        try (var paths = Files.walk(work)) {
            for (Path path : paths.sorted(Comparator.reverseOrder()).toList()) Files.delete(path);
        }
        System.out.println("PASS " + checks + " namespace-shard behavior checks, failures 0");
    }
}
