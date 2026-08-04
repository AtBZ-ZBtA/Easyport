package net.minecraftforge.network;

import java.util.function.Predicate;
import java.util.function.Supplier;

import net.minecraft.resources.ResourceLocation;
import net.minecraftforge.network.simple.SimpleChannel;

/**
 * Shim for {@code NetworkRegistry} and its {@code ChannelBuilder}, the entry point to Forge
 * networking. 85 corpus jars call it directly and 74 go through the builder.
 *
 * NeoForge has no equivalent -- channels as a concept are gone, replaced by payload types
 * registered against a namespace. So this creates a {@link SimpleChannel}, which records itself
 * for later registration; see that class for how the deferral works.
 *
 * <h2>Version predicates</h2>
 *
 * Forge negotiated a protocol version per channel and could refuse a connection whose peer
 * disagreed. NeoForge does its own version check per payload type, driven by
 * {@code versioned(String)} on the registrar, and has no way to consult an arbitrary predicate.
 *
 * The predicates are therefore accepted and discarded, and channels register as {@code optional}
 * so a peer lacking the mod is not disconnected outright. The practical difference is that a
 * version *mismatch* which Forge would have rejected at handshake now surfaces later, as a
 * decode failure on the first packet. Louder would be better; there is no hook for it.
 */
public class NetworkRegistry {

    /**
     * The direct form, called by 82 jars.
     *
     * The version supplier is resolved eagerly here rather than kept -- it is invoked during
     * registration anyway, and holding it would mean a channel's declared version could change
     * after the payload type was registered under the old one.
     */
    public static SimpleChannel newSimpleChannel(ResourceLocation name,
                                                 Supplier<String> networkProtocolVersion,
                                                 Predicate<String> clientAcceptedVersions,
                                                 Predicate<String> serverAcceptedVersions) {
        String version = networkProtocolVersion != null ? networkProtocolVersion.get() : "1";
        return new SimpleChannel(name, version);
    }

    /**
     * Forge's raw-packet channel. See {@link net.minecraftforge.network.event.EventNetworkChannel}
     * -- it links and does not carry traffic.
     */
    public static net.minecraftforge.network.event.EventNetworkChannel newEventChannel(
            ResourceLocation name,
            Supplier<String> networkProtocolVersion,
            Predicate<String> clientAcceptedVersions,
            Predicate<String> serverAcceptedVersions) {
        return new net.minecraftforge.network.event.EventNetworkChannel(name);
    }

    /** Forge's fluent form. Order of calls is free, so nothing is validated until build. */
    public static class ChannelBuilder {

        private ResourceLocation name;
        private Supplier<String> version;

        public static ChannelBuilder named(ResourceLocation name) {
            ChannelBuilder builder = new ChannelBuilder();
            builder.name = name;
            return builder;
        }

        public ChannelBuilder networkProtocolVersion(Supplier<String> version) {
            this.version = version;
            return this;
        }

        /** Accepted and dropped; see the class javadoc. */
        public ChannelBuilder clientAcceptedVersions(Predicate<String> predicate) {
            return this;
        }

        /** Accepted and dropped; see the class javadoc. */
        public ChannelBuilder serverAcceptedVersions(Predicate<String> predicate) {
            return this;
        }

        public SimpleChannel simpleChannel() {
            return newSimpleChannel(name, version, null, null);
        }

        public net.minecraftforge.network.event.EventNetworkChannel eventNetworkChannel() {
            return newEventChannel(name, version, null, null);
        }
    }

    private NetworkRegistry() {}
}
