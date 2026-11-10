package io.github.palkane.cepackoptimizer;

import java.io.IOException;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.zip.*;
import net.momirealms.craftengine.core.pack.host.*;
import net.momirealms.craftengine.core.pack.host.impl.ExternalHost;
import net.momirealms.craftengine.core.plugin.config.ConfigSection;
import net.momirealms.craftengine.core.plugin.network.NetWorkUser;
import net.momirealms.craftengine.core.util.Key;

/** Exercises the real translation host with file IO and CE's actual external-host implementation. */
public final class NativeHostingTest {
    private static int checks;
    private static final ResourcePackHostType<?> TYPE = new ResourcePackHostType<>(Key.of("test:translated"), null);
    private static void check(boolean value, String message) {
        checks++;
        if (!value) throw new AssertionError(message);
    }

    private static final class FileHost implements ResourcePackHost {
        final Path destination;
        final UUID uuid = UUID.randomUUID();
        final CountDownLatch started = new CountDownLatch(1);
        CompletableFuture<Void> completion = new CompletableFuture<>();
        List<ResourcePackDownloadData> links = List.of();
        FileHost(Path destination) { this.destination = destination; }
        public CompletableFuture<Void> upload(Path source) {
            try {
                Files.copy(source, destination, StandardCopyOption.REPLACE_EXISTING);
                started.countDown();
                return completion.thenRun(() -> {
                    try {
                        links = List.of(new ResourcePackDownloadData("https://example.com/packs/download?native=true",
                                uuid, NamespaceShards.sha1(Files.readAllBytes(destination))));
                    } catch (Exception e) { throw new CompletionException(e); }
                });
            } catch (IOException e) { return CompletableFuture.failedFuture(e); }
        }
        public CompletableFuture<List<ResourcePackDownloadData>> requestResourcePackDownloadLink(NetWorkUser user) {
            return CompletableFuture.completedFuture(links);
        }
        public boolean canUpload() { return true; }
        public ResourcePackHostType<?> type() { return ResourcePackHosts.SELF; }
    }

    private static StrictNamespacePack.Result fixture(Path zip, String id) throws Exception {
        try (var out = new ZipOutputStream(Files.newOutputStream(zip))) {
            out.putNextEntry(new ZipEntry("assets/minecraft/items/demo/" + id + ".json/"));
            out.write("{\"model\":{\"type\":\"minecraft:empty\"}}".getBytes(java.nio.charset.StandardCharsets.UTF_8));
            out.closeEntry();
        }
        byte[] bytes = Files.readAllBytes(zip);
        for (int i = 0; i < bytes.length - 46; i++)
            if (bytes[i] == 0x50 && bytes[i + 1] == 0x4b && bytes[i + 2] == 1 && bytes[i + 3] == 2) {
                bytes[i + 16] ^= 0x7f;
                break;
            }
        Files.write(zip, bytes);
        String hash = NamespaceShards.sha1(bytes);
        return new StrictNamespacePack.Result(new NamespaceMerger.Result(1, 1, 1,
                Set.of("demo"), Set.of("minecraft"),
                Map.of("items", Map.of("demo:" + id, "minecraft:demo/" + id))), 0, hash, hash);
    }

    private static ResourcePackHost external(String hash) {
        return ExternalHost.FACTORY.create("external-pack", ConfigSection.of("external-pack", Map.of(
                "url", "https://example.com/custom.zip?token=example",
                "uuid", "00000000-0000-0000-0000-000000000123", "sha1", hash)));
    }

