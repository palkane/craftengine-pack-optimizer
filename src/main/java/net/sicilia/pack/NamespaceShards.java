package net.sicilia.pack;

import com.google.gson.*;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.security.MessageDigest;
import java.util.*;
import java.util.regex.*;
import java.util.zip.ZipFile;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

/** Copies raw CE ZIP records: fake directories, CRC protection and compressed bytes stay intact. */
public final class NamespaceShards {
    private static final Pattern ASSET = Pattern.compile("^(?:[^/]+/)?assets/([^/]+)/");
    public record Shard(String file, String sha1, int entries, List<String> namespaces) {}
    /** Entry visits for one listing pass, conservatively counting every declared overlay as active. */
    public record ScanCost(long singlePack, long previousShards, long optimizedShards) {}
    public record Manifest(String sourceSha1, List<Shard> shards, ScanCost scanCost,
                           Map<String, String> itemModels) {
        Manifest(String sourceSha1, List<Shard> shards, ScanCost scanCost) {
            this(sourceSha1, shards, scanCost, Map.of());
        }
    }
    private record Entry(String name, String namespace, byte[] central, int start, int end, byte[] local) {}

    public static Manifest split(Path source, Path output, int count) throws Exception {
        return split(source, output, count, null);
    }

    public static Manifest splitCompatibility(Path source, Path output, Set<String> compatibilityNamespaces)
            throws Exception {
        return split(source, output, 2, compatibilityNamespaces);
    }

    private static Manifest split(Path source, Path output, int count, Set<String> compatibilityNamespaces)
            throws Exception {
        if (count < 2 || count > 32) throw new IOException("Shard count must be 2..32");
        byte[] data = Files.readAllBytes(source);
        List<Entry> entries = entries(data);
        JsonObject metadataJson;
        String metadataName;
        // A filter repeated across independent packs could hide a sibling's resources.
        try (ZipFile zip = new ZipFile(source.toFile())) {
            var metadata = zip.getEntry("pack.mcmeta");
            if (metadata == null) throw new IOException("Missing pack.mcmeta");
            metadataName = metadata.getName();
            try (var input = zip.getInputStream(metadata)) {
                metadataJson = JsonParser.parseString(
                        new String(input.readAllBytes(), StandardCharsets.UTF_8)).getAsJsonObject();
                if (metadataJson.has("filter"))
                    throw new IOException("Cross-pack resource filters are not supported for sharding");
            }
        }
        List<String> overlays = overlayDirectories(metadataJson);
        Map<String, Set<String>> overlayNamespaces = new LinkedHashMap<>();
        for (String directory : overlays) {
            Set<String> present = new HashSet<>();
            for (Entry entry : entries)
                if (entry.name.startsWith(directory + "/")) present.add(entry.namespace);
            overlayNamespaces.put(directory, present);
        }
        Map<String, Integer> weights = new TreeMap<>();
        for (Entry entry : entries)
            if (entry.namespace != null) weights.merge(entry.namespace, 1, Integer::sum);
        if (weights.size() < 2) throw new IOException("Not enough namespaces to split");
        int groups = Math.min(count, weights.size());
        List<List<String>> namespaces = new ArrayList<>();
        int[] loads = new int[groups];
        for (int i = 0; i < groups; i++) namespaces.add(new ArrayList<>());
        Map<String, Integer> owners = new HashMap<>();
        List<String> ordered = new ArrayList<>(weights.keySet());
        ordered.sort(Comparator.<String>comparingInt(weights::get).reversed().thenComparing(s -> s));
        for (String namespace : ordered) {
            int target = 0;
            if (compatibilityNamespaces != null) target = compatibilityNamespaces.contains(namespace) ? 1 : 0;
            else for (int i = 1; i < groups; i++) if (loads[i] < loads[target]) target = i;
            loads[target] += weights.get(namespace);
            namespaces.get(target).add(namespace);
            owners.put(namespace, target);
        }
        int roots = (int)entries.stream().filter(e -> e.namespace == null).count();
        long previousCost = 0;
        for (List<String> group : namespaces)
            previousCost += (long)group.size() * (roots + group.stream().mapToInt(weights::get).sum())
                    * (1 + overlays.size());
        if (compatibilityNamespaces == null) minimizeScans(namespaces, weights, roots, overlayNamespaces);
        else if (namespaces.stream().anyMatch(List::isEmpty))
            throw new IOException("Merged main pack or compatibility pack is empty");
        long optimizedCost = 0;
        for (int i = 0; i < groups; i++) {
            namespaces.get(i).sort(String::compareTo);
            for (String namespace : namespaces.get(i)) owners.put(namespace, i);
            optimizedCost += scanCost(namespaces.get(i), weights, roots, overlayNamespaces);
        }
        Files.createDirectories(output);
        List<Shard> result = new ArrayList<>();
        int prefix = entries.stream().mapToInt(Entry::start).min().orElseThrow();
        for (int i = 0; i < groups; i++) {
            List<Entry> selected = new ArrayList<>();
            for (Entry entry : entries) {
                // Root metadata/icon and unknown root files are identical in every shard.
                if (entry.namespace == null || owners.get(entry.namespace) == i) selected.add(entry);
            }
            JsonObject shardMetadata = metadataJson.deepCopy();
            if (shardMetadata.has("overlays")) {
                JsonArray kept = new JsonArray();
                for (JsonElement overlay : shardMetadata.getAsJsonObject("overlays").getAsJsonArray("entries")) {
                    String directory = overlay.getAsJsonObject().get("directory").getAsString();
                    if (selected.stream().anyMatch(e -> e.name.startsWith(directory + "/"))) kept.add(overlay);
                }
                if (kept.size() == 0) shardMetadata.remove("overlays");
                else shardMetadata.getAsJsonObject("overlays").add("entries", kept);
                if (!shardMetadata.equals(metadataJson)) {
                    Entry replacement = metadataEntry(metadataName, shardMetadata);
                    selected.replaceAll(e -> e.name.equals(metadataName) ? replacement : e);
                }
            }
            byte[] shard = write(data, prefix, selected);
            String hash = sha1(shard);
            Path file = output.resolve(hash + ".zip");
            if (!Files.exists(file)) Files.write(file, shard);
            else if (!Arrays.equals(Files.readAllBytes(file), shard))
                throw new IOException("Existing shard hash mismatch");
            result.add(new Shard(file.getFileName().toString(), hash, selected.size(),
                    List.copyOf(namespaces.get(i))));
        }
        return new Manifest(sha1(data), List.copyOf(result),
                new ScanCost((long)weights.size() * entries.size() * (1 + overlays.size()),
                        previousCost, optimizedCost));
    }

