package net.sicilia.pack;

import com.google.gson.GsonBuilder;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.HashMap;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;
import net.momirealms.craftengine.core.pack.workflow.*;
import net.momirealms.craftengine.core.pack.host.*;
import net.momirealms.craftengine.core.util.Key;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import net.momirealms.craftengine.bukkit.api.event.CraftEngineReloadEvent;

public final class CraftEnginePackOptimizer extends JavaPlugin implements Listener {
    private PackWorkflowType<SlimWorkflow> workflowType;
    private PackWorkflowType<FontWorkflow> fontWorkflowType;
    private PackWorkflowType<CandidateWorkflow> candidateWorkflowType;
    private PackWorkflowType<CompatibilityWorkflow> compatibilityWorkflowType;
    private PackWorkflowType<BindWorkflow> bindWorkflowType;
    private ResourcePackHostType<NamespaceShardHost> shardType;
    private NamespaceShardHost compatibilityHost;
    private NamespaceCompatibility.Result pendingNamespace;
    private PackWorkflowType<StrictWorkflow> strictWorkflowType;
    private ResourcePackHostType<StrictNamespaceHost> strictType;
    private StrictNamespaceHost strictHost;
    private ClientResourceBridge bridge;
    private NativeItemResourceBridge nativeItems;
    private RuntimeHostBridge runtimeHost;
    private Map<Key, Key> previousItemMappings;
    private volatile Map<String, Map<String, String>> strictMappings = Map.of();

    @Override
    public void onLoad() {
        workflowType = PackWorkflows.register(Key.of("sicilia:slim"), section -> new SlimWorkflow());
        fontWorkflowType = PackWorkflows.register(Key.of("sicilia:font_merge"), section -> new FontWorkflow());
        candidateWorkflowType = PackWorkflows.register(Key.of("sicilia:namespace_candidate"),
                section -> new CandidateWorkflow());
        compatibilityWorkflowType = PackWorkflows.register(Key.of("sicilia:namespace_merge"),
                section -> new CompatibilityWorkflow());
        bindWorkflowType = PackWorkflows.register(Key.of("sicilia:namespace_bind"), section -> new BindWorkflow());
        shardType = ResourcePackHosts.register(Key.of("sicilia:shards"),
                (id, section) -> new NamespaceShardHost(this, id, section));
        strictWorkflowType = PackWorkflows.register(Key.of("sicilia:namespace_strict"), section -> new StrictWorkflow());
        strictType = ResourcePackHosts.register(Key.of("sicilia:strict"),
                (id, section) -> new StrictNamespaceHost(this, id, section));
    }

    ResourcePackHostType<NamespaceShardHost> shardType() { return shardType; }
    void compatibilityHost(NamespaceShardHost host) { compatibilityHost = host; }
    ResourcePackHostType<StrictNamespaceHost> strictType() { return strictType; }
    void strictHost(StrictNamespaceHost host) { strictHost = host; }

    void installStrictMappings(Map<String, Map<String, String>> mappings) {
        if (previousItemMappings == null)
            previousItemMappings = net.momirealms.craftengine.core.item.network.ItemModelMappings.getMappings();
        new ClientResourceMappings(mappings);
        Map<Key, Key> items = new HashMap<>();
        mappings.getOrDefault("items", Map.of()).forEach((a, b) -> items.put(Key.of(a), Key.of(b)));
        net.momirealms.craftengine.core.item.network.ItemModelMappings.setMappings(Map.copyOf(items));
        if (bridge != null) bridge.install(mappings);
        strictMappings = mappings;
    }

