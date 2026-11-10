package io.github.palkane.cepackoptimizer;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import net.momirealms.craftengine.core.pack.workflow.PackWorkflowContext;

final class CraftEngineCompatibility {
    private static final Method ZIP_WITH_PNG = zipWithPng();

    private static Method zipWithPng() {
        try {
            return PackWorkflowContext.class.getMethod("zip", String.class, boolean.class, boolean.class);
        } catch (NoSuchMethodException ignored) {
            return null;
        }
    }

    static void requireStorePng(boolean storePng) {
        if (storePng && ZIP_WITH_PNG == null)
            throw new IllegalArgumentException("This CraftEngine version requires store-png: false");
    }

    static void zip(PackWorkflowContext context, String path, boolean storePng) throws Exception {
        requireStorePng(storePng);
        if (!storePng) {
            context.zip(path, true);
            return;
        }
        try {
            ZIP_WITH_PNG.invoke(context, path, true, true);
        } catch (InvocationTargetException e) {
            if (e.getCause() instanceof Exception cause) throw cause;
            if (e.getCause() instanceof Error cause) throw cause;
            throw e;
        }
    }
}
