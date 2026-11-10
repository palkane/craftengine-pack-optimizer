package io.github.palkane.cepackoptimizer;

import com.google.gson.GsonBuilder;
import java.nio.file.*;
import java.util.Arrays;

/** Offline verification on an extracted copy, never on the live generated ZIP. */
public final class OptimizerCli {
    public static void main(String[] args) throws Exception {
        if (args.length < 2) throw new IllegalArgumentException("Usage: <isolated-pack-tree> <report.json> [keep-glob ...]");
        var result = PackOptimizer.optimize(isolatedRoot(args[0]), true,
                Arrays.asList(args).subList(2, args.length));
        Files.writeString(Path.of(args[1]), new GsonBuilder().setPrettyPrinting().create().toJson(result));
        System.out.println(result.summary());
    }

    static Path isolatedRoot(String input) throws java.io.IOException {
        Path root = Path.of(input).toRealPath();
        for (Path directory = root; directory != null; directory = directory.getParent()) {
            if (Files.exists(directory.resolve("server.properties")))
                throw new IllegalArgumentException("Use an isolated copy outside the server directory");
        }
        return root;
    }
}
