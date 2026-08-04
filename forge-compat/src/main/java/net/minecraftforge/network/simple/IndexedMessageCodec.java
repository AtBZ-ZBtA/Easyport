package net.minecraftforge.network.simple;

/**
 * Shim for {@code IndexedMessageCodec}, which exists here only for its nested
 * {@code MessageHandler}.
 *
 * {@code SimpleChannel.registerMessage} is declared to return {@code MessageHandler}, so the
 * type has to exist for 106 corpus jars to link even though almost none of them keep the value.
 * Forge used the returned handler to chain login-phase configuration, which has no counterpart
 * in NeoForge's configuration phase, so the returned instance carries nothing.
 *
 * The codec class itself is empty. Forge's real one owned the discriminator table; here that
 * lives on {@link SimpleChannel} directly, since there is no reason to split it across two
 * classes when only the outer name is ever referenced.
 */
public class IndexedMessageCodec {

    /** Inert. See the class javadoc for why it still exists. */
    public static class MessageHandler<M> {
    }

    private IndexedMessageCodec() {}
}
