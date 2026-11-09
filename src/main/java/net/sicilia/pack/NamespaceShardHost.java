package net.sicilia.pack;

import com.google.gson.Gson;
import java.io.IOException;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.zip.ZipFile;
import net.momirealms.craftengine.core.pack.host.*;
import net.momirealms.craftengine.core.plugin.config.ConfigSection;
import net.momirealms.craftengine.core.plugin.network.NetWorkUser;
import net.momirealms.craftengine.core.item.network.ItemModelMappings;
import net.momirealms.craftengine.core.util.Key;

final class NamespaceShardHost implements ResourcePackHost {
    private static final Gson JSON = new Gson();
    private final CraftEnginePackOptimizer plugin;
    private final ConfigSection section;
    private final String id;
    private final Path state;
    private final int count;
    private final ShardS3 s3;
    private final boolean compatibility;
    private ResourcePackHost fallback;
    private final Map<UUID, Deque<Long>> requests = new HashMap<>();

    NamespaceShardHost(CraftEnginePackOptimizer plugin, String id, ConfigSection section) {
        this.plugin = plugin;
        this.id = id;
        this.section = section.copy();
        // Match the current live host; fail rather than silently reinterpret other S3 modes.
        if (!section.getString("protocol", "https").equals("https")
                || section.getBoolean("path_style") || section.getBoolean("use_environment_variables")
                || section.containsKey("cdn"))
            throw new IllegalArgumentException("Namespace shards require direct HTTPS virtual-host S3");
        this.state = Path.of(section.getNonEmptyString("shard-state-directory"));
        this.count = section.getInt("shard-count", 8);
        this.compatibility = section.getString("shard-layout", "").equals("merged-compat");
        if (compatibility)
            throw new IllegalArgumentException("merged-compat is retired after a rendering regression; use the strict single-pack host");
        this.s3 = new ShardS3(section.getNonEmptyString("endpoint"), section.getNonEmptyString("bucket"),
                section.getNonEmptyString("region"), section.getNonEmptyString("access_key_id"),
                section.getNonEmptyString("access_key_secret"),
                section.getNonEmptyString("upload_path") + ".shards",
                section.getInt("validity", 10));
        if (compatibility) plugin.compatibilityHost(this);
    }

    @Override
    public CompletableFuture<Void> upload(Path source) {
        return CompletableFuture.runAsync(() -> {
            try {
                Files.createDirectories(state);
                // Both servers share the state; do not publish concurrent generations.
                try (var channel = FileChannel.open(state.resolve("upload.lock"),
                        StandardOpenOption.CREATE, StandardOpenOption.WRITE);
                        var lock = channel.tryLock()) {
                    if (lock == null) throw new IOException("Another shard upload is running");
                    NamespaceShards.Manifest manifest;
                    if (compatibility) {
                        Path bindingFile = plugin.getDataFolder().toPath().resolve("namespace-binding.json");
                        var binding = JSON.fromJson(Files.readString(bindingFile), NamespaceCompatibility.Binding.class);
                        manifest = publishCompatibilityGroup(source, state, binding, s3::upload);
                        installMappings(manifest);
                    } else manifest = publishGroup(source, state, count, s3::upload);
                    plugin.getLogger().info("Published " + manifest.shards().size()
                            + " namespace shards; source " + manifest.sourceSha1());
                    plugin.getLogger().info("Modeled ZIP entry visits per listing (all declared overlays): "
                            + manifest.scanCost().previousShards() + " -> "
                            + manifest.scanCost().optimizedShards()
                            + "; not a client reload benchmark.");
                }
            } catch (Exception e) {
                throw new IllegalStateException("Namespace shard upload failed; previous group retained", e);
            }
        });
    }

    @FunctionalInterface
    interface Upload { void send(String file, byte[] bytes, String sha1) throws Exception; }

    static NamespaceShards.Manifest publishGroup(Path source, Path state, int count, Upload upload) throws Exception {
        var manifest = NamespaceShards.split(source, state.resolve("objects"), count);
        for (var shard : manifest.shards()) {
            byte[] bytes = Files.readAllBytes(state.resolve("objects").resolve(shard.file()));
            upload.send(shard.file(), bytes, shard.sha1());
        }
        publish(state, manifest);
        return manifest;
    }

