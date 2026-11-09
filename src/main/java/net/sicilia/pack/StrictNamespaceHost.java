package net.sicilia.pack;

import com.google.gson.Gson;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.zip.ZipFile;
import net.momirealms.craftengine.core.pack.host.*;
import net.momirealms.craftengine.core.plugin.config.ConfigSection;
import net.momirealms.craftengine.core.plugin.network.NetWorkUser;

/** One content-addressed ZIP and its matching client resource map are published together. */
final class StrictNamespaceHost implements ResourcePackHost {
    private static final Gson JSON = new Gson();
    record Published(String file, StrictNamespacePack.Result result) {}
    private final CraftEnginePackOptimizer plugin;
    private final Path state;
    private final String id;
    private final ShardS3 s3;
    private final ConfigSection section;
    private final ResourcePackHost original;
    private final Map<UUID, Deque<Long>> requests = new HashMap<>();
    private String installed;

    StrictNamespaceHost(CraftEnginePackOptimizer plugin, String id, ConfigSection section) {
        this(plugin, id, section, null);
    }

    StrictNamespaceHost(CraftEnginePackOptimizer plugin, String id, ConfigSection section, ResourcePackHost original) {
        this.plugin = plugin;
        this.id = id;
        this.section = section.copy();
        this.original = original;
        if (!section.getString("protocol", "https").equals("https")
                || section.getBoolean("path_style") || section.getBoolean("use_environment_variables")
                || section.containsKey("cdn"))
            throw new IllegalArgumentException("Strict pack host requires direct HTTPS virtual-host S3");
        state = Path.of(section.getNonEmptyString("namespace-state-directory"));
        s3 = new ShardS3(section.getNonEmptyString("endpoint"), section.getNonEmptyString("bucket"),
                section.getNonEmptyString("region"), section.getNonEmptyString("access_key_id"),
                section.getNonEmptyString("access_key_secret"),
                section.getNonEmptyString("upload_path") + ".strict", section.getInt("validity", 120));
        plugin.strictHost(this);
    }

    static void validate(Path source, StrictNamespacePack.Result result) throws Exception {
        if (!result.outputSha1().equals(NamespaceShards.sha1(Files.readAllBytes(source))))
            throw new IOException("Strict resource map does not match this ZIP");
        try (ZipFile zip = new ZipFile(source.toFile())) {
            Set<String> namespaces = new TreeSet<>(), names = new HashSet<>();
            zip.stream().forEach(e -> {
                String name = e.getName();
                names.add(name);
                int start = name.startsWith("assets/") ? 0 : name.indexOf("/assets/") + 1;
                if (start >= 0 && name.substring(start).startsWith("assets/"))
                    namespaces.add(name.substring(start).split("/", 3)[1]);
            });
            if (!namespaces.equals(Set.of("custom", "minecraft")))
                throw new IOException("Strict publication must contain exactly minecraft/custom, found " + namespaces);
            for (String kind : List.of("items", "font", "equipment")) {
                for (String target : result.merge().runtimeIds().getOrDefault(kind, Map.of()).values()) {
                    String path = "assets/" + target.replace(":", "/" + kind + "/") + ".json";
                    if (!names.contains(path) && names.stream().noneMatch(name -> name.endsWith("/" + path)))
                        throw new IOException("Missing strict " + kind + " target: " + target);
                }
            }
        }
    }

    @Override public CompletableFuture<Void> upload(Path source) {
        return CompletableFuture.runAsync(() -> {
            try {
                Files.createDirectories(state.resolve("objects"));
                try (var channel = java.nio.channels.FileChannel.open(state.resolve("upload.lock"),
                        StandardOpenOption.CREATE, StandardOpenOption.WRITE); var lock = channel.tryLock()) {
                    if (lock == null) throw new IOException("Another strict publication is running");
                    Path work = Files.createTempDirectory(state, "build-");
                    try {
                        Path pack = original == null ? source : work.resolve("strict.zip");
                        var result = original == null
                                ? JSON.fromJson(Files.readString(plugin.getDataFolder().toPath()
                                        .resolve("strict-binding.json")), StrictNamespacePack.Result.class)
                                : StrictNamespacePack.build(source, pack, work.resolve("pack"));
                        validate(pack, result);
                        new ClientResourceMappings(result.merge().runtimeIds());
                        String file = result.outputSha1() + ".zip";
                        byte[] data = Files.readAllBytes(pack);
                        s3.upload(file, data, result.outputSha1());
                        Files.write(state.resolve("objects").resolve(file), data);
                        var published = new Published(file, result);
                        Path temporary = Files.createTempFile(state, "publication-", ".tmp");
                        try {
                            Files.writeString(temporary, JSON.toJson(published));
                            Files.move(temporary, state.resolve("current.json"),
                                    StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
                        } finally { Files.deleteIfExists(temporary); }
                        install(published);
                        try {
                            var readable = new org.bukkit.configuration.file.YamlConfiguration();
                            readable.set("pack-sha1", result.outputSha1());
                            readable.set("resources", result.merge().runtimeIds());
                            readable.save(plugin.getDataFolder().toPath().resolve("client-resource-mappings.yml").toFile());
                        } catch (IOException e) {
                            plugin.getLogger().warning("Strict pack published; readable mapping export failed: "
                                    + e.getClass().getSimpleName());
                        }
                        plugin.getLogger().info("Published one strict minecraft/custom pack: " + result.outputSha1());
                    } finally {
                        try (var paths = Files.walk(work)) {
                            for (Path path : paths.sorted(Comparator.reverseOrder()).toList()) Files.delete(path);
                        } catch (IOException e) {
                            plugin.getLogger().warning("Strict build cleanup failed: " + e.getClass().getSimpleName());
                        }
                    }
                }
            } catch (Exception e) {
                throw new IllegalStateException("Strict pack publication failed; previous publication retained", e);
            }
        });
    }

    private synchronized void install(Published publication) {
        if (publication.result.outputSha1().equals(installed)) return;
        plugin.installStrictMappings(publication.result.merge().runtimeIds());
        installed = publication.result.outputSha1();
    }

    void restoreMappings() {
        Path file = state.resolve("current.json");
        if (!Files.exists(file)) return;
        try {
            var publication = JSON.fromJson(Files.readString(file), Published.class);
            validate(state.resolve("objects").resolve(publication.file), publication.result);
            plugin.installStrictMappings(publication.result.merge().runtimeIds());
            installed = publication.result.outputSha1();
        } catch (Exception e) { throw new IllegalStateException("Cannot restore strict resource mappings", e); }
    }

    @Override public CompletableFuture<List<ResourcePackDownloadData>> requestResourcePackDownloadLink(NetWorkUser user) {
        try {
            if (original != null && !Files.exists(state.resolve("current.json")))
                return original.requestResourcePackDownloadLink(user);
            if (!allow(user.uuid())) return CompletableFuture.completedFuture(List.of());
            var publication = JSON.fromJson(Files.readString(state.resolve("current.json")), Published.class);
            install(publication);
            UUID uuid = UUID.nameUUIDFromBytes(("sicilia:strict/" + id).getBytes(StandardCharsets.UTF_8));
            return CompletableFuture.completedFuture(List.of(new ResourcePackDownloadData(
                    s3.url("GET", publication.file), uuid, publication.result.outputSha1())));
        } catch (Exception e) { return CompletableFuture.failedFuture(e); }
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
    @Override public ResourcePackHostType<? extends ResourcePackHost> type() { return plugin.strictType(); }
}
