package net.minecraftforge.network;

import net.minecraft.network.protocol.PacketFlow;
import net.minecraftforge.fml.LogicalSide;

/**
 * Shim for {@code NetworkDirection}, referenced by 122 corpus jars.
 *
 * Forge folded two questions into one enum: which way a packet travels, and which connection
 * phase it belongs to. NeoForge separated them -- direction is vanilla's {@code PacketFlow},
 * phase is which registrar method you call. The enum is reproduced whole because mods read the
 * constants directly ({@code PLAY_TO_CLIENT} appears in 101 jars) and switch on them.
 *
 * <h2>Login constants</h2>
 *
 * {@code LOGIN_TO_*} are kept so switches over the enum stay exhaustive and so field reads
 * resolve, but Forge's login-phase packet system has no counterpart here: NeoForge replaced it
 * with the configuration phase, which has a different handshake shape entirely. A channel that
 * registers login messages will link and then never exchange them. Only four corpus jars touch
 * the login events, so this is a narrow gap rather than a common one.
 */
public enum NetworkDirection {
    PLAY_TO_SERVER,
    PLAY_TO_CLIENT,
    LOGIN_TO_SERVER,
    LOGIN_TO_CLIENT;

    /**
     * The side that receives a packet sent in this direction.
     *
     * Note this is the *logical* side, so a packet sent to the client is received on
     * {@code LogicalSide.CLIENT} even when that client is hosting the integrated server.
     */
    public LogicalSide getReceptionSide() {
        return switch (this) {
            case PLAY_TO_CLIENT, LOGIN_TO_CLIENT -> LogicalSide.CLIENT;
            case PLAY_TO_SERVER, LOGIN_TO_SERVER -> LogicalSide.SERVER;
        };
    }

    public LogicalSide getOriginationSide() {
        return getReceptionSide().isClient() ? LogicalSide.SERVER : LogicalSide.CLIENT;
    }

    /**
     * Recovers the Forge direction from the flow NeoForge reports on a received packet.
     *
     * {@code PacketFlow} is named from the receiver's point of view: SERVERBOUND means this
     * packet is arriving at the server, which is Forge's PLAY_TO_SERVER. The play phase is
     * assumed because that is the only phase this shim's channels register in.
     */
    public static NetworkDirection fromFlow(PacketFlow flow) {
        return flow == PacketFlow.SERVERBOUND ? PLAY_TO_SERVER : PLAY_TO_CLIENT;
    }
}
