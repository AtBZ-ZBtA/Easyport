package easyport.bridge;

import net.minecraft.network.protocol.common.custom.CustomPacketPayload;

/**
 * Carries a Forge message across NeoForge's payload-based networking.
 *
 * The two systems disagree about where a packet's identity lives. A Forge message is a plain
 * object; the channel knows how to encode it and a numeric discriminator says which kind it is.
 * A NeoForge payload carries its own identity -- it implements {@code CustomPacketPayload} and
 * names a {@code Type} -- and the codec is registered against that type.
 *
 * Reconciling them by rewriting every mod's message classes into payloads would mean generating
 * a {@code Type}, a {@code StreamCodec} and an interface implementation per class, for hundreds
 * of classes. Instead one payload type is registered per *channel*, and this wraps whichever
 * message is travelling. The discriminator moves to the front of the buffer, which is where
 * Forge put it anyway, so the wire format is close to unchanged.
 *
 * <h2>Not a byte[] copy</h2>
 *
 * The message is held as a live object and encoded directly into the outgoing buffer by the
 * channel's codec, rather than being serialised into a byte array that then gets embedded. That
 * matters beyond efficiency: play packets travel on a {@code RegistryFriendlyByteBuf}, and
 * encoders that read registry data from the buffer -- item stacks, holders, anything
 * registry-backed in 1.20.5+ -- would fail against a detached buffer that has no registry access.
 */
public final class ForgeChannelPayload implements CustomPacketPayload {

    private final CustomPacketPayload.Type<ForgeChannelPayload> type;
    private final Object message;

    public ForgeChannelPayload(CustomPacketPayload.Type<ForgeChannelPayload> type, Object message) {
        this.type = type;
        this.message = message;
    }

    /** The wrapped Forge message, handed back to the mod's own handler. */
    public Object message() {
        return message;
    }

    @Override
    public CustomPacketPayload.Type<? extends CustomPacketPayload> type() {
        return type;
    }
}
