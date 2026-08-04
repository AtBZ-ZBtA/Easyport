package net.minecraftforge.fml;

/**
 * Shim for {@code LogicalSide}, which NeoForge dropped in favour of vanilla's
 * {@code PacketFlow} and its own {@code Dist}.
 *
 * Logical side is not the same question as physical side: an integrated server running inside a
 * client is {@code Dist.CLIENT} physically and {@code LogicalSide.SERVER} logically. Mapping
 * this onto {@code Dist} would therefore be wrong in single-player, which is where mods are
 * mostly tested and where the bug would be least likely to be noticed.
 *
 * So it stands alone as a plain enum. It is reached almost entirely through
 * {@code NetworkDirection.getReceptionSide()} -- 26 corpus jars -- where the answer follows
 * from the packet's direction and needs no environment lookup at all.
 */
public enum LogicalSide {
    CLIENT,
    SERVER;

    public boolean isServer() {
        return this == SERVER;
    }

    public boolean isClient() {
        return this == CLIENT;
    }
}
