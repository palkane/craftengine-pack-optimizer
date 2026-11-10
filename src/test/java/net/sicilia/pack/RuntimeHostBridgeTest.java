package net.sicilia.pack;

import java.lang.reflect.Proxy;
import java.nio.file.Path;
import java.util.*;
import java.util.concurrent.CompletableFuture;
import net.momirealms.craftengine.bukkit.api.event.CraftEngineReloadEvent;
import net.momirealms.craftengine.core.pack.host.*;
import net.momirealms.craftengine.core.plugin.network.NetWorkUser;
import net.momirealms.craftengine.core.util.Key;
import org.bukkit.event.*;
import org.bukkit.plugin.*;

/** Real Bukkit event ordering and host proxies, with recording endpoints instead of S3 IO. */
public final class RuntimeHostBridgeTest {
    private static int checks;
    private static final List<String> calls = new ArrayList<>();
    private static final ResourcePackHostType<Endpoint> STRICT =
            new ResourcePackHostType<>(Key.of("test:strict"), null);

    private static void check(boolean value, String message) {
        checks++;
        if (!value) throw new AssertionError(message);
    }

    private record Endpoint(boolean strict) implements ResourcePackHost {
        public CompletableFuture<Void> upload(Path source) {
            calls.add(strict ? "strict-upload" : "ordinary-upload");
            return CompletableFuture.completedFuture(null);
        }
        public CompletableFuture<List<ResourcePackDownloadData>> requestResourcePackDownloadLink(NetWorkUser user) {
            calls.add(strict ? "strict-download" : "ordinary-download");
            return CompletableFuture.completedFuture(List.of());
        }
        public boolean canUpload() { return true; }
        public ResourcePackHostType<?> type() { return strict ? STRICT : ResourcePackHosts.S3; }
    }

    private static ResourcePackHost synced(ResourcePackHost host) {
        return (ResourcePackHost)Proxy.newProxyInstance(ResourcePackHost.class.getClassLoader(),
                new Class<?>[]{ResourcePackHost.class}, (proxy, method, args) -> {
                    Object result = method.invoke(host, args);
                    if (method.getName().equals("upload"))
                        ((CompletableFuture<?>)result).whenComplete((value, error) -> {
                            if (error == null) calls.add("notified");
                        });
                    return result;
                });
    }

    public static void main(String[] args) throws Exception {
        check(RuntimeHostBridge.needsTranslation(new Endpoint(false), STRICT), "native S3 not accepted");
        check(RuntimeHostBridge.needsTranslation(synced(new Endpoint(false)), STRICT), "S3 proxy not accepted");
        check(!RuntimeHostBridge.needsTranslation(synced(new Endpoint(true)), STRICT),
                "already translated proxy would be replaced and lose notifications");
        try {
            RuntimeHostBridge.needsTranslation(null, STRICT);
            throw new AssertionError("missing host accepted");
        } catch (IllegalStateException expected) { checks++; }

        EventPriority priority = CraftEnginePackOptimizer.class
                .getMethod("onCraftEngineReload", CraftEngineReloadEvent.class)
                .getAnnotation(EventHandler.class).priority();
        ResourcePackHost[] named = {new Endpoint(false)}, grouped = {named[0]};
        HandlerList handlers = new HandlerList();
        Listener listener = new Listener() {};
        handlers.register(new RegisteredListener(listener, (ignored, event) -> {
            if (RuntimeHostBridge.needsTranslation(named[0], STRICT)) {
                named[0] = new Endpoint(true);
                grouped[0] = named[0];
            }
        }, priority, null, false));
        handlers.register(new RegisteredListener(listener, (ignored, event) ->
                named[0] = synced(named[0]), EventPriority.NORMAL, null, false));
        try {
            for (int reload = 0; reload < 3; reload++) {
                named[0] = new Endpoint(false);
                grouped[0] = named[0];
                for (RegisteredListener registration : handlers.getRegisteredListeners())
                    registration.callEvent(new CraftEngineReloadEvent(null));
                calls.clear();
                named[0].upload(Path.of("generated.zip")).join();
                named[0].requestResourcePackDownloadLink(null).join();
                grouped[0].requestResourcePackDownloadLink(null).join();
                check(calls.equals(List.of("strict-upload", "notified", "strict-download", "strict-download")),
                        "reload lost optimized upload, peer notification or download routing: " + calls);
                check(!RuntimeHostBridge.needsTranslation(named[0], STRICT),
                        "repeated attach would replace the active notification proxy");
            }
        } finally {
            handlers.unregister(listener);
            HandlerList.getHandlerLists().remove(handlers);
        }
        System.out.println("Runtime host routing checks passed: " + checks);
    }
}
