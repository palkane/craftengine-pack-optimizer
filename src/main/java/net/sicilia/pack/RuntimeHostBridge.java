package net.sicilia.pack;

import java.lang.reflect.Field;
import java.nio.file.Path;
import java.util.*;
import net.momirealms.craftengine.core.pack.AbstractPackManager;
import net.momirealms.craftengine.core.pack.host.*;
import net.momirealms.craftengine.core.plugin.CraftEngine;
import net.momirealms.craftengine.core.plugin.config.*;
import net.momirealms.craftengine.core.util.YamlUtils;

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
        if (Config.obfuscateItemModel())
            throw new IllegalStateException("Native item-model obfuscation must remain disabled for translation");
        var original = current.get("default");
        if (!needsTranslation(original, plugin.strictType())) return;
        var section = ConfigSection.of("resource-pack.packs.default",
                YamlUtils.reader(Config.instance().settings()).getValue("resource-pack.packs.default")).copy();
        section.put("namespace-state-directory",
                Path.of("../shared-plugin-sync/state/ce-strict-default").toAbsolutePath().normalize().toString());
        var adapter = new StrictNamespaceHost(plugin, "default", section, original);
        adapter.restoreMappings();
        var replacement = new LinkedHashMap<>(current);
        replacement.put("default", adapter);
        manager = packs;
        previous = current;
        previousGroup = (ResourcePackHost)group.get(packs);
        installed = Collections.unmodifiableMap(replacement);
        installedGroup = new ResourcePackHostGroup(installed.values());
        hosts.set(packs, installed);
        group.set(packs, installedGroup);
        plugin.getLogger().info("Default S3 host translated in memory; source YAML and generated ZIP unchanged.");
    }

    static boolean needsTranslation(ResourcePackHost host, ResourcePackHostType<?> strictType) {
        if (host != null && host.type() == strictType) return false;
        if (host == null || host.type() != ResourcePackHosts.S3)
            throw new IllegalStateException("Transparent translation requires CE default S3 hosting");
        return true;
    }

    void verify() throws Exception {
        var packs = CraftEngine.instance().packManager();
        var named = packs.resourcePackHosts().get("default");
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
