package io.github.palkane.cepackoptimizer;

import com.github.retrooper.packetevents.event.*;
import com.github.retrooper.packetevents.protocol.dialog.Dialog;
import com.github.retrooper.packetevents.protocol.entity.data.EntityData;
import com.github.retrooper.packetevents.protocol.item.ItemStack;
import com.github.retrooper.packetevents.protocol.nbt.NBT;
import com.github.retrooper.packetevents.protocol.player.Equipment;
import com.github.retrooper.packetevents.protocol.component.*;
import com.github.retrooper.packetevents.protocol.component.builtin.item.*;
import com.github.retrooper.packetevents.protocol.nbt.NBTCompound;
import com.github.retrooper.packetevents.protocol.recipe.data.*;
import com.github.retrooper.packetevents.protocol.sound.*;
import com.github.retrooper.packetevents.resources.ResourceLocation;
import com.github.retrooper.packetevents.util.adventure.AdventureSerializer;
import com.github.retrooper.packetevents.wrapper.PacketWrapper;
import com.github.retrooper.packetevents.wrapper.play.client.WrapperPlayClientCreativeInventoryAction;
import com.github.retrooper.packetevents.wrapper.play.client.WrapperPlayClientClickWindow;
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerTeams;
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerWindowItems;
import java.lang.reflect.*;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.logging.Logger;
import net.kyori.adventure.text.Component;

/** Decodes supported packet presentation fields; never performs byte/string substitutions. */
final class ClientResourceBridge extends PacketListenerAbstract {
    private final Logger logger;
    private volatile ClientResourceMappings mappings = new ClientResourceMappings(Map.of());
    private volatile boolean active;
    private final Map<UUID, ClientResourceMappings> previews = new ConcurrentHashMap<>();
    private final java.util.concurrent.atomic.AtomicLong mappedPackets = new java.util.concurrent.atomic.AtomicLong();
    private final java.util.concurrent.atomic.AtomicLong failedPackets = new java.util.concurrent.atomic.AtomicLong();
    private final Map<String, Constructor<?>> constructors = new HashMap<>();
    private final Map<Class<?>, List<Property>> properties = new ConcurrentHashMap<>();
    private record Property(Method get, Method set) {}

    ClientResourceBridge(Logger logger) {
        super(PacketListenerPriority.HIGHEST);
        this.logger = logger;
        String[][] types = {
                {"SYSTEM_CHAT_MESSAGE", "SystemChatMessage"}, {"ACTION_BAR", "ActionBar"},
                {"SET_TITLE_TEXT", "SetTitleText"}, {"SET_TITLE_SUBTITLE", "SetTitleSubtitle"},
                {"BOSS_BAR", "BossBar"}, {"OPEN_WINDOW", "OpenWindow"},
                {"PLAYER_LIST_HEADER_AND_FOOTER", "PlayerListHeaderAndFooter"},
                {"SCOREBOARD_OBJECTIVE", "ScoreboardObjective"},
                {"ENTITY_METADATA", "EntityMetadata"}, {"ENTITY_EQUIPMENT", "EntityEquipment"},
                {"WINDOW_ITEMS", "WindowItems"}, {"SET_SLOT", "SetSlot"},
                {"SET_CURSOR_ITEM", "SetCursorItem"}, {"SET_PLAYER_INVENTORY", "SetPlayerInventory"},
                {"SOUND_EFFECT", "SoundEffect"}, {"ENTITY_SOUND_EFFECT", "EntitySoundEffect"},
                {"MERCHANT_OFFERS", "MerchantOffers"}, {"TEAMS", "Teams"}
        };
        try {
            for (String[] type : types)
                constructors.put(type[0], Class.forName("com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServer"
                        + type[1]).getConstructor(PacketSendEvent.class));
            constructors.put("STOP_SOUND", StopSoundPacket.class.getConstructor(PacketSendEvent.class));
            constructors.put("SHOW_DIALOG", ShowDialogPacket.class.getConstructor(PacketSendEvent.class));
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException("Installed PacketEvents cannot provide the required resource adapters", e);
        }
    }