    public static void main(String[] args) throws Exception {
        Path root = Files.createTempDirectory("native-hosting-");
        try {
            Path source = root.resolve("generated.zip"), state = root.resolve("state");
            var first = fixture(source, "first");
            var installs = new ArrayList<Map<String, Map<String, String>>>();
            var nativeHost = new FileHost(root.resolve("served.zip"));
            var translated = new StrictNamespaceHost(state, nativeHost, TYPE, installs::add);
            translated.stage(source, first);
            check(installs.isEmpty() && !Files.exists(state.resolve("current.json")), "staging activated mappings");
            check(translated.canUpload(), "native upload capability lost");
            var pending = translated.upload(source);
            check(nativeHost.started.await(10, TimeUnit.SECONDS), "native upload not called");
            check(!pending.isDone() && installs.isEmpty(), "mapping published before native upload completed");
            nativeHost.completion.complete(null);
            pending.join();
            check(installs.equals(List.of(first.merge().runtimeIds())), "successful upload did not install mappings");
            check(Arrays.equals(Files.readAllBytes(source), Files.readAllBytes(nativeHost.destination)),
                    "native upload received repacked bytes");
            check(translated.requestResourcePackDownloadLink(null).join() == nativeHost.links,
                    "native download metadata was replaced");
            var restored = new ArrayList<Map<String, Map<String, String>>>();
            new StrictNamespaceHost(state, nativeHost, TYPE, restored::add).restoreMappings();
            check(restored.equals(installs), "restart failed to restore committed mappings");

            byte[] previous = Files.readAllBytes(state.resolve("current.json"));
            var second = fixture(source, "second");
            translated.stage(source, second);
            nativeHost.completion = CompletableFuture.failedFuture(new IOException("native upload rejected"));
            try {
                translated.upload(source).join();
                throw new AssertionError("failed native upload was accepted");
            } catch (CompletionException expected) { checks++; }
            check(Arrays.equals(previous, Files.readAllBytes(state.resolve("current.json"))) && installs.size() == 1,
                    "failed upload replaced previous publication");
            check(translated.requestResourcePackDownloadLink(null).join() == nativeHost.links,
                    "failed upload lost the previous native download response");
            nativeHost.links = List.of();
            check(translated.requestResourcePackDownloadLink(null).join().isEmpty(), "native rate-limit result ignored");

            Path externalState = root.resolve("external-state");
            var externalInstalls = new ArrayList<Map<String, Map<String, String>>>();
            var nativeExternal = external(second.outputSha1().toUpperCase(Locale.ROOT));
            var external = new StrictNamespaceHost(externalState, nativeExternal, TYPE, externalInstalls::add);
            external.stage(source, second);
            check(!external.canUpload(), "external host was converted into an uploader");
            check(externalInstalls.isEmpty(), "external mappings activated before matching download metadata");
            var expected = nativeExternal.requestResourcePackDownloadLink(null).join();
            check(external.requestResourcePackDownloadLink(null).join().equals(expected), "external URL/UUID/hash changed");
            check(externalInstalls.equals(List.of(second.merge().runtimeIds())), "external matching SHA-1 not activated");
            try {
                external.upload(source).join();
                throw new AssertionError("manual host accepted an automatic upload");
            } catch (CompletionException expectedFailure) { checks++; }
            var wrong = new StrictNamespaceHost(externalState, external(""), TYPE, externalInstalls::add);
            try {
                wrong.requestResourcePackDownloadLink(null).join();
                throw new AssertionError("external URL without matching SHA-1 accepted");
            } catch (CompletionException expectedFailure) { checks++; }
            check(externalInstalls.size() == 1, "external mismatch changed mappings");
            var peer = new StrictNamespaceHost(externalState, nativeExternal, TYPE, restored::add);
            peer.restoreMappings();
            check(restored.getLast().equals(second.merge().runtimeIds()), "peer did not restore external publication");

            byte[] unchanged = Files.readAllBytes(source);
            Files.writeString(source, "not the bound archive");
            try {
                translated.upload(source).join();
                throw new AssertionError("mismatched ZIP uploaded");
            } catch (CompletionException expectedFailure) { checks++; }
            check(Arrays.equals(Files.readAllBytes(nativeHost.destination), unchanged),
                    "invalid input reached the native host");
        } finally {
            try (var files = Files.walk(root)) {
                for (Path file : files.sorted(Comparator.reverseOrder()).toList()) Files.delete(file);
            }
        }
        System.out.println("Native hosting checks passed: " + checks);
    }
}
