package io.github.palkane.cepackoptimizer;

import com.google.gson.*;
import com.github.retrooper.packetevents.protocol.nbt.*;
import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Only presentation fields are translated. Text, commands, gameplay IDs and custom data stay intact. */
public final class ClientResourceMappings {
    private final Map<String, Map<String, String>> mappings;
    private final ClientResourceMappings inverse;
    private static final Pattern JSON_STRING = Pattern.compile("\"(?:\\\\.|[^\"\\\\])*\"");
    private record TextEdit(int start, int end, String value) {}

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
        String target = mappings.getOrDefault(type, Map.of()).get(value);
        // 1.21.2-1.21.3 item_model IDs resolve models, before the separate items definitions.
        if (target == null && type.equals("items"))
            target = mappings.getOrDefault("models", Map.of()).get(value);
        return target == null ? value : target;
    }

    public NBT rewrite(NBT source) {
        return rewrite(source, "");
    }

    NBTCompound rewriteLegacyItem(NBTCompound source) {
        if (mappings.getOrDefault("font", Map.of()).isEmpty()) return source;
        NBTCompound result = source.copy();
        if (result.getTags().get("display") instanceof NBTCompound display) {
            if (display.getTags().get("Name") instanceof NBTString name)
                display.setTag("Name", new NBTString(rewriteLegacyText(name.getValue())));
            if (display.getTags().get("Lore") instanceof NBTList<?> lore) {
                NBTList<NBTString> lines = NBTList.createStringList();
                for (NBT value : lore.getTags()) {
                    if (!(value instanceof NBTString text)) return source;
                    lines.addTag(new NBTString(rewriteLegacyText(text.getValue())));
                }
                display.setTag("Lore", lines);
            }
        }
        rewriteLegacyContents(result, "Items");
        rewriteLegacyContents(result, "ChargedProjectiles");
        if (result.getTags().get("BlockEntityTag") instanceof NBTCompound block)
            rewriteLegacyContents(block, "Items");
        return result.equals(source) ? source : result;
    }

    private void rewriteLegacyContents(NBTCompound tag, String key) {
        if (!(tag.getTags().get(key) instanceof NBTList<?> contents)) return;
        for (NBT child : contents.getTags()) {
            if (child instanceof NBTCompound item && item.getTags().get("tag") instanceof NBTCompound data)
                item.setTag("tag", rewriteLegacyItem(data));
        }
    }

    private String rewriteLegacyText(String source) {
        try {
            JsonElement parsed = JsonParser.parseString(source);
            Matcher strings = JSON_STRING.matcher(source);
            List<TextEdit> edits = new ArrayList<>();
            legacyTextEdits(parsed, "", true, strings, edits);
            if (edits.isEmpty() || strings.find()) return source;
            StringBuilder result = new StringBuilder(source.length());
            int start = 0;
            for (TextEdit edit : edits) {
                result.append(source, start, edit.start).append(edit.value);
                start = edit.end;
            }
            return result.append(source, start, source.length()).toString();
        } catch (JsonParseException | IllegalArgumentException e) {
            return source;
        }
    }

    private void legacyTextEdits(JsonElement source, String field, boolean allowed,
                                 Matcher strings, List<TextEdit> edits) {
        if (source.isJsonArray()) {
            for (JsonElement value : source.getAsJsonArray()) legacyTextEdits(value, field, allowed, strings, edits);
        } else if (source.isJsonObject()) {
            for (var entry : source.getAsJsonObject().entrySet()) {
                nextJsonString(strings, entry.getKey());
                legacyTextEdits(entry.getValue(), entry.getKey(), allowed && !opaque(entry.getKey()), strings, edits);
            }
        } else if (source.isJsonPrimitive() && source.getAsJsonPrimitive().isString()) {
            String value = source.getAsString();
            nextJsonString(strings, value);
            String target = allowed && field.equals("font") ? id("font", value) : value;
            if (!value.equals(target))
                edits.add(new TextEdit(strings.start(), strings.end(), new JsonPrimitive(target).toString()));
        }
    }

    private static void nextJsonString(Matcher strings, String expected) {
        if (!strings.find() || !JsonParser.parseString(strings.group()).getAsString().equals(expected))
            throw new IllegalArgumentException("Ambiguous legacy text JSON");
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
