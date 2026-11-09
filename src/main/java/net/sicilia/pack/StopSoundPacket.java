package net.sicilia.pack;

import com.github.retrooper.packetevents.event.PacketSendEvent;
import com.github.retrooper.packetevents.protocol.packettype.PacketType;
import com.github.retrooper.packetevents.wrapper.PacketWrapper;

/** Schema checked against the installed vanilla 26.3 ClientboundStopSoundPacket codec. */
final class StopSoundPacket extends PacketWrapper<StopSoundPacket> {
    private int flags;
    private int category;
    private String sound;

    public StopSoundPacket(PacketSendEvent event) { super(event); }
    StopSoundPacket(Integer category, String sound) {
        super(PacketType.Play.Server.STOP_SOUND);
        flags = (category == null ? 0 : 1) | (sound == null ? 0 : 2);
        this.category = category == null ? 0 : category;
        this.sound = sound;
    }
    @Override public void read() {
        flags = readByte();
        if ((flags & 1) != 0) category = readVarInt();
        sound = (flags & 2) != 0 ? readString() : null;
    }
    @Override public void write() {
        writeByte(flags);
        if ((flags & 1) != 0) writeVarInt(category);
        if ((flags & 2) != 0) writeString(sound);
    }
    @Override public void copy(StopSoundPacket other) {
        flags = other.flags;
        category = other.category;
        sound = other.sound;
    }
    String sound() { return sound; }
    boolean translate(ClientResourceMappings mappings) {
        if (sound == null) return false;
        String target = mappings.id("sound", sound);
        if (sound.equals(target)) return false;
        sound = target;
        return true;
    }
}
