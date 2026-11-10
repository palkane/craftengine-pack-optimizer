package io.github.palkane.cepackoptimizer;

import com.github.retrooper.packetevents.*;
import com.github.retrooper.packetevents.injector.ChannelInjector;
import com.github.retrooper.packetevents.manager.player.PlayerManager;
import com.github.retrooper.packetevents.manager.protocol.ProtocolManager;
import com.github.retrooper.packetevents.manager.server.*;
import com.github.retrooper.packetevents.netty.NettyManager;
import com.github.retrooper.packetevents.protocol.component.*;
import com.github.retrooper.packetevents.protocol.component.builtin.item.*;
import com.github.retrooper.packetevents.protocol.entity.data.*;
import com.github.retrooper.packetevents.protocol.dialog.*;
import com.github.retrooper.packetevents.protocol.item.ItemStack;
import com.github.retrooper.packetevents.protocol.item.type.ItemTypes;
import com.github.retrooper.packetevents.protocol.nbt.*;
import com.github.retrooper.packetevents.protocol.player.ClientVersion;
import com.github.retrooper.packetevents.protocol.recipe.data.*;
import com.github.retrooper.packetevents.resources.ResourceLocation;
import com.github.retrooper.packetevents.wrapper.PacketWrapper;
import com.github.retrooper.packetevents.wrapper.play.client.*;
import com.github.retrooper.packetevents.wrapper.play.server.*;
import java.util.*;
import net.kyori.adventure.key.Key;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;