    private static List<String> overlayDirectories(JsonObject metadata) throws IOException {
        if (!metadata.has("overlays")) return List.of();
        List<String> directories = new ArrayList<>();
        for (JsonElement element : metadata.getAsJsonObject("overlays").getAsJsonArray("entries")) {
            String directory = element.getAsJsonObject().get("directory").getAsString();
            if (directory.isEmpty() || directory.contains("/") || directory.equals(".") || directory.equals(".."))
                throw new IOException("Unsupported overlay directory: " + directory);
            directories.add(directory);
        }
        return directories;
    }

    private static long scanCost(List<String> group, Map<String, Integer> weights, int roots,
            Map<String, Set<String>> overlays) {
        long entries = roots;
        for (String namespace : group) entries += weights.get(namespace);
        int layers = 1;
        for (Set<String> present : overlays.values())
            if (present.contains(null) || group.stream().anyMatch(present::contains)) layers++;
        return entries * group.size() * layers;
    }

    private static void minimizeScans(List<List<String>> groups, Map<String, Integer> weights, int roots,
            Map<String, Set<String>> overlays) {
        // Keep the configured pack count; move namespaces only when repeated ZIP scans decrease.
        while (true) {
            long bestDelta = 0;
            int from = -1, to = -1;
            String move = null;
            for (int a = 0; a < groups.size(); a++) {
                List<String> source = groups.get(a);
                if (source.size() == 1) continue;
                for (String namespace : List.copyOf(source)) {
                    for (int b = 0; b < groups.size(); b++) {
                        if (a == b) continue;
                        List<String> target = groups.get(b);
                        long before = scanCost(source, weights, roots, overlays)
                                + scanCost(target, weights, roots, overlays);
                        List<String> remaining = new ArrayList<>(source);
                        remaining.remove(namespace);
                        List<String> expanded = new ArrayList<>(target);
                        expanded.add(namespace);
                        long delta = scanCost(remaining, weights, roots, overlays)
                                + scanCost(expanded, weights, roots, overlays) - before;
                        if (delta < bestDelta) {
                            bestDelta = delta;
                            from = a;
                            to = b;
                            move = namespace;
                        }
                    }
                }
            }
            if (move == null) return;
            groups.get(from).remove(move);
            groups.get(to).add(move);
        }
    }

    private static Entry metadataEntry(String name, JsonObject metadata) throws IOException {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try (ZipOutputStream zip = new ZipOutputStream(bytes)) {
            ZipEntry entry = new ZipEntry(name);
            entry.setTime(0);
            zip.putNextEntry(entry);
            zip.write(metadata.toString().getBytes(StandardCharsets.UTF_8));
            zip.closeEntry();
        }
        byte[] archive = bytes.toByteArray();
        Entry entry = entries(archive).getFirst();
        return new Entry(name, null, entry.central, 0, 0,
                Arrays.copyOfRange(archive, entry.start, entry.end));
    }

    static String sha1(byte[] data) throws Exception {
        return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-1").digest(data));
    }