    static NamespaceShards.Manifest publishCompatibilityGroup(Path source, Path state,
            NamespaceCompatibility.Binding binding, Upload upload) throws Exception {
        if (!NamespaceShards.sha1(Files.readAllBytes(source)).equals(binding.sourceSha1()))
            throw new IOException("Namespace mapping does not match the protected ZIP");
        try (ZipFile zip = new ZipFile(source.toFile())) {
            Set<String> paths = new HashSet<>();
            zip.stream().forEach(entry -> paths.add(entry.getName().replaceFirst("/$", "")));
            for (String id : binding.result().itemModels().values()) {
                String[] parts = id.split(":", 2);
                String path = "assets/" + parts[0] + "/items/" + parts[1] + ".json";
                if (!paths.contains(path) && paths.stream().noneMatch(p -> p.endsWith("/" + path)))
                    throw new IOException("Published item-model target missing: " + id);
            }
        }
        var split = NamespaceShards.splitCompatibility(source, state.resolve("objects"),
                binding.result().compatibilityNamespaces());
        var manifest = new NamespaceShards.Manifest(split.sourceSha1(), split.shards(),
                split.scanCost(), binding.result().itemModels());
        mappingKeys(manifest);
        for (var shard : manifest.shards())
            upload.send(shard.file(), Files.readAllBytes(state.resolve("objects").resolve(shard.file())),
                    shard.sha1());
        publish(state, manifest);
        return manifest;
    }

    static void publish(Path state, NamespaceShards.Manifest manifest) throws IOException {
        Path temporary = Files.createTempFile(state, "manifest-", ".tmp");
        try {
            Files.writeString(temporary, JSON.toJson(manifest));
            Files.move(temporary, state.resolve("current.json"),
                    StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
        } finally { Files.deleteIfExists(temporary); }
    }

    private static Map<Key, Key> mappingKeys(NamespaceShards.Manifest manifest) {
        Map<Key, Key> result = new HashMap<>();
        if (manifest.itemModels() != null)
            manifest.itemModels().forEach((a, b) -> result.put(Key.of(a), Key.of(b)));
        return Map.copyOf(result);
    }

    private void installMappings(NamespaceShards.Manifest manifest) {
        ItemModelMappings.setMappings(mappingKeys(manifest));
        plugin.getLogger().info("Installed " + manifest.itemModels().size()
                + " client-only item-model mappings for published namespace pack.");
    }

    void restoreMappings() {
        if (!compatibility) return;
        try {
            Path current = state.resolve("current.json");
            if (Files.exists(current))
                installMappings(JSON.fromJson(Files.readString(current), NamespaceShards.Manifest.class));
        } catch (Exception e) {
            throw new IllegalStateException("Cannot restore published namespace item mappings", e);
        }
    }

    @Override
    public CompletableFuture<List<ResourcePackDownloadData>> requestResourcePackDownloadLink(NetWorkUser user) {
        try {
            Path current = state.resolve("current.json");
            if (!Files.exists(current)) {
                synchronized (this) {
                    if (fallback == null) fallback = ResourcePackHosts.S3.factory().create(id, section);
                }
                return fallback.requestResourcePackDownloadLink(user);
            }
            if (!allow(user.uuid())) return CompletableFuture.completedFuture(List.of());
            var manifest = JSON.fromJson(Files.readString(current), NamespaceShards.Manifest.class);
            List<ResourcePackDownloadData> data = new ArrayList<>();
            int index = 0;
            for (var shard : manifest.shards()) {
                UUID uuid = UUID.nameUUIDFromBytes(("sicilia:namespace-shards/" + id + "/" + index++)
                        .getBytes(StandardCharsets.UTF_8));
                data.add(new ResourcePackDownloadData(s3.url("GET", shard.file()), uuid, shard.sha1()));
            }
            if (data.isEmpty()) throw new IOException("Published shard group is empty");
            return CompletableFuture.completedFuture(List.copyOf(data));
        } catch (Exception e) {
            return CompletableFuture.failedFuture(new IllegalStateException("Cannot prepare namespace shards", e));
        }
    }

    private synchronized boolean allow(UUID user) {
        ConfigSection rate = section.getSection("rate_map");
        if (rate == null) return true;
        long now = System.nanoTime();
        long interval = Math.max(1, rate.getInt("reset_interval", 20)) * 1_000_000_000L;
        requests.values().removeIf(q -> q.isEmpty() || now - q.getLast() >= interval);
        Deque<Long> times = requests.computeIfAbsent(user, ignored -> new ArrayDeque<>());
        while (!times.isEmpty() && now - times.getFirst() >= interval) times.removeFirst();
        if (times.size() >= Math.max(1, rate.getInt("max_requests", 5))) return false;
        times.addLast(now);
        return true;
    }

    @Override public boolean canUpload() { return true; }
    @Override public ResourcePackHostType<? extends ResourcePackHost> type() { return plugin.shardType(); }
}