    void install(Map<String, Map<String, String>> values) {
        mappings = new ClientResourceMappings(values);
        active = !values.isEmpty();
    }
    void preview(UUID player, Map<String, Map<String, String>> values) {
        previews.put(player, new ClientResourceMappings(values));
    }
    void stopPreview(UUID player) { previews.remove(player); }
    ClientResourceMappings globalMappings() { return active ? mappings : null; }
    String status() { return "mapped packets=" + mappedPackets.get() + ", failed packets=" + failedPackets.get(); }

    ClientResourceMappings selectMappings(UUID player) {
        if (player == null) return null;
        ClientResourceMappings selected = previews.get(player);
        return selected != null ? selected : active ? mappings : null;
    }

    @Override
    public void onPacketReceive(PacketReceiveEvent event) {
        if (!active && previews.isEmpty()) return;
        ClientResourceMappings selected = selectMappings(event.getUser().getUUID());
        if (selected == null) return;
        try {
            PacketWrapper<?> wrapper = switch (event.getPacketType().toString()) {
                case "CREATIVE_INVENTORY_ACTION" -> new WrapperPlayClientCreativeInventoryAction(event);
                case "CLICK_WINDOW" -> event.getUser().getClientVersion().isOlderThan(
                        com.github.retrooper.packetevents.protocol.player.ClientVersion.V_1_21_5)
                        ? new WrapperPlayClientClickWindow(event) : null;
                default -> null;
            };
            // Modern click packets contain hashes, not item components. CE compares them through
            // NativeItemResourceBridge.s2c; never turn those hashes into fabricated item stacks.
            if (wrapper != null && rewritePacket(wrapper, selected.inverse())) event.markForReEncode(true);
        } catch (ReflectiveOperationException | RuntimeException e) {
            failedPackets.incrementAndGet();
            event.setCancelled(true);
            logger.warning("Rejected resource roundtrip for " + event.getPacketType() + ": "
                    + e.getClass().getSimpleName());
        }
    }

    @Override
    public void onPacketSend(PacketSendEvent event) {
        if (!active && previews.isEmpty()) return;
        ClientResourceMappings selected = selectMappings(event.getUser().getUUID());
        if (selected == null) return;
        Constructor<?> constructor = constructors.get(event.getPacketType().toString());
        if (constructor == null) return;
        try {
            PacketWrapper<?> wrapper = (PacketWrapper<?>)constructor.newInstance(event);
            if (rewritePacket(wrapper, selected)) {
                event.markForReEncode(true);
                mappedPackets.incrementAndGet();
            }
        } catch (ReflectiveOperationException | RuntimeException e) {
            failedPackets.incrementAndGet();
            logger.warning("Client resource mapping failed for " + event.getPacketType() + ": "
                    + e.getClass().getSimpleName());
        }
    }

    boolean rewritePacket(PacketWrapper<?> wrapper, ClientResourceMappings map) throws ReflectiveOperationException {
        if (wrapper instanceof StopSoundPacket stop) return stop.translate(map);
        if (wrapper instanceof ShowDialogPacket dialog) return dialog.translate(map);
        boolean changed = false;
        if (wrapper instanceof WrapperPlayServerWindowItems inventory && inventory.getCarriedItem().isPresent()) {
            ItemStack before = inventory.getCarriedItem().get();
            ItemStack after = (ItemStack)rewrite(before, wrapper, map);
            if (!before.equals(after)) {
                inventory.setCarriedItem(after);
                changed = true;
            }
        }
        if (wrapper instanceof WrapperPlayServerTeams teams && teams.getTeamInfo().isPresent()) {
            var before = teams.getTeamInfo().get();
            Component display = (Component)rewrite(before.getDisplayName(), wrapper, map);
            Component prefix = (Component)rewrite(before.getPrefix(), wrapper, map);
            Component suffix = (Component)rewrite(before.getSuffix(), wrapper, map);
            if (!display.equals(before.getDisplayName()) || !prefix.equals(before.getPrefix())
                    || !suffix.equals(before.getSuffix())) {
                teams.setTeamInfo(new WrapperPlayServerTeams.ScoreBoardTeamInfo(display, prefix, suffix,
                        before.getTagVisibility(), before.getCollisionRule(), before.getColor(), before.getOptionData()));
                changed = true;
            }
        }
        for (Property property : properties.computeIfAbsent(wrapper.getClass(), ClientResourceBridge::properties)) {
            Object before = property.get.invoke(wrapper);
            Object after = rewrite(before, wrapper, map);
            if (!Objects.equals(before, after)) {
                property.set.invoke(wrapper, after);
                changed = true;
            }
        }
        return changed;
    }

