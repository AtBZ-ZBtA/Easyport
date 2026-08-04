package easyport.bridge;

import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraftforge.network.NetworkEvent;
import net.minecraftforge.network.simple.SimpleChannel;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.network.event.RegisterPayloadHandlersEvent;
import net.neoforged.neoforge.network.registration.HandlerThread;
import net.neoforged.neoforge.network.registration.PayloadRegistrar;

/**
 * Registers every Forge channel with NeoForge's payload system.
 *
 * This is the join between the two networking models. {@link SimpleChannel} collects channels as
 * mods create them and knows how to encode, decode and dispatch its own messages; this class
 * hands NeoForge one payload type per channel, wired to those three operations.
 *
 * <h2>Why it has to happen here</h2>
 *
 * NeoForge accepts payload registrations only while {@code RegisterPayloadHandlersEvent} is
 * being handled -- registering earlier or later throws. Forge had no such window, so mods create
 * channels in their constructors or during common setup, both of which run before this event.
 * Collecting them and draining the list at the right moment is what makes the unchanged mod code
 * work.
 *
 * <h2>Thread choice</h2>
 *
 * Channels register with {@code HandlerThread.NETWORK}, which is not NeoForge's default. Forge
 * handlers run on the network thread and reach the main thread by calling
 * {@code context.enqueueWork(...)} themselves -- that call is in 143 corpus jars and is the most
 * common single line in the whole networking surface. Registering on the main thread instead
 * would leave all of those enqueueing work from the thread they are already on, which changes
 * when the body actually runs relative to the rest of the tick.
 */
@EventBusSubscriber(modid = "forge_compat")
public final class NetworkBridge {

    @SubscribeEvent
    public static void onRegisterPayloads(RegisterPayloadHandlersEvent event) {
        for (SimpleChannel channel : SimpleChannel.created()) {
            register(event, channel);
        }
        SimpleChannel.closeRegistration();
    }

    private static void register(RegisterPayloadHandlersEvent event, SimpleChannel channel) {
        // The registrar's namespace must match the payload id's namespace, so it comes from the
        // channel's own ResourceLocation rather than from forge-compat.
        PayloadRegistrar registrar = event.registrar(channel.name().getNamespace())
                .versioned(channel.version())
                .optional()
                .executesOn(HandlerThread.NETWORK);

        StreamCodec<RegistryFriendlyByteBuf, ForgeChannelPayload> codec = StreamCodec.of(
                (buf, payload) -> channel.encode(buf, payload.message()),
                buf -> new ForgeChannelPayload(channel.payloadType(), channel.decode(buf)));

        // Bidirectional regardless of what the mod declared. Forge tracked a direction per
        // message and this bridge registers per channel, so the union is the only option that
        // does not drop traffic; the cost is that a wrong-way send is no longer refused.
        registrar.playBidirectional(channel.payloadType(), codec,
                (payload, context) -> channel.handle(payload.message(), new NetworkEvent.Context(context)));
    }

    private NetworkBridge() {}
}
