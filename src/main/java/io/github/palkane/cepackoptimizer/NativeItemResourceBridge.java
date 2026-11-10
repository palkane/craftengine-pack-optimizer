package io.github.palkane.cepackoptimizer;

import java.lang.reflect.*;
import java.util.Optional;
import net.momirealms.craftengine.bukkit.item.BukkitItemManager;
import net.momirealms.craftengine.core.entity.player.Player;
import net.momirealms.craftengine.core.item.Item;
import net.momirealms.craftengine.core.item.network.*;
import net.momirealms.craftengine.libraries.nbt.CompoundTag;

/** Also used by CE's hashed-stack comparison, not only by outgoing packet encoding. */
final class NativeItemResourceBridge implements InvocationHandler {
    private final BukkitItemManager manager;
    private final NetworkItemHandler delegate;
    private final ClientResourceBridge resources;
    private final Field field;
    private final NetworkItemHandler proxy;

    private NativeItemResourceBridge(BukkitItemManager manager, ClientResourceBridge resources) throws Exception {
        this.manager = manager;
        this.resources = resources;
        field = BukkitItemManager.class.getDeclaredField("networkItemHandler");
        field.setAccessible(true);
        delegate = manager.networkItemHandler();
        proxy = (NetworkItemHandler)Proxy.newProxyInstance(NetworkItemHandler.class.getClassLoader(),
                new Class<?>[]{NetworkItemHandler.class}, this);
    }

    static NativeItemResourceBridge attach(ClientResourceBridge resources) throws Exception {
        var manager = BukkitItemManager.instance();
        NetworkItemHandler current = manager.networkItemHandler();
        if (Proxy.isProxyClass(current.getClass())
                && Proxy.getInvocationHandler(current) instanceof NativeItemResourceBridge existing) return existing;
        var result = new NativeItemResourceBridge(manager, resources);
        result.field.set(manager, result.proxy);
        if (manager.networkItemHandler() != result.proxy) throw new IllegalStateException("CE item translation not installed");
        return result;
    }

    void detach() throws Exception {
        if (manager.networkItemHandler() == proxy) field.set(manager, delegate);
    }

    private Optional<Item> rewrite(Item item, ClientResourceMappings map, Optional<Item> previous) {
        if (map == null || item.isEmpty()) return previous;
        CompoundTag before = item.toNBT();
        CompoundTag after = (CompoundTag)map.rewriteNative(before);
        return before.equals(after) ? previous : Optional.of(manager.fromNBT(after));
    }

    @Override
    @SuppressWarnings("unchecked")
    public Object invoke(Object receiver, Method method, Object[] args) throws Throwable {
        if (method.getDeclaringClass() == Object.class) {
            return switch (method.getName()) {
                case "equals" -> receiver == args[0];
                case "hashCode" -> System.identityHashCode(receiver);
                case "toString" -> "Resource translation for " + delegate;
                default -> throw new IllegalStateException("Unexpected Object method");
            };
        }
        try {
            if (method.getName().equals("s2c")) {
                Item item = (Item)args[0];
                Optional<Item> translated = (Optional<Item>)method.invoke(delegate, args);
                Player player = (Player)args[1];
                return rewrite(translated.orElse(item), player == null ? resources.globalMappings()
                        : resources.selectMappings(player.uuid()), translated);
            }
            if (method.getName().equals("c2s")) {
                Item item = (Item)args[0];
                ClientResourceMappings map = resources.globalMappings();
                Optional<Item> restored = rewrite(item, map == null ? null : map.inverse(), Optional.empty());
                args[0] = restored.orElse(item);
                Optional<Item> nativeResult = (Optional<Item>)method.invoke(delegate, args);
                return nativeResult.isPresent() ? nativeResult : restored;
            }
            return method.invoke(delegate, args);
        } catch (InvocationTargetException e) {
            throw e.getCause();
        }
    }
}