    private static List<Property> properties(Class<?> type) {
        List<Property> result = new ArrayList<>();
        for (Method getter : type.getMethods()) {
            Class<?> returns = getter.getReturnType();
            if (getter.getParameterCount() != 0 || !getter.getName().startsWith("get")
                    || !(Component.class.isAssignableFrom(returns) || returns == ItemStack.class
                    || Sound.class.isAssignableFrom(returns) || Dialog.class.isAssignableFrom(returns)
                    || List.class.isAssignableFrom(returns) || Map.class.isAssignableFrom(returns)
                    || returns == Optional.class)) continue;
            try {
                Method setter = type.getMethod("set" + getter.getName().substring(3), returns);
                result.add(new Property(getter, setter));
            } catch (NoSuchMethodException ignored) {}
        }
        return List.copyOf(result);
    }

    @SuppressWarnings({"rawtypes", "unchecked"})
    private Object rewrite(Object value, PacketWrapper<?> wrapper, ClientResourceMappings map) {
        if (value instanceof Component component) {
            NBT before = AdventureSerializer.toNbt(component), after = map.rewrite(before);
            return before.equals(after) ? value : AdventureSerializer.fromNbt(after);
        }
        if (value instanceof ItemStack item && !item.isEmpty()) {
            ItemStack result = null;
            for (var entry : item.getComponents().getPatches().entrySet()) {
                if (entry.getValue().isEmpty()) continue;
                ComponentValue<?> before = new ComponentValue(entry.getKey(), entry.getValue().get());
                ComponentValue<?> after = rewriteComponent(before, wrapper, map);
                if (!before.equals(after)) {
                    if (result == null) result = item.copy();
                    setComponent(result, after);
                }
            }
            return result == null ? value : result;
        }
        if (value instanceof Dialog dialog) {
            NBT before = Dialog.encode(wrapper, dialog), after = map.rewrite(before);
            return before.equals(after) ? value : Dialog.decode(after, wrapper);
        }
        if (value instanceof Sound sound) {
            String before = sound.getSoundId().toString(), after = map.id("sound", before);
            return before.equals(after) ? value : new StaticSound(new ResourceLocation(after), sound.getRange());
        }
        if (value instanceof EntityData<?> data) {
            Object after = rewrite(data.getValue(), wrapper, map);
            return Objects.equals(data.getValue(), after) ? value : new EntityData(data.getIndex(), data.getType(), after);
        }
        if (value instanceof Equipment equipment) {
            ItemStack after = (ItemStack)rewrite(equipment.getItem(), wrapper, map);
            return Objects.equals(equipment.getItem(), after) ? value : new Equipment(equipment.getSlot(), after);
        }
        if (value instanceof MerchantOffer offer) {
            var beforeFirst = offer.getFirstInputCost();
            var beforeSecond = offer.getSecondInputCost();
            var first = rewriteCost(beforeFirst, wrapper, map);
            var second = rewriteCost(beforeSecond, wrapper, map);
            var output = (ItemStack)rewrite(offer.getOutputItem(), wrapper, map);
            return first == beforeFirst && second == beforeSecond
                    && output == offer.getOutputItem() ? value
                    : MerchantOffer.of(first, second, output, offer.getUses(), offer.getMaxUses(), offer.getXp(),
                            offer.getSpecialPrice(), offer.getPriceMultiplier(), offer.getDemand());
        }
        if (value instanceof List<?> list) {
            List<Object> after = new ArrayList<>(list.size());
            for (Object child : list) after.add(rewrite(child, wrapper, map));
            return list.equals(after) ? value : after;
        }
        if (value instanceof Optional<?> optional)
            return optional.map(child -> rewrite(child, wrapper, map));
        if (value instanceof Map<?, ?> entries) {
            Map<Object, Object> after = new LinkedHashMap<>();
            entries.forEach((key, child) -> after.put(key, rewrite(child, wrapper, map)));
            return entries.equals(after) ? value : after;
        }
        return value;
    }

