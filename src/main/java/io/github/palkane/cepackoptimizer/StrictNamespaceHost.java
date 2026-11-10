package io.github.palkane.cepackoptimizer;

import com.google.gson.Gson;
import java.io.IOException;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.function.Consumer;
import java.util.zip.ZipFile;
import net.momirealms.craftengine.core.pack.host.*;
import net.momirealms.craftengine.core.plugin.network.NetWorkUser;

/** One content-addressed ZIP and its matching client resource map are published together. */
final class StrictNamespaceHost implements ResourcePackHost {
    private static final Gson JSON = new Gson();
    record Published(String file, StrictNamespacePack.Result result) {}
    private final Path state;
    private final ResourcePackHost original;
    private final ResourcePackHostType<?> type;
    private final Consumer<Map<String, Map<String, String>>> installer;
    private String installed;

    Path protectedBindingPath() { return state.resolve("protected-binding.json"); }

    StrictNamespaceHost(Path state, ResourcePackHost original, ResourcePackHostType<?> type,
                        Consumer<Map<String, Map<String, String>>> installer) {
        this.state = state;
        this.original = Objects.requireNonNull(original);
        this.type = type;
        this.installer = installer;
    }

    static void validate(Path source, StrictNamespacePack.Result result) throws Exception {
        if (!result.outputSha1().equals(NamespaceShards.sha1(Files.readAllBytes(source))))
            throw new IOException("Strict resource map does not match this ZIP");
        try (ZipFile zip = new ZipFile(source.toFile())) {
            Set<String> namespaces = new TreeSet<>(), names = new HashSet<>();
            zip.stream().forEach(e -> {
                if (e.isDirectory() && e.getSize() == 0) return;
                String name = e.getName().replaceFirst("/+$", "");
                names.add(name);
                int start = name.startsWith("assets/") ? 0 : name.indexOf("/assets/") + 1;
                if (start >= 0 && name.substring(start).startsWith("assets/"))
                    namespaces.add(name.substring(start).split("/", 3)[1]);
            });
            Set<String> expected = result.merge().namespacesAfter();
            if (!(expected.equals(Set.of("minecraft")) || expected.equals(Set.of("custom", "minecraft")))
                    || !namespaces.equals(expected))
                throw new IOException("Published namespaces do not match the resource map: " + namespaces);
            for (String kind : List.of("items", "font", "equipment")) {
                for (String target : result.merge().runtimeIds().getOrDefault(kind, Map.of()).values()) {
                    String path = "assets/" + target.replace(":", "/" + kind + "/") + ".json";
                    if (!names.contains(path) && names.stream().noneMatch(name -> name.endsWith("/" + path)))
                        throw new IOException("Missing strict " + kind + " target: " + target);
                }
            }
        }
    }

    void stage(Path source, StrictNamespacePack.Result result) throws Exception {
        validate(source, result);
        new ClientResourceMappings(result.merge().runtimeIds());
        Files.createDirectories(state.resolve("objects"));
        Files.copy(source, state.resolve("objects").resolve(result.outputSha1() + ".zip"),
                StandardCopyOption.REPLACE_EXISTING);
        ProtectedNamespacePack.saveBinding(protectedBindingPath(), result);
    }

    @Override public CompletableFuture<Void> upload(Path source) {
        if (!original.canUpload())
            return CompletableFuture.failedFuture(new IllegalStateException("This CE host requires manual upload"));
        return CompletableFuture.runAsync(() -> {
            try {
                Files.createDirectories(state.resolve("objects"));
                try (var channel = java.nio.channels.FileChannel.open(state.resolve("upload.lock"),
                        StandardOpenOption.CREATE, StandardOpenOption.WRITE); var lock = channel.tryLock()) {
                    if (lock == null) throw new IOException("Another strict publication is running");
                    var result = ProtectedNamespacePack.readBinding(protectedBindingPath(), source);
                    new ClientResourceMappings(result.merge().runtimeIds());
                    var publication = new Published(result.outputSha1() + ".zip", result);
                    Path snapshot = state.resolve("objects").resolve(publication.file);
                    validate(snapshot, result);
                    original.upload(snapshot).join();
                    publish(publication);
                }
            } catch (Exception e) {
                throw new IllegalStateException("CE pack upload/publication failed; previous mappings retained", e);
            }
        });
    }

    private synchronized void publish(Published publication) throws IOException {
        Path temporary = Files.createTempFile(state, "publication-", ".tmp");
        try {
            Files.writeString(temporary, JSON.toJson(publication));
            Files.move(temporary, state.resolve("current.json"),
                    StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
        } finally { Files.deleteIfExists(temporary); }
        install(publication);
    }

    private synchronized void install(Published publication) {
        if (publication.result.outputSha1().equals(installed)) return;
        installer.accept(publication.result.merge().runtimeIds());
        installed = publication.result.outputSha1();
    }

    void restoreMappings() {
        Path file = state.resolve("current.json");
        if (!Files.exists(file)) return;
        try {
            var publication = JSON.fromJson(Files.readString(file), Published.class);
            validate(state.resolve("objects").resolve(publication.file), publication.result);
            installer.accept(publication.result.merge().runtimeIds());
            installed = publication.result.outputSha1();
        } catch (Exception e) { throw new IllegalStateException("Cannot restore strict resource mappings", e); }
    }

    @Override public CompletableFuture<List<ResourcePackDownloadData>> requestResourcePackDownloadLink(NetWorkUser user) {
        return original.requestResourcePackDownloadLink(user).thenApply(downloads -> {
            try {
                if (downloads.isEmpty()) return downloads;
                Path current = state.resolve("current.json");
                if (Files.exists(current)) {
                    var publication = JSON.fromJson(Files.readString(current), Published.class);
                    if (matches(downloads, publication.result)) {
                        install(publication);
                        return downloads;
                    }
                }
                // Read-only hosts publish out of band; CE's configured SHA-1 binds their URL to the staged ZIP.
                if (!original.canUpload() && Files.exists(protectedBindingPath())) {
                    var result = JSON.fromJson(Files.readString(protectedBindingPath()), StrictNamespacePack.Result.class);
                    if (matches(downloads, result)) {
                        var publication = new Published(result.outputSha1() + ".zip", result);
                        ProtectedNamespacePack.readBinding(protectedBindingPath(),
                                state.resolve("objects").resolve(publication.file));
                        new ClientResourceMappings(result.merge().runtimeIds());
                        publish(publication);
                        return downloads;
                    }
                    throw new IOException("CE external host SHA-1 does not match the staged protected ZIP");
                }
                if (Files.exists(current))
                    throw new IOException("CE host SHA-1 does not match the published resource mappings");
                return downloads;
            } catch (Exception e) { throw new CompletionException(e); }
        });
    }

    private static boolean matches(List<ResourcePackDownloadData> downloads, StrictNamespacePack.Result result) {
        return downloads.stream().anyMatch(data -> result.outputSha1().equalsIgnoreCase(data.sha1()));
    }
    @Override public boolean canUpload() { return original.canUpload(); }
    @Override public ResourcePackHostType<?> type() { return type; }
}