    @Override
    public void onEnable() {
        saveDefaultConfig();
        getServer().getPluginManager().registerEvents(this, this);
        bridge = new ClientResourceBridge(getLogger());
        com.github.retrooper.packetevents.PacketEvents.getAPI().getEventManager().registerListener(bridge);
        try {
            nativeItems = NativeItemResourceBridge.attach(bridge);
            runtimeHost = new RuntimeHostBridge(this);
            runtimeHost.attach();
        } catch (Exception e) {
            throw new IllegalStateException("Cannot attach transparent resource translation", e);
        }
        getCommand("cepackpreview").setExecutor((sender, command, label, args) -> {
            if (sender instanceof org.bukkit.entity.Player) {
                sender.sendMessage("Console only.");
                return true;
            }
            if (args.length == 1 && args[0].equals("status")) {
                sender.sendMessage(bridge.status());
                return true;
            }
            if (args.length == 1 && args[0].equals("verify-host")) {
                try {
                    runtimeHost.verify();
                    sender.sendMessage("PASS strict default upload/download and grouped download routing; " + bridge.status());
                } catch (Exception e) {
                    sender.sendMessage("FAIL strict host routing: " + e.getMessage());
                }
                return true;
            }
            if (args.length == 1 && args[0].equals("verify")) {
                try {
                    var mapping = strictMappings.getOrDefault("items", Map.of()).entrySet().stream()
                            .filter(e -> !e.getKey().equals(e.getValue())).findFirst().orElseThrow();
                    var manager = net.momirealms.craftengine.bukkit.item.BukkitItemManager.instance();
                    var sample = manager.wrap(new org.bukkit.inventory.ItemStack(org.bukkit.Material.PAPER, 3));
                    sample.itemModel(mapping.getKey());
                    var original = sample.toNBT();
                    var sent = manager.s2c(sample.copy(), null,
                            net.momirealms.craftengine.core.item.network.ItemPacketSource.GENERIC).orElseThrow();
                    if (!sent.itemModel().orElseThrow().equals(mapping.getValue()) || !sample.toNBT().equals(original))
                        throw new IllegalStateException("Native outgoing mapping/source isolation failed");
                    var restored = manager.c2s(sent.copy()).orElseThrow();
                    if (!restored.toNBT().equals(original))
                        throw new IllegalStateException("Native item roundtrip changed components");
                    sender.sendMessage("PASS native CE item s2c/c2s, count/components and source isolation; " + bridge.status());
                } catch (Exception e) {
                    sender.sendMessage("FAIL native resource check: " + e.getClass().getSimpleName() + ": " + e.getMessage());
                }
                return true;
            }
            if (args.length != 2 || !(args[0].equals("start") || args[0].equals("stop"))) {
                sender.sendMessage("cepackpreview start|stop <online-player>, or status|verify|verify-host");
                return true;
            }
            var player = getServer().getPlayerExact(args[1]);
            if (player == null) { sender.sendMessage("Player is not online."); return true; }
            if (args[0].equals("stop")) {
                bridge.stopPreview(player.getUniqueId());
                sender.sendMessage("Preview mappings removed; reconnect before using the normal pack.");
                return true;
            }
            try {
                var result = new com.google.gson.Gson().fromJson(
                        Files.readString(getDataFolder().toPath().resolve("strict-preview.json")),
                        StrictNamespacePack.Result.class);
                StrictNamespaceHost.validate(getDataFolder().toPath().resolve("strict-preview.zip"), result);
                bridge.preview(player.getUniqueId(), result.merge().runtimeIds());
                sender.sendMessage("Preview mappings enabled for " + player.getName()
                        + " only; no pack upload or global item-model mapping changed.");
            } catch (Exception e) { sender.sendMessage("Preview rejected: " + e.getMessage()); }
            return true;
        });
        if (compatibilityHost != null) compatibilityHost.restoreMappings();
        if (strictHost != null) strictHost.restoreMappings();
        getLogger().info("Registered font_merge and post-protection namespace_strict; legacy namespace_merge is retired. Source resources are never modified.");
    }

    @EventHandler public void onQuit(org.bukkit.event.player.PlayerQuitEvent event) {
        if (bridge != null) bridge.stopPreview(event.getPlayer().getUniqueId());
    }