    private MerchantItemCost rewriteCost(MerchantItemCost cost, PacketWrapper<?> wrapper, ClientResourceMappings map) {
        if (cost == null) return null;
        List<ComponentValue<?>> values = new ArrayList<>();
        boolean changed = false;
        for (ComponentValue<?> component : cost.getPredicate().getRequiredComponents()) {
            ComponentValue<?> after = rewriteComponent(component, wrapper, map);
            values.add(after);
            changed |= !component.equals(after);
        }
        return changed ? new MerchantItemCost(cost.getItem(), cost.getCount(), new ComponentPredicate(values)) : cost;
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    private <T> ComponentValue<T> rewriteComponent(ComponentValue<T> component, PacketWrapper<?> wrapper,
                                                   ClientResourceMappings map) {
        String key = component.getType().getName().toString();
        if (!Set.of("minecraft:item_model", "minecraft:custom_name", "minecraft:item_name", "minecraft:lore",
                "minecraft:equippable", "minecraft:camera_overlay", "minecraft:container",
                "minecraft:bundle_contents", "minecraft:charged_projectiles").contains(key)) return component;
        Object nested = component.getValue();
        if (nested instanceof ItemModel model) {
            ResourceLocation target = resource(map, "items", model.getModelLocation());
            return target.equals(model.getModelLocation()) ? component
                    : new ComponentValue(component.getType(), new ItemModel(target));
        }
        if (nested instanceof Component text) {
            var after = (Component)rewrite(text, wrapper, map);
            return text.equals(after) ? component : new ComponentValue(component.getType(), after);
        }
        if (nested instanceof ItemLore lore) {
            var after = new ItemLore((List<Component>)rewrite(lore.getLines(), wrapper, map));
            return lore.equals(after) ? component : new ComponentValue(component.getType(), after);
        }
        if (nested instanceof ItemEquippable equip) {
            var after = new ItemEquippable(equip.getSlot(), (Sound)rewrite(equip.getEquipSound(), wrapper, map),
                    resource(map, "equipment", equip.getAssetId()), resource(map, "textures", equip.getCameraOverlay()),
                    equip.getAllowedEntities(), equip.isDispensable(), equip.isSwappable(), equip.isDamageOnHurt(),
                    equip.isEquipOnInteract(), equip.isCanBeSheared(),
                    (Sound)rewrite(equip.getShearingSound(), wrapper, map));
            return equip.equals(after) ? component : new ComponentValue(component.getType(), after);
        }
        if (nested instanceof ItemContainerContents contents) {
            var result = new ItemContainerContents((List<ItemStack>)rewrite(contents.getItems(), wrapper, map));
            return result.equals(contents) ? component : new ComponentValue(component.getType(), result);
        }
        if (nested instanceof BundleContents contents) {
            var result = new BundleContents((List<ItemStack>)rewrite(contents.getItems(), wrapper, map));
            return result.equals(contents) ? component : new ComponentValue(component.getType(), result);
        }
        if (nested instanceof ChargedProjectiles contents) {
            var result = new ChargedProjectiles((List<ItemStack>)rewrite(contents.getItems(), wrapper, map));
            return result.equals(contents) ? component : new ComponentValue(component.getType(), result);
        }
        return component;
    }

    private ResourceLocation resource(ClientResourceMappings map, String type, ResourceLocation id) {
        return id == null ? null : new ResourceLocation(map.id(type, id.toString()));
    }
    private <T> void setComponent(ItemStack item, ComponentValue<T> value) {
        item.setComponent(value.getType(), value.getValue());
    }
}
