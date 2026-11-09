package net.sicilia.pack;

import com.github.retrooper.packetevents.protocol.nbt.*;
import java.util.*;

/** Only presentation fields are translated. Text, commands, gameplay IDs and custom data stay intact. */
public final class ClientResourceMappings {
    private final Map<String, Map<String, String>> mappings;
    private final ClientResourceMappings inverse;

    public ClientResourceMappings(Map<String, Map<String, String>> mappings) {
        this.mappings = freeze(mappings);
        Map<String, Map<String, String>> reverse = new HashMap<>();
        this.mappings.forEach((type, values) -> {
            Map<String, String> ids = new HashMap<>();
            values.forEach((from, to) -> {
                String previous = ids.put(to, from);
                if (previous != null && !previous.equals(from))
                    throw new IllegalArgumentException("Ambiguous reverse resource mapping: " + type + "/" + to);
                if (!from.equals(to) && values.containsKey(to))
                    throw new IllegalArgumentException("Resource mapping is not idempotent: " + type + "/" + to);
            });
            reverse.put(type, ids);
        });
        inverse = new ClientResourceMappings(freeze(reverse), this);
    }

    private ClientResourceMappings(Map<String, Map<String, String>> values, ClientResourceMappings inverse) {
        this.mappings = values;
        this.inverse = inverse;
    }

    private static Map<String, Map<String, String>> freeze(Map<String, Map<String, String>> values) {
        Map<String, Map<String, String>> result = new HashMap<>();
        values.forEach((type, ids) -> result.put(type, Map.copyOf(ids)));
        return Map.copyOf(result);
    }

    public ClientResourceMappings inverse() { return inverse; }

    public String id(String type, String value) {
        return mappings.getOrDefault(type, Map.of()).getOrDefault(value, value);
    }

    public NBT rewrite(NBT source) {
        return rewrite(source, "");
    }

    private static boolean opaque(String key) {
        return Set.of("minecraft:custom_data", "custom_data", "click_event", "clickEvent", "action",
                "command", "custom_click_action").contains(key);
    }

    private static String context(String field, String key) {
        return field.equals("font") && key.equals("id") ? "font"
                : (field.equals("equip_sound") || field.equals("shearing_sound")) && key.equals("sound_id")
                ? "sound"
                : (field.equals("minecraft:equippable") || field.equals("equippable")) && key.equals("asset_id")
                ? "equipment" : key;
    }

    private String translate(String field, String value) {
        return switch (field) {
            case "font" -> id("font", value);
            case "minecraft:item_model" -> id("items", value);
            case "equipment" -> id("equipment", value);
            case "sprite" -> id("sprites", value);
            case "camera_overlay", "minecraft:camera_overlay" -> id("textures", value);
            case "atlas" -> id("atlases", value);
            case "equip_sound", "shearing_sound", "sound" -> id("sound", value);
            default -> value;
        };
    }

    public net.momirealms.craftengine.libraries.nbt.Tag rewriteNative(
            net.momirealms.craftengine.libraries.nbt.Tag source) {
        return rewriteNative(source, "");
    }

    private net.momirealms.craftengine.libraries.nbt.Tag rewriteNative(
            net.momirealms.craftengine.libraries.nbt.Tag source, String field) {
        if (source instanceof net.momirealms.craftengine.libraries.nbt.CompoundTag compound) {
            var result = new net.momirealms.craftengine.libraries.nbt.CompoundTag();
            for (var entry : compound.entrySet())
                result.put(entry.getKey(), opaque(entry.getKey()) ? entry.getValue().copy()
                        : rewriteNative(entry.getValue(), context(field, entry.getKey())));
            return result;
        }
        if (source instanceof net.momirealms.craftengine.libraries.nbt.ListTag list) {
            var result = new net.momirealms.craftengine.libraries.nbt.ListTag();
            for (var child : list) result.add(rewriteNative(child, field));
            return result;
        }
        if (source instanceof net.momirealms.craftengine.libraries.nbt.StringTag string)
            return new net.momirealms.craftengine.libraries.nbt.StringTag(translate(field, string.value()));
        return source.copy();
    }

    @SuppressWarnings({"rawtypes", "unchecked"})
    private NBT rewrite(NBT source, String field) {
        if (source instanceof NBTCompound compound) {
            NBTCompound result = new NBTCompound();
            for (var entry : compound.getTags().entrySet()) {
                String key = entry.getKey();
                if (opaque(key)) {
                    result.setTag(key, entry.getValue().copy());
                    continue;
                }
                result.setTag(key, rewrite(entry.getValue(), context(field, key)));
            }
            return result;
        }
        if (source instanceof NBTList<?> list) {
            NBTList result = new NBTList(list.getTagsType());
            for (NBT child : list.getTags()) result.addTag(rewrite(child, field));
            return result;
        }
        if (source instanceof NBTString string) {
            String value = string.getValue();
            String updated = translate(field, value);
            return updated.equals(value) ? source.copy() : new NBTString(updated);
        }
        return source.copy();
    }
}
