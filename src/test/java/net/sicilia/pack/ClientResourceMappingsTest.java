package net.sicilia.pack;

import com.github.retrooper.packetevents.protocol.nbt.*;
import java.util.*;

public final class ClientResourceMappingsTest {
    private static int checks;
    private static void check(boolean value, String message) {
        checks++;
        if (!value) throw new AssertionError(message);
    }
    private static NBTCompound object(Object... pairs) {
        NBTCompound result = new NBTCompound();
        for (int i = 0; i < pairs.length; i += 2)
            result.setTag((String)pairs[i], pairs[i + 1] instanceof NBT n ? n : new NBTString((String)pairs[i + 1]));
        return result;
    }
    public static void main(String[] args) {
        var bridge = new ClientResourceBridge(java.util.logging.Logger.getAnonymousLogger());
        check(true, "installed packet presentation adapters unavailable");
        UUID player = UUID.randomUUID(), other = UUID.randomUUID();
        check(bridge.selectMappings(null) == null && bridge.selectMappings(player) == null,
                "inactive bridge must leave unauthenticated and ordinary connections unchanged");
        bridge.preview(player, Map.of("font", Map.of("menu:text", "custom:preview/text")));
        check(bridge.selectMappings(null) == null && bridge.selectMappings(other) == null,
                "preview must not affect unauthenticated or other connections");
        check(bridge.selectMappings(player).id("font", "menu:text").equals("custom:preview/text"),
                "preview player did not receive preview mapping");
        bridge.install(Map.of("font", Map.of("menu:text", "custom:global/text")));
        check(bridge.selectMappings(null) == null
                        && bridge.selectMappings(other).id("font", "menu:text").equals("custom:global/text")
                        && bridge.selectMappings(player).id("font", "menu:text").equals("custom:preview/text"),
                "global mapping must skip unauthenticated connections and preserve player previews");
        bridge.stopPreview(player);
        check(bridge.selectMappings(player).id("font", "menu:text").equals("custom:global/text"),
                "stopping preview must restore global mapping");
        var mapper = new ClientResourceMappings(Map.of(
                "font", Map.of("menu:text", "custom:menu/text"),
                "sprites", Map.of("menu:icon", "custom:menu/icon"),
                "items", Map.of("demo:item", "custom:demo/item"),
                "equipment", Map.of("demo:armor", "custom:demo/armor")));
        NBTCompound source = object("font", "menu:text", "text", "menu:text",
                "click_event", object("action", "run_command", "command", "/give demo:item"),
                "sprite", "menu:icon", "minecraft:item_model", "demo:item",
                "minecraft:equippable", object("asset_id", "demo:armor"),
                "minecraft:custom_data", object("font", "menu:text", "id", "demo:item"),
                "extra", new NBTList<>(NBTType.COMPOUND, List.of(object("font", object("type", "minecraft:font", "id", "menu:text")))));
        NBTCompound result = (NBTCompound)mapper.rewrite(source);
        check(result.getStringTagValueOrThrow("font").equals("custom:menu/text"), "font not mapped");
        check(result.getStringTagValueOrThrow("text").equals("menu:text"), "literal text changed");
        check(result.getCompoundTagOrThrow("click_event").equals(source.getCompoundTagOrThrow("click_event")), "command changed");
        check(result.getStringTagValueOrThrow("sprite").equals("custom:menu/icon"), "sprite not mapped");
        check(result.getStringTagValueOrThrow("minecraft:item_model").equals("custom:demo/item"), "item model not mapped");
        check(result.getCompoundTagOrThrow("minecraft:equippable").getStringTagValueOrThrow("asset_id").equals("custom:demo/armor"),
                "equipment not mapped");
        check(result.getCompoundTagOrThrow("minecraft:custom_data").equals(source.getCompoundTagOrThrow("minecraft:custom_data")),
                "gameplay data changed");
        check(source.getStringTagValueOrThrow("font").equals("menu:text"), "server-owned object modified");
        check(result.getTagListOfTypeOrThrow("extra", NBTCompound.class).getTag(0)
                .getCompoundTagOrThrow("font").getStringTagValueOrThrow("id").equals("custom:menu/text"), "nested font not mapped");
        check(mapper.rewrite(result).equals(result), "mapping applied twice");
        System.out.println("Client resource mapping checks passed: " + checks);
    }
}
