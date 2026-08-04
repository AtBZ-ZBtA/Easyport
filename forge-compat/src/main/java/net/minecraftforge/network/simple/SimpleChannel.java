package net.minecraftforge.network.simple;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.BiConsumer;
import java.util.function.Function;
import java.util.function.Supplier;

import easyport.bridge.ForgeChannelPayload;
import net.minecraft.network.Connection;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import net.minecraftforge.network.NetworkDirection;
import net.minecraftforge.network.NetworkEvent;
import net.minecraftforge.network.PacketDistributor;

/**
 * Shim for {@code SimpleChannel}, referenced by 162 of 433 corpus jars.
 *
 * This is the centre of the networking bridge. Forge's model is: make a channel, register
 * message types against small integer indices with an encoder, a decoder and a handler, then
 * send objects. NeoForge's model is: register self-describing payload types with stream codecs
 * during a specific mod-bus event, then send payloads.
 *
 * The bridge keeps Forge's model intact on the mod-facing side and collapses it onto a single
 * NeoForge payload type per channel. {@link ForgeChannelPayload} explains the wire format;
 * {@code easyport.bridge.NetworkBridge} does the registration.
 *
 * <h2>Deferred registration</h2>
 *
 * Forge let a channel be created and populated at any time. NeoForge only accepts payload
 * registrations during {@code RegisterPayloadHandlersEvent}. So construction here records the
 * channel in {@link #created()} and does nothing else; the bridge drains that list when the
 * event fires. Mod constructors and common-setup both run before it, which is where essentially
 * all channel setup happens.
 *
 * A channel created *after* the event cannot be registered at all. That is reported loudly
 * rather than ignored -- silence would look like a mod whose packets simply never arrive.
 */
public class SimpleChannel {

    /** Every channel built so far, in creation order, awaiting or past registration. */
    private static final List<SimpleChannel> CREATED = new ArrayList<>();
    private static boolean registrationClosed = false;

    private final ResourceLocation name;
    private final String version;
    private final CustomPacketPayload.Type<ForgeChannelPayload> payloadType;

    /** Registrations by wire discriminator, and the same set indexed by message class. */
    private final Map<Integer, Registration<?>> byIndex = new HashMap<>();
    private final Map<Class<?>, Registration<?>> byClass = new HashMap<>();

    public SimpleChannel(ResourceLocation name, String version) {
        this.name = name;
        this.version = version;
        this.payloadType = new CustomPacketPayload.Type<>(name);
        synchronized (CREATED) {
            CREATED.add(this);
            if (registrationClosed) {
                System.err.println("[forge-compat] channel " + name + " was created after payload "
                        + "registration closed; its packets will not be delivered");
            }
        }
    }

    public static List<SimpleChannel> created() {
        synchronized (CREATED) {
            return List.copyOf(CREATED);
        }
    }

    /** Called by the bridge once {@code RegisterPayloadHandlersEvent} has been handled. */
    public static void closeRegistration() {
        synchronized (CREATED) {
            registrationClosed = true;
        }
    }

    public ResourceLocation name() {
        return name;
    }

    public String version() {
        return version;
    }

    public CustomPacketPayload.Type<ForgeChannelPayload> payloadType() {
        return payloadType;
    }

    // ------------------------------------------------------------------ registration

    private record Registration<M>(int index, Class<M> type,
                                   BiConsumer<M, FriendlyByteBuf> encoder,
                                   Function<FriendlyByteBuf, M> decoder,
                                   BiConsumer<M, Supplier<NetworkEvent.Context>> handler) {}

    /**
     * The dominant registration form -- 106 corpus jars call exactly this overload.
     *
     * Returns null where Forge returned a {@code MessageHandler} used for further chaining. Only
     * a handful of jars keep the return value, and those that do use it to attach login-phase
     * behaviour that has no counterpart here anyway.
     */
    public <M> IndexedMessageCodec.MessageHandler<M> registerMessage(
            int index, Class<M> messageType,
            BiConsumer<M, FriendlyByteBuf> encoder,
            Function<FriendlyByteBuf, M> decoder,
            BiConsumer<M, Supplier<NetworkEvent.Context>> handler) {
        return registerMessage(index, messageType, encoder, decoder, handler, Optional.empty());
    }

    public <M> IndexedMessageCodec.MessageHandler<M> registerMessage(
            int index, Class<M> messageType,
            BiConsumer<M, FriendlyByteBuf> encoder,
            Function<FriendlyByteBuf, M> decoder,
            BiConsumer<M, Supplier<NetworkEvent.Context>> handler,
            Optional<NetworkDirection> direction) {
        Registration<M> reg = new Registration<>(index, messageType, encoder, decoder, handler);
        byIndex.put(index, reg);
        byClass.put(messageType, reg);
        return new IndexedMessageCodec.MessageHandler<>();
    }