/** Real PE item/component codecs and packet wrappers; only platform lifecycle is absent. */
public final class ClientResourceProtocolTest {
    private static int checks;
    private static void check(boolean condition, String description) {
        checks++;
        if (!condition) throw new AssertionError(description);
    }
    static void bootstrap() {
        PacketEvents.setAPI(new PacketEventsAPI<Object>() {
            private final NettyManager netty = new io.github.retrooper.packetevents.impl.netty.NettyManagerImpl();
            public boolean isLoaded() { return true; }
            public void init() {}
            public boolean isInitialized() { return true; }
            public boolean isTerminated() { return false; }
            public Object getPlugin() { return null; }
            public ServerManager getServerManager() { return () -> ServerVersion.V_26_3; }
            public ProtocolManager getProtocolManager() { throw new UnsupportedOperationException(); }
            public PlayerManager getPlayerManager() { throw new UnsupportedOperationException(); }
            public NettyManager getNettyManager() { return netty; }
            public ChannelInjector getInjector() { throw new UnsupportedOperationException(); }
        });
    }
    private static ItemStack item() {
        NBTCompound data = new NBTCompound();
        data.setTag("font", new NBTString("menu:text"));
        data.setTag("id", new NBTString("demo:item"));
        return ItemStack.builder().type(ItemTypes.PAPER).amount(3).version(ClientVersion.V_26_3)
                .component(ComponentTypes.ITEM_MODEL, new ItemModel(new ResourceLocation("demo:item")))
                .component(ComponentTypes.CUSTOM_DATA, data)
                .component(ComponentTypes.CUSTOM_NAME, Component.text("demo:item").font(Key.key("menu:text")))
                .build();
    }
    private static String model(ItemStack item) {
        return item.getComponent(ComponentTypes.ITEM_MODEL).orElseThrow().getModelLocation().toString();
    }
    private static Map<ComponentType<?>, Object> required(ComponentPredicate predicate) {
        Map<ComponentType<?>, Object> result = new HashMap<>();
        for (var value : predicate.getRequiredComponents()) result.put(value.getType(), value.getValue());
        return result;
    }
    public static void main(String[] args) throws Exception {
        bootstrap();
        var map = new ClientResourceMappings(Map.of("items", Map.of("demo:item", "custom:demo/item"),
                "font", Map.of("menu:text", "custom:menu/text"),
                "equipment", Map.of("demo:armor", "custom:demo/armor"),
                "sound", Map.of("demo:loop", "custom:demo.loop")));
        var bridge = new ClientResourceBridge(java.util.logging.Logger.getAnonymousLogger());
        ItemStack source = item();
        var slot = new WrapperPlayServerSetSlot(0, 1, 7, source);
        check(bridge.rewritePacket(slot, map), "set slot unchanged");
        check(model(slot.getItem()).equals("custom:demo/item"), "slot model path");
        check(model(source).equals("demo:item"), "server item mutated");
        check(slot.getItem().getComponent(ComponentTypes.CUSTOM_DATA).equals(source.getComponent(ComponentTypes.CUSTOM_DATA)),
                "PDC changed");
        check(slot.getItem().getComponent(ComponentTypes.CUSTOM_NAME).orElseThrow().font().equals(Key.key("custom:menu/text")),
                "item name font not translated");
        check(!bridge.rewritePacket(slot, map), "second rewrite changed mapped item");
        var creative = new WrapperPlayClientCreativeInventoryAction(7, slot.getItem());
        check(bridge.rewritePacket(creative, map.inverse()), "creative reverse missing");
        check(creative.getItemStack().equals(source), "creative roundtrip lost identity/components");
        var inventory = new WrapperPlayServerWindowItems(0, 1, List.of(source), source);
        check(bridge.rewritePacket(inventory, map), "inventory unchanged");
        check(model(inventory.getItems().getFirst()).equals("custom:demo/item")
                && model(inventory.getCarriedItem().orElseThrow()).equals("custom:demo/item"), "inventory cursor/slot mismatch");
        for (ComponentType<?> type : List.of(ComponentTypes.CONTAINER, ComponentTypes.BUNDLE_CONTENTS,
                ComponentTypes.CHARGED_PROJECTILES)) {
            var builder = ItemStack.builder().type(ItemTypes.BUNDLE).version(ClientVersion.V_26_3);
            if (type == ComponentTypes.CONTAINER)
                builder.component(ComponentTypes.CONTAINER, new ItemContainerContents(List.of(source)));
            if (type == ComponentTypes.BUNDLE_CONTENTS)
                builder.component(ComponentTypes.BUNDLE_CONTENTS, new BundleContents(List.of(source)));
            if (type == ComponentTypes.CHARGED_PROJECTILES)
                builder.component(ComponentTypes.CHARGED_PROJECTILES, new ChargedProjectiles(List.of(source)));
            var nested = builder.build();
            var packet = new WrapperPlayServerSetSlot(0, 1, 7, nested);
            check(bridge.rewritePacket(packet, map), "nested component not mapped: " + type.getName());
            ItemStack child = type == ComponentTypes.CONTAINER
                    ? packet.getItem().getComponent(ComponentTypes.CONTAINER).orElseThrow().getItems().getFirst()
                    : type == ComponentTypes.BUNDLE_CONTENTS
                    ? packet.getItem().getComponent(ComponentTypes.BUNDLE_CONTENTS).orElseThrow().getItems().getFirst()
                    : packet.getItem().getComponent(ComponentTypes.CHARGED_PROJECTILES).orElseThrow().getItems().getFirst();
            check(model(child).equals("custom:demo/item"), "nested model unchanged");
            check(bridge.rewritePacket(packet, map.inverse()) && packet.getItem().equals(nested), "nested roundtrip");
            check(model(source).equals("demo:item"), "nested source changed");
        }
        var metadata = new WrapperPlayServerEntityMetadata(42, List.of(
                new EntityData<>(23, EntityDataTypes.ITEMSTACK, source)));
        check(bridge.rewritePacket(metadata, map), "display entity item missing");
        check(model((ItemStack)metadata.getEntityMetadata().getFirst().getValue()).equals("custom:demo/item"),
                "display entity incorrect model");
        var predicate = new ComponentPredicate(List.of(
                new ComponentValue<>(ComponentTypes.ITEM_MODEL, new ItemModel(new ResourceLocation("demo:item"))),
                new ComponentValue<>(ComponentTypes.CUSTOM_DATA, source.getComponent(ComponentTypes.CUSTOM_DATA).orElseThrow())));
        var cost = new MerchantItemCost(ItemTypes.PAPER, 4, predicate);
        var offer = MerchantOffer.of(cost, source, 2, 11, 5, -2, 0.25f, 9);
        var merchant = new WrapperPlayServerMerchantOffers(4, List.of(offer), 2, 3, true, true);
        check(bridge.rewritePacket(merchant, map), "merchant unchanged");
        var changed = merchant.getMerchantOffers().getFirst();
        check(model(changed.getOutputItem()).equals("custom:demo/item"), "merchant output not mapped");
        check(((ItemModel)required(changed.getFirstInputCost().getPredicate()).get(ComponentTypes.ITEM_MODEL))
                .getModelLocation().toString().equals("custom:demo/item"), "merchant predicate not mapped");
        check(changed.getFirstInputCost().getCount() == 4 && changed.getUses() == 2
                && changed.getDemand() == 9 && changed.getSpecialPrice() == -2, "merchant rules changed");
        check(cost.getPredicate().equals(predicate) && model(offer.getOutputItem()).equals("demo:item"),
                "server merchant changed");
        check(bridge.rewritePacket(merchant, map.inverse())
                && required(merchant.getMerchantOffers().getFirst().getFirstInputCost().getPredicate()).equals(required(predicate)),
                "merchant roundtrip changed predicate");
        var text = Component.text("literal menu:text").font(Key.key("menu:text"));
        var info = new WrapperPlayServerTeams.ScoreBoardTeamInfo(text, text, text,
                WrapperPlayServerTeams.NameTagVisibility.ALWAYS, WrapperPlayServerTeams.CollisionRule.ALWAYS,
                NamedTextColor.WHITE, WrapperPlayServerTeams.OptionData.NONE);
        var team = new WrapperPlayServerTeams("test", WrapperPlayServerTeams.TeamMode.CREATE, info);
        check(bridge.rewritePacket(team, map), "team font unchanged");
        check(team.getTeamInfo().orElseThrow().getPrefix().font().equals(Key.key("custom:menu/text")),
                "team prefix font wrong");
        check(info.getPrefix().equals(text), "server team mutated");
        var dialog = new NoticeDialog(new CommonDialogData(text, null, true, false,
                DialogAction.CLOSE, List.of(), List.of()), NoticeDialog.DEFAULT_ACTION);
        var showDialog = new WrapperPlayServerShowDialog(dialog);
        check(bridge.rewritePacket(showDialog, map), "dialog unchanged");
        check(((NoticeDialog)showDialog.getDialog()).getCommon().getTitle().font().equals(Key.key("custom:menu/text")),
                "dialog font wrong");
        check(dialog.getCommon().getTitle().equals(text), "server dialog mutated");
        var equip = new ItemEquippable(com.github.retrooper.packetevents.protocol.player.EquipmentSlot.HELMET,
                new com.github.retrooper.packetevents.protocol.sound.StaticSound(new ResourceLocation("demo:loop"), null),
                new ResourceLocation("demo:armor"), null, null, true, false, true, false, false,
                new com.github.retrooper.packetevents.protocol.sound.StaticSound(new ResourceLocation("demo:loop"), null));
        var armor = ItemStack.builder().type(ItemTypes.PAPER).version(ClientVersion.V_26_3)
                .component(ComponentTypes.EQUIPPABLE, equip).build();
        var armorSlot = new WrapperPlayServerSetSlot(0, 1, 8, armor);
        check(bridge.rewritePacket(armorSlot, map), "equipment unchanged");
        var mappedEquipment = armorSlot.getItem().getComponent(ComponentTypes.EQUIPPABLE).orElseThrow();
        check(mappedEquipment.getAssetId().toString().equals("custom:demo/armor")
                && mappedEquipment.getEquipSound().getSoundId().toString().equals("custom:demo.loop")
                && !mappedEquipment.isSwappable(), "equipment asset/sound/flags wrong");
        check(equip.getAssetId().toString().equals("demo:armor"), "server equipment mutated");
        for (Integer category : Arrays.asList(null, 3)) {
            var stop = new StopSoundPacket(category, "demo:loop");
            check(bridge.rewritePacket(stop, map) && stop.sound().equals("custom:demo.loop"), "stop sound not mapped");
            check(bridge.rewritePacket(stop, map.inverse()) && stop.sound().equals("demo:loop"), "stop sound reverse");
            check(!bridge.rewritePacket(new StopSoundPacket(category, null), map), "stop-all altered");
        }
        var nativeItem = new net.momirealms.craftengine.libraries.nbt.CompoundTag();
        var components = new net.momirealms.craftengine.libraries.nbt.CompoundTag();
        components.putString("minecraft:item_model", "demo:item");
        var custom = new net.momirealms.craftengine.libraries.nbt.CompoundTag();
        custom.putString("font", "menu:text");
        components.put("minecraft:custom_data", custom);
        nativeItem.put("components", components);
        var converted = (net.momirealms.craftengine.libraries.nbt.CompoundTag)map.rewriteNative(nativeItem);
        check(converted.getCompound("components").getString("minecraft:item_model").equals("custom:demo/item"),
                "CE native item not translated");
        check(map.inverse().rewriteNative(converted).equals(nativeItem), "CE native roundtrip changed identity");
        check(components.getString("minecraft:item_model").equals("demo:item"), "CE native source mutated");
        var mutable = new HashMap<String, String>();
        mutable.put("demo:item", "custom:demo/item");
        var immutable = new ClientResourceMappings(Map.of("items", mutable));
        mutable.clear();
        check(immutable.id("items", "demo:item").equals("custom:demo/item"), "caller mutated installed mapping");
        check(immutable.inverse().inverse() == immutable, "inverse must share original mapping snapshot");
        try {
            new ClientResourceMappings(Map.of("items", Map.of("a:item", "custom:same", "b:item", "custom:same")));
            throw new AssertionError("ambiguous inverse accepted");
        } catch (IllegalArgumentException expected) { checks++; }
        if (args.length == 1 && !args[0].isBlank()) {
            var result = new com.google.gson.Gson().fromJson(java.nio.file.Files.readString(java.nio.file.Path.of(args[0])),
                    StrictNamespacePack.Result.class);
            var actual = new ClientResourceMappings(result.merge().runtimeIds());
            for (var entry : result.merge().runtimeIds().entrySet())
                for (var ids : entry.getValue().entrySet())
                    check(actual.inverse().id(entry.getKey(), actual.id(entry.getKey(), ids.getKey())).equals(ids.getKey()),
                            "actual pack resource not reversible");
        }
        System.out.println("Client resource protocol checks passed: " + checks);
    }
}
