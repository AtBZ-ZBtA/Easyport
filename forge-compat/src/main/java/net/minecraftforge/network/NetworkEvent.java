package net.minecraftforge.network;

import java.util.concurrent.CompletableFuture;

import net.minecraft.server.level.ServerPlayer;
import net.neoforged.neoforge.network.handling.IPayloadContext;

/**
 * Shim for {@code NetworkEvent} and, far more importantly, its nested {@code Context}.
 *
 * {@code NetworkEvent.Context} is the single most-referenced networking type in the corpus --
 * 166 of 433 jars -- because every message handler receives one. Its three hot methods are
 * {@code enqueueWork} (143 jars), {@code setPacketHandled} (140) and {@code getSender} (133).
 *
 * NeoForge's equivalent is {@code IPayloadContext}, which is close enough in shape that this is
 * a thin adapter rather than a reimplementation.
 */
public class NetworkEvent {

    /**
     * Forge's handler context, backed by NeoForge's.
     *
     * Handlers receive it as {@code Supplier<Context>} rather than directly -- a Forge quirk
     * this shim has to reproduce because it is baked into every handler's descriptor.
     */
    public static class Context {

        private final IPayloadContext delegate;
        private final NetworkDirection direction;

        public Context(IPayloadContext delegate) {
            this.delegate = delegate;
            this.direction = NetworkDirection.fromFlow(delegate.flow());
        }

        /**
         * Defers work to the main thread.
         *
         * Direct passthrough, and the reason channels register with {@code HandlerThread.NETWORK}:
         * Forge handlers run on the network thread and call this to reach the main one. Running
         * them on the main thread instead would make this a no-op-ish self-schedule and quietly
         * change ordering guarantees the mod is relying on.
         */
        public CompletableFuture<Void> enqueueWork(Runnable runnable) {
            return delegate.enqueueWork(runnable);
        }

        /**
         * The player who sent this packet, or null when it arrived on the client.
         *
         * Forge returned null client-side and mods branch on that, so the null is part of the
         * contract rather than a failure. NeoForge's {@code player()} returns the local player
         * on the client, which is not the same thing at all -- hence the flow check rather than
         * a plain cast.
         */
        public ServerPlayer getSender() {
            if (delegate.flow() != net.minecraft.network.protocol.PacketFlow.SERVERBOUND) return null;
            return delegate.player() instanceof ServerPlayer sp ? sp : null;
        }

        public NetworkDirection getDirection() {
            return direction;
        }

        /**
         * No-op.
         *
         * Forge required handlers to acknowledge a packet or it logged a warning and, for login
         * packets, stalled the handshake. NeoForge has no such handshake -- delivery is
         * acknowledged by the handler returning -- so there is nothing to record. Kept because
         * 140 jars call it, and every one of them would fail to link without it.
         */
        public void setPacketHandled(boolean handled) {
            // intentionally empty; see javadoc
        }

        /** The NeoForge context underneath, for shim code that needs the real thing. */
        public IPayloadContext unwrap() {
            return delegate;
        }
    }

    /**
     * The raw-channel payload events, 7 corpus jars each.
     *
     * Posted by {@link net.minecraftforge.network.event.EventNetworkChannel}, which is itself a
     * link-only shim, so nothing constructs these. They exist because a listener names them as
     * its parameter type and the class has to resolve for the mod to load -- architectury is
     * blocked on exactly this.
     *
     * They extend NeoForge's {@code Event} so that a listener registration is at least valid
     * rather than throwing at registration time. It will never fire; see EventNetworkChannel for
     * why the raw channel cannot be bridged.
     */
    public static class ClientCustomPayloadEvent extends net.neoforged.bus.api.Event {
        private final net.minecraft.network.FriendlyByteBuf payload;
        private final Context source;

        public ClientCustomPayloadEvent(net.minecraft.network.FriendlyByteBuf payload, Context source) {
            this.payload = payload;
            this.source = source;
        }

        public net.minecraft.network.FriendlyByteBuf getPayload() {
            return payload;
        }

        public Context getSource() {
            return source;
        }
    }

    public static class ServerCustomPayloadEvent extends net.neoforged.bus.api.Event {
        private final net.minecraft.network.FriendlyByteBuf payload;
        private final Context source;

        public ServerCustomPayloadEvent(net.minecraft.network.FriendlyByteBuf payload, Context source) {
            this.payload = payload;
            this.source = source;
        }

        public net.minecraft.network.FriendlyByteBuf getPayload() {
            return payload;
        }

        public Context getSource() {
            return source;
        }
    }

    private NetworkEvent() {}
}
