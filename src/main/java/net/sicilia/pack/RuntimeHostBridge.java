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
        if (original == null || !original.getClass().getName().equals(
                "net.momirealms.craftengine.core.pack.host.impl.S3Host"))
            throw new IllegalStateException("Transparent translation requires CE default S3 hosting");
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
        hosts.set(packs, installed);
        group.set(packs, new ResourcePackHostGroup(installed.values()));
        plugin.getLogger().info("Default S3 host translated in memory; source YAML and generated ZIP unchanged.");
    }

    void detach() throws Exception {
        if (manager != null && hosts.get(manager) == installed) {
            hosts.set(manager, previous);
            group.set(manager, previousGroup);
        }
    }
}
