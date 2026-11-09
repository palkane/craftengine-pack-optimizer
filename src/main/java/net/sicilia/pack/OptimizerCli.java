package net.sicilia.pack;

import com.google.gson.GsonBuilder;
import java.nio.file.*;
import java.util.List;

/** Offline verification on an extracted copy, never on the live generated ZIP. */
public final class OptimizerCli {
    public static void main(String[] args) throws Exception {
        if (args.length != 2) throw new IllegalArgumentException("Usage: <extracted-pack> <report.json>");
        var result = PackOptimizer.optimize(Path.of(args[0]), true, List.of(
                "assets/siciliaforge/textures/item/foundry/breaking/**",
                "assets/siciliaforge/textures/item/sand_plane_*"));
        Files.writeString(Path.of(args[1]), new GsonBuilder().setPrettyPrinting().create().toJson(result));
        System.out.println(result.summary());
    }
}
