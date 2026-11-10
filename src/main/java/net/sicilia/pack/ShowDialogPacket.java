package net.sicilia.pack;

import com.github.retrooper.packetevents.event.PacketSendEvent;
import com.github.retrooper.packetevents.protocol.nbt.NBT;
import com.github.retrooper.packetevents.wrapper.PacketWrapper;

/** Matches vanilla Dialog.STREAM_CODEC's trusted, server-originated NBT reader. */
final class ShowDialogPacket extends PacketWrapper<ShowDialogPacket> {
    private int holder;
    private NBT dialog;

    public ShowDialogPacket(PacketSendEvent event) { super(event); }

    @Override public void read() {
        holder = readVarInt();
        dialog = holder == 0 ? readUnlimitedNBTRaw() : null;
    }

    @Override public void write() {
        writeVarInt(holder);
        if (holder == 0) writeNBTRaw(dialog);
    }

    @Override public void copy(ShowDialogPacket other) {
        holder = other.holder;
        dialog = other.dialog;
    }

    boolean translate(ClientResourceMappings mappings) {
        if (dialog == null) return false;
        NBT after = mappings.rewrite(dialog);
        if (dialog.equals(after)) return false;
        dialog = after;
        return true;
    }
}
