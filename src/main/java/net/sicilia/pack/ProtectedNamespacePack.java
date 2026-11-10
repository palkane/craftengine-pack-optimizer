package net.sicilia.pack;

import com.google.gson.Gson;
import java.io.IOException;
import java.nio.file.*;
import java.util.*;

/** Changes the generated tree before CE writes its final, protected archive. */
final class ProtectedNamespacePack {
    record Prepared(NamespaceMerger.Result merge, int directorySprites) {}

    static Prepared prepare(Path root) throws IOException {
        Map<String, String> spriteIds = new TreeMap<>();
        int sprites = StrictNamespacePack.rewriteDirectories(root, spriteIds);
        var merge = NamespaceMerger.optimize(root, "minecraft");
        if (!merge.namespacesAfter().equals(Set.of("minecraft")))
            throw new IOException("Prepared pack must contain only minecraft");
        Map<String, Map<String, String>> mappings = new TreeMap<>(merge.runtimeIds());
        var allSprites = new TreeMap<>(mappings.getOrDefault("sprites", Map.of()));
        allSprites.putAll(spriteIds);
        mappings.put("sprites", Map.copyOf(allSprites));
        merge = new NamespaceMerger.Result(merge.files(), merge.moved(), merge.rewritten(),
                merge.namespacesBefore(), merge.namespacesAfter(), Map.copyOf(mappings));
        return new Prepared(merge, sprites);
    }

    static StrictNamespacePack.Result seal(Path protectedZip, Prepared prepared) throws Exception {
        String hash = NamespaceShards.sha1(Files.readAllBytes(protectedZip));
        var result = new StrictNamespacePack.Result(prepared.merge(), prepared.directorySprites(), hash, hash);
        StrictNamespaceHost.validate(protectedZip, result);
        return result;
    }

    static void saveBinding(Path binding, StrictNamespacePack.Result result) throws IOException {
        Files.createDirectories(binding.toAbsolutePath().getParent());
        Path temporary = Files.createTempFile(binding.toAbsolutePath().getParent(), "binding-", ".tmp");
        try {
            Files.writeString(temporary, new Gson().toJson(result));
            Files.move(temporary, binding, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
        } finally {
            Files.deleteIfExists(temporary);
        }
    }

    static StrictNamespacePack.Result readBinding(Path binding, Path source) throws Exception {
        var result = new Gson().fromJson(Files.readString(binding), StrictNamespacePack.Result.class);
        if (!result.merge().namespacesAfter().equals(Set.of("minecraft")))
            throw new IOException("Run the protected-prefix workflow before publishing");
        StrictNamespaceHost.validate(source, result);
        return result;
    }
}