    public <M> MessageBuilder<M> messageBuilder(Class<M> type, int index) {
        return new MessageBuilder<>(this, type, index);
    }

    public <M> MessageBuilder<M> messageBuilder(Class<M> type, int index, NetworkDirection direction) {
        return new MessageBuilder<>(this, type, index);
    }

    /**
     * Forge's fluent alternative to {@code registerMessage}, used by 26 corpus jars.
     *
     * Direction is accepted and dropped: NeoForge channels here are registered bidirectionally,
     * so a message declared one-way still travels. The looser check means a mod that sends a
     * packet the wrong way gets no complaint from the network layer, where Forge would have
     * refused it. Worth knowing, but it turns a hard error into a mod bug that was already there.
     */
    public static class MessageBuilder<M> {
        private final SimpleChannel channel;
        private final Class<M> type;
        private final int index;
        private BiConsumer<M, FriendlyByteBuf> encoder;
        private Function<FriendlyByteBuf, M> decoder;
        private BiConsumer<M, Supplier<NetworkEvent.Context>> handler;

        MessageBuilder(SimpleChannel channel, Class<M> type, int index) {
            this.channel = channel;
            this.type = type;
            this.index = index;
        }

        public MessageBuilder<M> encoder(BiConsumer<M, FriendlyByteBuf> encoder) {
            this.encoder = encoder;
            return this;
        }

        public MessageBuilder<M> decoder(Function<FriendlyByteBuf, M> decoder) {
            this.decoder = decoder;
            return this;
        }

        public MessageBuilder<M> consumerMainThread(BiConsumer<M, Supplier<NetworkEvent.Context>> handler) {
            this.handler = handler;
            return this;
        }

        public MessageBuilder<M> consumerNetworkThread(BiConsumer<M, Supplier<NetworkEvent.Context>> handler) {
            this.handler = handler;
            return this;
        }

        public void add() {
            channel.registerMessage(index, type, encoder, decoder, handler);
        }
    }

    // ------------------------------------------------------------------ codec, driven by the bridge

    @SuppressWarnings("unchecked")
    public void encode(FriendlyByteBuf buf, Object message) {
        Registration<Object> reg = (Registration<Object>) lookup(message.getClass());
        if (reg == null) {
            throw new IllegalArgumentException("no registration on channel " + name
                    + " for message type " + message.getClass().getName());
        }
        buf.writeVarInt(reg.index());
        reg.encoder().accept(message, buf);
    }

    public Object decode(FriendlyByteBuf buf) {
        int index = buf.readVarInt();
        Registration<?> reg = byIndex.get(index);
        if (reg == null) {
            throw new IllegalArgumentException("no registration on channel " + name
                    + " for discriminator " + index);
        }
        return reg.decoder().apply(buf);
    }

    @SuppressWarnings("unchecked")
    public void handle(Object message, NetworkEvent.Context context) {
        Registration<Object> reg = (Registration<Object>) lookup(message.getClass());
        if (reg == null || reg.handler() == null) return;
        reg.handler().accept(message, () -> context);
    }

    /**
     * Finds the registration for a message class, walking up the hierarchy.
     *
     * The exact class usually matches, but Forge matched on the registered type, so a subclass
     * of a registered message was legal and some mods rely on it.
     */
    private Registration<?> lookup(Class<?> messageClass) {
        for (Class<?> k = messageClass; k != null && k != Object.class; k = k.getSuperclass()) {
            Registration<?> reg = byClass.get(k);
            if (reg != null) return reg;
        }
        return null;
    }

    // ------------------------------------------------------------------ sending

    public void sendToServer(Object message) {
        net.neoforged.neoforge.network.PacketDistributor.sendToServer(wrap(message));
    }

    public void send(PacketDistributor.PacketTarget target, Object message) {
        target.send(wrap(message));
    }

    /**
     * Forge's connection-targeted send, used by 51 jars.
     *
     * The direction argument decides which way to push it; the connection itself is not used,
     * because NeoForge's distributor addresses players and levels rather than raw connections.
     * Server-bound is exact. Client-bound through a bare {@code Connection} has no equivalent
     * -- there is no way back from a connection to its {@code ServerPlayer} through public API
     * -- so it is reported rather than silently dropped.
     */
    public void sendTo(Object message, Connection connection, NetworkDirection direction) {
        if (direction == NetworkDirection.PLAY_TO_SERVER) {
            sendToServer(message);
            return;
        }
        System.err.println("[forge-compat] sendTo(" + direction + ") on channel " + name
                + " has no NeoForge equivalent and was dropped");
    }

    private ForgeChannelPayload wrap(Object message) {
        return new ForgeChannelPayload(payloadType, message);
    }
}