    // Install inside the normal-priority upload notification proxy, not around it.
    @EventHandler(priority = EventPriority.LOWEST)
    public void onCraftEngineReload(CraftEngineReloadEvent event) {
        try { if (runtimeHost != null) runtimeHost.attach(); }
        catch (Exception e) { throw new IllegalStateException("Cannot reattach transparent resource translation", e); }
        if (compatibilityHost != null) compatibilityHost.restoreMappings();
        if (strictHost != null) strictHost.restoreMappings();
    }

    @Override public void onDisable() {
        if (bridge != null)
            com.github.retrooper.packetevents.PacketEvents.getAPI().getEventManager().unregisterListener(bridge);
        try {
            if (runtimeHost != null) runtimeHost.detach();
            if (nativeItems != null) nativeItems.detach();
        } catch (Exception e) { getLogger().warning("Resource translation detach failed: " + e.getClass().getSimpleName()); }
        if (previousItemMappings != null)
            net.momirealms.craftengine.core.item.network.ItemModelMappings.setMappings(previousItemMappings);
    }

    private final class StrictWorkflow implements PackWorkflow {
        @Override public PackWorkflowType<StrictWorkflow> type() { return strictWorkflowType; }
        @Override public void validate(PackWorkflowValidation validation) {
            NamespaceCompatibility.validateBinding(validation);
        }
        @Override public void execute(PackWorkflowContext context) throws Exception {
            Path directory = Files.createTempDirectory(getDataFolder().toPath(), "strict-work-");
            try {
                var result = StrictNamespacePack.build(context.resolvePath("./generated/resource_pack.zip"),
                        context.resolvePath("./generated/resource_pack_strict.zip"), directory.resolve("pack"));
                StrictNamespaceHost.validate(context.resolvePath("./generated/resource_pack_strict.zip"), result);
                Files.writeString(getDataFolder().toPath().resolve("strict-binding.json"),
                        new GsonBuilder().setPrettyPrinting().create().toJson(result));
                var readable = new org.bukkit.configuration.file.YamlConfiguration();
                readable.set("pack-sha1", result.outputSha1());
                readable.set("resources", result.merge().runtimeIds());
                readable.save(getDataFolder().toPath().resolve("client-resource-mappings.yml").toFile());
                getLogger().info("Strict two-namespace pack prepared after native protection; "
                        + "archive tricks normalized, native asset obfuscation retained.");
            } finally {
                try (var paths = Files.walk(directory)) {
                    for (Path path : paths.sorted(java.util.Comparator.reverseOrder()).toList()) Files.delete(path);
                }
            }
        }
    }

    private final class CompatibilityWorkflow implements PackWorkflow {
        @Override public PackWorkflowType<CompatibilityWorkflow> type() { return compatibilityWorkflowType; }
        @Override public void validate(PackWorkflowValidation validation) {
            throw new IllegalArgumentException("Legacy namespace_merge caused a rendering regression; use namespace_strict after protected ZIP packaging");
        }
        @Override public void execute(PackWorkflowContext context) throws Exception {
            pendingNamespace = null;
            var config = org.bukkit.configuration.file.YamlConfiguration.loadConfiguration(
                    Path.of("plugins/CraftEngine/config.yml").toFile());
            if (config.getBoolean("resource-pack.protection.obfuscation.item-model.enable"))
                throw new IllegalStateException("namespace_merge requires native item-model obfuscation disabled");
            pendingNamespace = NamespaceCompatibility.prepare(context.generatedPackPath(), getConfig().getStringList("keep"));
            Files.createDirectories(getDataFolder().toPath());
            Files.writeString(getDataFolder().toPath().resolve("last-namespace-report.json"),
                    new GsonBuilder().setPrettyPrinting().create().toJson(pendingNamespace));
            getLogger().info("Namespace merge prepared: " + pendingNamespace.merge().namespacesBefore().size()
                    + " -> 2 large-pack namespaces, " + pendingNamespace.aliases()
                    + " compatibility files, " + pendingNamespace.spriteAliases() + " atlas sprite aliases.");
        }
    }

