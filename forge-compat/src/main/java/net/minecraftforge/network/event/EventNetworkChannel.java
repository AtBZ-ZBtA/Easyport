package net.minecraftforge.network.event;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;

import net.minecraft.resources.ResourceLocation;
import net.minecraftforge.network.NetworkEvent;

/**
 * Shim for {@code EventNetworkChannel}, Forge's raw-packet channel. 8 corpus jars, architectury
 * among them.
 *
 * Where {@link net.minecraftforge.network.simple.SimpleChannel} knows how to encode typed
 * messages, this one handed the listener a buffer and got out of the way — mods used it to speak
 * a protocol of their own, often to stay compatible with a Fabric counterpart.
 *
 * <h2>Registration only</h2>
 *
 * Listeners are recorded and never invoked. Bridging them would mean registering a NeoForge
 * payload per channel, as the SimpleChannel bridge does, and then synthesising a
 * {@code NetworkEvent} — but Forge's raw channel delivered the whole custom-payload packet,
 * including framing that NeoForge's payload system consumes before a handler ever sees it. There
 * is no faithful reconstruction available at that layer.
 *
 * So this is a link-only shim, in the same category as the events NeoForge removed: it exists so
 * the mod loads, and it does not pretend to carry traffic. Architectury is blocked on nothing
 * else at this point, and it blocks twelve mods.
 *
 * Recorded rather than silent — a mod relying on a raw channel will find it inert, and that is a
 * real gap, listed alongside the others in api-report.
 */
public class EventNetworkChannel {

    private final ResourceLocation name;
    private final List<Object> listeners = new ArrayList<>();

    public EventNetworkChannel(ResourceLocation name) {
        this.name = name;
    }

    public ResourceLocation name() {
        return name;
    }

    /** Accepted and held. See the class javadoc for why it is never called. */
    public void addListener(Consumer<NetworkEvent> listener) {
        listeners.add(listener);
    }

    /** Accepted and held. See the class javadoc for why it is never called. */
    public void registerObject(Object object) {
        listeners.add(object);
    }

    public void unregisterObject(Object object) {
        listeners.remove(object);
    }
}
