package net.sicilia.pack;

import com.google.gson.GsonBuilder;
import java.nio.file.*;
import java.util.zip.*;

/** Builds a pre-protection snapshot from a real generated pack without touching the server. */
public final class PrefixPackProbe {
    public static void main(String[] args) throws Exception {
        if (args.length == 3) {
            var before = new com.google.gson.Gson().fromJson(Files.readString(Path.of(args[0])),
                    StrictNamespacePack.Result.class);
            Path archive = Path.of(args[1]);
            byte[] original = Files.readAllBytes(archive);
            var result = ProtectedNamespacePack.seal(archive,
                    new ProtectedNamespacePack.Prepared(before.merge(), before.directorySprites()));
            ProtectedNamespacePack.saveBinding(Path.of(args[2]), result);
            if (!java.util.Arrays.equals(original, Files.readAllBytes(archive)))
                throw new AssertionError("Binding changed native protection bytes");
            System.out.println("PASS native protected archive binding, runtime target paths and byte preservation");
            return;
        }
        Path source = Path.of(args[0]), directory = Path.of(args[1]);
        if (Files.exists(directory)) throw new IllegalArgumentException("Use a new probe directory");
        Path root = directory.resolve("pack");
        StrictNamespacePack.extract(source, root);
        var prepared = ProtectedNamespacePack.prepare(root);
        Path output = directory.resolve("prefix-unprotected.zip");
        try (var zip = new ZipOutputStream(Files.newOutputStream(output)); var paths = Files.walk(root)) {
            for (Path path : paths.filter(Files::isRegularFile).sorted().toList()) {
                zip.putNextEntry(new ZipEntry(root.relativize(path).toString()));
                Files.copy(path, zip);
                zip.closeEntry();
            }
        }
        var result = new StrictNamespacePack.Result(prepared.merge(), prepared.directorySprites(),
                NamespaceShards.sha1(Files.readAllBytes(source)), NamespaceShards.sha1(Files.readAllBytes(output)));
        Files.writeString(directory.resolve("mapping.json"), new GsonBuilder().setPrettyPrinting().create().toJson(result));
        StrictNamespaceHost.validate(output, result);
        System.out.println("PASS prefix snapshot: " + result.merge().namespacesBefore().size() + " -> "
                + result.merge().namespacesAfter() + "; files=" + result.merge().files());
    }
}
