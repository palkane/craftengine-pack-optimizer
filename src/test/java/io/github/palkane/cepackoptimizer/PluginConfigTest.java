package io.github.palkane.cepackoptimizer;

import java.net.URLClassLoader;
import java.nio.file.*;
import java.util.Comparator;
import org.bukkit.plugin.java.JavaPlugin;

/** Runs onLoad and Bukkit's real default-resource writer without starting a Minecraft server. */
public final class PluginConfigTest {
    private static void field(JavaPlugin plugin, String name, Object value) throws Exception {
        var field = JavaPlugin.class.getDeclaredField(name);
        field.setAccessible(true);
        field.set(plugin, value);
    }

    public static void main(String[] args) throws Exception {
        Path root = Files.createTempDirectory("plugin-config-");
        try (var resources = new URLClassLoader(new java.net.URL[]{
                Path.of("src/main/resources").toUri().toURL()}, null)) {
            Class<?> unsafeClass = Class.forName("sun.misc.Unsafe");
            var singleton = unsafeClass.getDeclaredField("theUnsafe");
            singleton.setAccessible(true);
            var plugin = (CraftEnginePackOptimizer)unsafeClass.getMethod("allocateInstance", Class.class)
                    .invoke(singleton.get(null), CraftEnginePackOptimizer.class);
            Path data = root.resolve("CraftEnginePackOptimizer");
            field(plugin, "dataFolder", data.toFile());
            field(plugin, "configFile", data.resolve("config.yml").toFile());
            field(plugin, "classLoader", resources);
            plugin.onLoad();
            if (!Files.readString(data.resolve("config.yml")).equals(Files.readString(Path.of("src/main/resources/config.yml"))))
                throw new AssertionError("onLoad did not create the bundled default config");
            Files.writeString(data.resolve("config.yml"), "pack: custom-host\nnamespace-state-directory: custom-state\n");
            plugin.saveDefaultConfig();
            if (!Files.readString(data.resolve("config.yml")).contains("pack: custom-host"))
                throw new AssertionError("existing user configuration was overwritten");
        } finally {
            try (var files = Files.walk(root)) {
                for (Path file : files.sorted(Comparator.reverseOrder()).toList()) Files.delete(file);
            }
        }
        System.out.println("First-load configuration checks passed: 2");
    }
}
