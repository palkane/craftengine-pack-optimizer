package io.github.palkane.cepackoptimizer;

import java.lang.reflect.Field;
import java.nio.file.Path;
import java.util.*;
import net.momirealms.craftengine.core.pack.AbstractPackManager;
import net.momirealms.craftengine.core.pack.host.*;
import net.momirealms.craftengine.core.plugin.CraftEngine;
import net.momirealms.craftengine.core.plugin.config.*;

/** CE 26.9.2 has host getters but no replacement API. Never edits its YAML or host registry. */
final class RuntimeHostBridge {
    private final CraftEnginePackOptimizer plugin;
    private Object manager;
    private Map<String, ResourcePackHost> previous;
    private Map<String, ResourcePackHost> installed;
    private ResourcePackHost previousGroup;
    private ResourcePackHost installedGroup;
    private final Field hosts;
    private final Field group;
    private String packId;

    RuntimeHostBridge(CraftEnginePackOptimizer plugin) throws Exception {
        this.plugin = plugin;
        hosts = AbstractPackManager.class.getDeclaredField("resourcePackHosts");
        group = AbstractPackManager.class.getDeclaredField("resourcePackHost");
        hosts.setAccessible(true);
        group.setAccessible(true);
    }

    void attach() throws Exception {
        var packs = CraftEngine.instance().packManager();
        var current = packs.resourcePackHosts();
        if (current.isEmpty() || current == installed) return;
        packId = plugin.getConfig().getString("pack", "default");
        var original = current.get(packId);
        if (!needsTranslation(original, plugin.strictType())) return;
        Path state = stateDirectory(plugin.getDataFolder().toPath(),
                plugin.getConfig().getString("namespace-state-directory", "state"));
        if (java.nio.file.Files.exists(state.resolve("current.json")) && Config.obfuscateItemModel())
            throw new IllegalStateException("Native item-model obfuscation must remain disabled for published mappings");
        var adapter = new StrictNamespaceHost(state, original, plugin.strictType(), plugin::installStrictMappings);
        adapter.restoreMappings();
        var replacement = new LinkedHashMap<>(current);
        replacement.put(packId, adapter);
        manager = packs;
        previous = current;
        previousGroup = (ResourcePackHost)group.get(packs);
        installed = Collections.unmodifiableMap(replacement);
        installedGroup = new ResourcePackHostGroup(installed.values());
        hosts.set(packs, installed);
        group.set(packs, installedGroup);
        plugin.strictHost(adapter);
        plugin.getLogger().info("Resource translation attached to CE pack '" + packId
                + "'; native hosting and ZIP bytes retained.");
    }

    static Path stateDirectory(Path dataFolder, String configured) {
        if (configured == null || configured.isBlank())
            throw new IllegalArgumentException("namespace-state-directory must not be empty");
        return dataFolder.resolve(configured).toAbsolutePath().normalize();
    }

    static boolean needsTranslation(ResourcePackHost host, ResourcePackHostType<?> strictType) {
        if (host != null && host.type() == strictType) return false;
        if (host == null)
            throw new IllegalStateException("Configured CE pack was not found; check the optimizer's pack setting");
        return true;
    }

    void verify() throws Exception {
        var packs = CraftEngine.instance().packManager();
        var named = packs.resourcePackHosts().get(packId);
        if (named == null || named.type() != plugin.strictType()
                || group.get(packs) != installedGroup)
            throw new IllegalStateException("Named upload/download or grouped download host lost strict translation");
    }

    void detach() throws Exception {
        if (manager != null && hosts.get(manager) == installed) {
            hosts.set(manager, previous);
            group.set(manager, previousGroup);
        }
    }
}
