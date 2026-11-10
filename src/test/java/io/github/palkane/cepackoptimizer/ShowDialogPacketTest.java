package io.github.palkane.cepackoptimizer;

import com.github.retrooper.packetevents.event.PacketSendEvent;
import com.github.retrooper.packetevents.manager.server.ServerVersion;
import com.github.retrooper.packetevents.protocol.ConnectionState;
import com.github.retrooper.packetevents.protocol.nbt.*;
import com.github.retrooper.packetevents.protocol.packettype.PacketType;
import com.github.retrooper.packetevents.protocol.player.*;
import com.github.retrooper.packetevents.wrapper.PacketWrapper;
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerShowDialog;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import java.nio.file.*;
import java.util.*;
import java.util.logging.Logger;

/** Exercises the outgoing listener with real PE wire codecs, including large dialog NBT. */
public final class ShowDialogPacketTest {
    private static int checks;
    private static void check(boolean condition, String description) {
        checks++;
        if (!condition) throw new AssertionError(description);
    }
    private static final class Send extends PacketSendEvent {
        Send(ByteBuf bytes) {
            super(PacketType.Play.Server.SHOW_DIALOG.getId(ClientVersion.V_26_3),
                PacketType.Play.Server.SHOW_DIALOG, ServerVersion.V_26_3, null,
                new User(null, ConnectionState.PLAY, ClientVersion.V_26_3,
                    new UserProfile(UUID.randomUUID(), "dialog-test")), null, bytes);
        }
    }
    private static NBTCompound object(Object... fields) {
        var value = new NBTCompound();
        for (int i = 0; i < fields.length; i += 2)
            value.setTag((String)fields[i], fields[i + 1] instanceof NBT tag ? tag : new NBTString((String)fields[i + 1]));
        return value;
    }
    private static NBTCompound dialog() {
        var text = new NBTList<NBTCompound>(NBTType.COMPOUND);
        for (int i = 0; i < 8192; i++)
            text.addTag(object("text", "0123456789", "font", "dialogmenu:space",
                "hover_event", object("action", "show_text", "value", object("text", "X/Y"))));
        return object("type", "minecraft:dialog_list", "title", object("text", ""),
            "pause", new NBTByte(false), "after_action", "none",
            "body", new NBTList<>(NBTType.COMPOUND, List.of(object("type", "minecraft:plain_message",
                "contents", object("text", "", "extra", text), "width", new NBTInt(1024)))),
            "dialogs", new NBTList<>(NBTType.STRING),
            "exit_action", object("label", object("text", "Close"),
                "action", object("type", "custom", "id", "dialogmenu:test")));
    }
    private static ByteBuf wire(NBT data) {
        ByteBuf bytes = Unpooled.buffer();
        var wrapper = PacketWrapper.createUniversalPacketWrapper(bytes);
        wrapper.writeVarInt(0);
        wrapper.writeNBTRaw(data);
        return bytes;
    }
    private static NBT raw(ByteBuf bytes) {
        var wrapper = PacketWrapper.createUniversalPacketWrapper(bytes);
        check(wrapper.readVarInt() == 0, "inline dialog holder changed");
        NBT value = wrapper.readUnlimitedNBTRaw();
        check(!bytes.isReadable(), "trailing dialog data");
        return value;
    }
    private static void exercise(ByteBuf bytes, boolean overDefaultLimit) throws Exception {
        int index = bytes.readerIndex();
        NBT before = raw(bytes);
        bytes.readerIndex(index);
        if (overDefaultLimit) {
            try {
                new WrapperPlayServerShowDialog(new Send(bytes));
                throw new AssertionError("fixture no longer reproduces default NBT size failure");
            } catch (IllegalArgumentException expected) {
                check(expected.getMessage().contains("NBT size limit reached"), "unexpected baseline failure: " + expected);
            }
            bytes.readerIndex(index);
        }
        Set<String> fonts = new TreeSet<>();
        collectFonts(before, fonts);
        check(!fonts.isEmpty(), "fixture lacks fonts");
        Map<String, String> renamed = new HashMap<>();
        for (String font : fonts)
            if (!font.startsWith("minecraft:")) renamed.put(font, "minecraft:" + font.replace(':', '/'));
        var mapping = new ClientResourceMappings(Map.of("font", renamed));
        var bridge = new ClientResourceBridge(Logger.getAnonymousLogger());
        bridge.install(Map.of("font", renamed));
        var event = new Send(bytes);
        event.markForReEncode(false);
        bridge.onPacketSend(event);
        check(event.needsReEncode(), "actual outgoing SHOW_DIALOG listener did not map packet: " + bridge.status());
        check(bridge.status().equals("mapped packets=1, failed packets=0"), "mapping failure: " + bridge.status());
        var mapped = event.getLastUsedWrapper();
        ByteBuf outgoing = Unpooled.buffer();
        mapped.setBuffer(outgoing);
        mapped.write();
        NBT after = raw(outgoing);
        check(after.equals(mapping.rewrite(before)), "dialog data besides resource IDs changed");
        check(mapping.inverse().rewrite(after).equals(before), "dialog roundtrip lost text/hover/click/close/geometry");
        if (System.getProperty("dialog.test.output") != null && fonts.size() > 1)
            Files.write(Path.of(System.getProperty("dialog.test.output")),
                io.netty.buffer.ByteBufUtil.getBytes(outgoing, 0, outgoing.writerIndex()));
        outgoing.readerIndex(0);
        new ShowDialogPacket(new Send(outgoing));
        check(!outgoing.isReadable(), "mapped dialog cannot be read back");
        bytes.readerIndex(index);
        var unchanged = new Send(bytes);
        unchanged.markForReEncode(false);
        var inactive = new ClientResourceBridge(Logger.getAnonymousLogger());
        inactive.onPacketSend(unchanged);
        check(!unchanged.needsReEncode() && bytes.readerIndex() == index,
            "inactive mapping must leave packets untouched");
        outgoing.release();
        bytes.release();
    }
    private static void collectFonts(NBT value, Set<String> fonts) {
        if (value instanceof NBTCompound compound) {
            var font = compound.getTagOrNull("font");
            if (font instanceof NBTString string) fonts.add(string.getValue());
            if (font instanceof NBTCompound definition && definition.getTagOrNull("id") instanceof NBTString string)
                fonts.add(string.getValue());
            compound.getTags().values().forEach(child -> collectFonts(child, fonts));
        } else if (value instanceof NBTList<?> list)
            list.getTags().forEach(child -> collectFonts(child, fonts));
    }
    public static void main(String[] args) throws Exception {
        ClientResourceProtocolTest.bootstrap();
        exercise(wire(dialog()), true);
        ByteBuf reference = Unpooled.buffer();
        PacketWrapper.createUniversalPacketWrapper(reference).writeVarInt(12);
        var registered = new ShowDialogPacket(new Send(reference));
        check(!registered.translate(new ClientResourceMappings(Map.of("font",
            Map.of("dialogmenu:space", "custom:dialogmenu/space")))), "registry reference rewritten as inline data");
        reference.clear();
        registered.write();
        check(PacketWrapper.createUniversalPacketWrapper(reference).readVarInt() == 12 && !reference.isReadable(),
            "registry holder ID changed");
        reference.release();
        if (args.length > 0 && !args[0].isBlank()) exercise(Unpooled.wrappedBuffer(Files.readAllBytes(Path.of(args[0]))), true);
        System.out.println("SHOW_DIALOG wire checks passed: " + checks);
    }
}