    private static List<Entry> entries(byte[] data) throws IOException {
        int end = -1;
        for (int i = data.length - 22; i >= Math.max(0, data.length - 65557); i--) {
            if (u32(data, i) == 0x06054b50L && i + 22 + u16(data, i + 20) == data.length) {
                end = i;
                break;
            }
        }
        if (end < 0) throw new IOException("Missing ZIP end record");
        int size = checked(u32(data, end + 12));
        int central = end - size;
        long adjustment = central - u32(data, end + 16);
        List<byte[]> headers = new ArrayList<>();
        List<Integer> starts = new ArrayList<>();
        List<String> names = new ArrayList<>();
        Set<String> unique = new HashSet<>();
        for (int p = central; p < end;) {
            if (p < 0 || p + 46 > end || u32(data, p) != 0x02014b50L)
                throw new IOException("Unsupported ZIP central directory");
            int length = 46 + u16(data, p + 28) + u16(data, p + 30) + u16(data, p + 32);
            if (p + length > end) throw new IOException("Truncated central directory");
            if (u32(data, p + 20) == 0xffffffffL || u32(data, p + 24) == 0xffffffffL
                    || u32(data, p + 42) == 0xffffffffL)
                throw new IOException("ZIP64 is not supported");
            String name = new String(data, p + 46, u16(data, p + 28), StandardCharsets.UTF_8);
            if (!unique.add(name)) throw new IOException("Duplicate ZIP entry: " + name);
            int start = checked(u32(data, p + 42) + adjustment);
            if (start + 30 > central || u32(data, start) != 0x04034b50L)
                throw new IOException("Invalid local ZIP entry");
            headers.add(Arrays.copyOfRange(data, p, p + length));
            starts.add(start);
            names.add(name);
            p += length;
        }
        List<Integer> sorted = starts.stream().distinct().sorted().toList();
        if (sorted.size() != starts.size()) throw new IOException("Shared ZIP offsets not supported");
        Map<Integer, Integer> ends = new HashMap<>();
        for (int i = 0; i < sorted.size(); i++)
            ends.put(sorted.get(i), i + 1 == sorted.size() ? central : sorted.get(i + 1));
        List<Entry> result = new ArrayList<>();
        for (int i = 0; i < headers.size(); i++) {
            String name = names.get(i);
            Matcher matcher = ASSET.matcher(name);
            String namespace = matcher.find() ? matcher.group(1) : null;
            // Do not mistake CE's nonempty fake-directory files for empty directories.
            boolean emptyDirectory = name.endsWith("/") && u32(headers.get(i), 24) == 0;
            if (!emptyDirectory)
                result.add(new Entry(name, namespace, headers.get(i), starts.get(i), ends.get(starts.get(i)), null));
        }
        if (result.size() >= 65535) throw new IOException("Too many ZIP entries");
        return result;
    }

    private static byte[] write(byte[] source, int prefix, List<Entry> entries) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        out.write(source, 0, prefix);
        List<byte[]> headers = new ArrayList<>();
        for (Entry entry : entries) {
            byte[] header = entry.central.clone();
            put32(header, 42, out.size());
            if (entry.local == null) out.write(source, entry.start, entry.end - entry.start);
            else out.write(entry.local);
            headers.add(header);
        }
        int offset = out.size();
        for (byte[] header : headers) out.write(header);
        int size = out.size() - offset;
        byte[] end = new byte[22];
        put32(end, 0, 0x06054b50);
        put16(end, 8, entries.size());
        put16(end, 10, entries.size());
        put32(end, 12, size);
        put32(end, 16, offset);
        out.write(end);
        return out.toByteArray();
    }

    private static int checked(long value) throws IOException {
        if (value < 0 || value > Integer.MAX_VALUE) throw new IOException("ZIP exceeds supported size");
        return (int) value;
    }
    private static int u16(byte[] b, int p) { return (b[p] & 255) | (b[p + 1] & 255) << 8; }
    private static long u32(byte[] b, int p) { return Integer.toUnsignedLong(u16(b, p) | u16(b, p + 2) << 16); }
    private static void put16(byte[] b, int p, int n) { b[p] = (byte)n; b[p + 1] = (byte)(n >>> 8); }
    private static void put32(byte[] b, int p, int n) { put16(b, p, n); put16(b, p + 2, n >>> 16); }

    public static void main(String[] args) throws Exception {
        if (args.length != 3) throw new IllegalArgumentException("Usage: <protected.zip> <output> <count>");
        Path output = Path.of(args[1]);
        Manifest manifest = split(Path.of(args[0]), output, Integer.parseInt(args[2]));
        Files.writeString(output.resolve("candidate.json"),
                new com.google.gson.GsonBuilder().setPrettyPrinting().create().toJson(manifest));
        System.out.println("Created " + manifest.shards.size() + " namespace shards");
    }
}