    private final class BindWorkflow implements PackWorkflow {
        @Override public PackWorkflowType<BindWorkflow> type() { return bindWorkflowType; }
        @Override public void validate(PackWorkflowValidation validation) { NamespaceCompatibility.validateBinding(validation); }
        @Override public void execute(PackWorkflowContext context) throws Exception {
            if (pendingNamespace == null) throw new IllegalStateException("namespace_merge must run before namespace_bind");
            String hash = NamespaceShards.sha1(Files.readAllBytes(context.resolvePath("./generated/resource_pack.zip")));
            Files.writeString(getDataFolder().toPath().resolve("namespace-binding.json"),
                    new GsonBuilder().setPrettyPrinting().create().toJson(
                            new NamespaceCompatibility.Binding(hash, pendingNamespace)));
        }
    }

    private final class CandidateWorkflow implements PackWorkflow {
        @Override
        public PackWorkflowType<CandidateWorkflow> type() { return candidateWorkflowType; }

        @Override
        public void validate(PackWorkflowValidation validation) { validation.requireGeneratedPack(); }

        @Override
        public void execute(PackWorkflowContext context) throws Exception {
            Files.createDirectories(getDataFolder().toPath());
            Path directory = Files.createTempDirectory(getDataFolder().toPath(), "namespace-candidate-");
            Path copy = directory.resolve("pack");
            Path source = context.generatedPackPath();
            try (var paths = Files.walk(source)) {
                for (Path path : paths.toList()) {
                    if (!Files.isRegularFile(path)) continue;
                    Path target = copy.resolve(source.relativize(path).toString());
                    Files.createDirectories(target.getParent());
                    Files.copy(path, target);
                }
            }
            NamespaceMerger.Result result = NamespaceMerger.optimize(copy);
            Files.writeString(directory.resolve("report.json"),
                    new GsonBuilder().setPrettyPrinting().create().toJson(result));
            try (var zip = new ZipOutputStream(Files.newOutputStream(directory.resolve("candidate.zip")));
                 var paths = Files.walk(copy)) {
                for (Path path : paths.filter(Files::isRegularFile).sorted().toList()) {
                    zip.putNextEntry(new ZipEntry(copy.relativize(path).toString().replace('\\', '/')));
                    Files.copy(path, zip);
                    zip.closeEntry();
                }
            }
            getLogger().info("Offline namespace candidate exported to " + directory
                    + "; generated staging pack unchanged, runtime ID bridge not installed.");
        }
    }

    private final class FontWorkflow implements PackWorkflow {
        @Override
        public PackWorkflowType<FontWorkflow> type() { return fontWorkflowType; }

        @Override
        public void validate(PackWorkflowValidation validation) { validation.requireGeneratedPack(); }

        @Override
        public void execute(PackWorkflowContext context) throws Exception {
            FontProviderOptimizer.Result result = FontProviderOptimizer.optimize(context.generatedPackPath());
            Path report = getDataFolder().toPath().resolve("last-font-report.json");
            Files.createDirectories(report.getParent());
            Files.writeString(report, new GsonBuilder().setPrettyPrinting().create().toJson(result));
            getLogger().info(result.summary());
        }
    }

    private final class SlimWorkflow implements PackWorkflow {
        @Override
        public PackWorkflowType<SlimWorkflow> type() { return workflowType; }

        @Override
        public void validate(PackWorkflowValidation validation) { validation.requireGeneratedPack(); }

        @Override
        public void execute(PackWorkflowContext context) throws Exception {
            Path root = context.generatedPackPath();
            boolean flatten = getConfig().getBoolean("flatten-exact-version-overlays", true);
            List<String> keep = getConfig().getStringList("keep");
            PackOptimizer.Result result = PackOptimizer.optimize(root, flatten, keep);
            Path report = getDataFolder().toPath().resolve("last-report.json");
            Files.createDirectories(report.getParent());
            Files.writeString(report, new GsonBuilder().setPrettyPrinting().create().toJson(result));
            getLogger().info(result.summary());
        }
    }
}
