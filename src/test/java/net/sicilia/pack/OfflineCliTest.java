package net.sicilia.pack;

import java.nio.file.*;
import java.util.Comparator;

public final class OfflineCliTest {
    public static void main(String[] args) throws Exception {
        Path temporary = Files.createTempDirectory("offline-cli-");
        int checks = 0;
        try {
            Path copy = Files.createDirectories(temporary.resolve("copy"));
            if (!OptimizerCli.isolatedRoot(copy.toString()).equals(copy.toRealPath()))
                throw new AssertionError("isolated copy rejected");
            checks++;
            Path server = Files.createDirectories(temporary.resolve("server"));
            Files.writeString(server.resolve("server.properties"), "");
            Path pack = Files.createDirectories(server.resolve("plugins/CraftEngine/generated/pack"));
            for (String cli : new String[]{"slim", "merge", "compatibility"}) {
                try {
                    String[] input = {pack.toString(), temporary.resolve("report.json").toString()};
                    switch (cli) {
                        case "slim" -> OptimizerCli.main(input);
                        case "merge" -> NamespaceMerger.main(input);
                        default -> NamespaceCompatibility.main(input);
                    }
                    throw new AssertionError(cli + " accepted a server directory");
                } catch (IllegalArgumentException expected) {
                    if (!expected.getMessage().contains("isolated copy")) throw expected;
                    checks++;
                }
            }
        } finally {
            try (var paths = Files.walk(temporary)) {
                for (Path path : paths.sorted(Comparator.reverseOrder()).toList()) Files.delete(path);
            }
        }
        System.out.println("Offline CLI checks passed: " + checks);
    }
}
