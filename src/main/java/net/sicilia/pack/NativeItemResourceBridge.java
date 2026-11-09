package net.sicilia.pack;

import java.lang.reflect.Field;
import java.util.Optional;
import net.momirealms.craftengine.bukkit.item.BukkitItemManager;
import net.momirealms.craftengine.core.entity.player.Player;
import net.momirealms.craftengine.core.item.Item;
import net.momirealms.craftengine.core.item.network.*;
import net.momirealms.craftengine.libraries.nbt.CompoundTag;

/** Also used by CE's hashed-stack comparison, not only by outgoing packet encoding. */
final class NativeItemResourceBridge implements NetworkItemHandler {
    private final BukkitItemManager manager;
    private final NetworkItemHandler delegate;
    private final ClientResourceBridge resources;
    private final Field field;

    private NativeItemResourceBridge(BukkitItemManager manager, ClientResourceBridge resources) throws Exception {
        this.manager = manager;
        this.resources = resources;
        field = BukkitItemManager.class.getDeclaredField("networkItemHandler");
        field.setAccessible(true);
        delegate = manager.networkItemHandler();
    }

    static NativeItemResourceBridge attach(ClientResourceBridge resources) throws Exception {
        var manager = BukkitItemManager.instance();
        if (manager.networkItemHandler() instanceof NativeItemResourceBridge existing) return existing;
        var result = new NativeItemResourceBridge(manager, resources);
        result.field.set(manager, result);
        if (manager.networkItemHandler() != result) throw new IllegalStateException("CE item translation not installed");
        return result;
    }

    void detach() throws Exception {
        if (manager.networkItemHandler() == this) field.set(manager, delegate);
    }

    private Optional<Item> rewrite(Item item, ClientResourceMappings map, Optional<Item> previous) {
        if (map == null || item.isEmpty()) return previous;
        CompoundTag before = item.toNBT();
        CompoundTag after = (CompoundTag)map.rewriteNative(before);
        return before.equals(after) ? previous : Optional.of(manager.fromNBT(after));
    }

    @Override public Optional<Item> s2c(Item item, Player player, ItemPacketSource source) {
        Optional<Item> translated = delegate.s2c(item, player, source);
        return rewrite(translated.orElse(item), player == null ? resources.globalMappings()
                : resources.selectMappings(player.uuid()), translated);
    }

    @Override public Optional<Item> c2s(Item item) {
        ClientResourceMappings map = resources.globalMappings();
        Optional<Item> restored = rewrite(item, map == null ? null : map.inverse(), Optional.empty());
        Optional<Item> nativeResult = delegate.c2s(restored.orElse(item));
        return nativeResult.isPresent() ? nativeResult : restored;
    }
}
