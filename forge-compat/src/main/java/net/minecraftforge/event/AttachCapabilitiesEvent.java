package net.minecraftforge.event;

import java.util.LinkedHashMap;
import java.util.Map;

import net.minecraft.resources.ResourceLocation;
import net.minecraftforge.common.capabilities.ICapabilityProvider;
import net.minecraftforge.eventbus.api.GenericEvent;

/**
 * Link-only shim for {@code AttachCapabilitiesEvent<T>}, referenced by 86 corpus jars.
 *
 * <h2>Why nothing posts it</h2>
 *
 * This is how Forge let a mod add a capability to an object it did not own -- attach an item
 * handler to someone else's block entity, or to a vanilla one. NeoForge removed the mechanism
 * outright: capabilities are registered against a type up front rather than attached to
 * instances, so there is no moment in the lifecycle that corresponds to "this object was just
 * constructed, does anyone want to add something to it".
 *
 * {@code easyport.bridge.CapabilityBridge} bridges the other half of the capability story -- an
 * object that implements {@code ICapabilityProvider} itself is reachable, because the bridge can
 * register a provider for its whole type and forward. This half has no equivalent construction:
 * the set of objects a mod wants to attach to is only known when the event fires, and it never
 * does.
 *
 * So this exists to let the mod load. Attachments are recorded and never consulted.
 *
 * <h2>What that costs</h2>
 *
 * Real functionality, and it is worth being precise about which. A mod whose own blocks carry
 * capabilities is fine -- that goes through the bridge. A mod that adds capabilities to *other*
 * mods' or vanilla's objects loses that integration silently. Cross-mod item and fluid transfer
 * is the common case.
 *
 * Closing it properly needs a per-type registration derived from what mods attach at runtime,
 * which cannot be known before the attachments happen. The honest fix is per-mod: recognise the
 * attachment and convert it into a NeoForge registration for that type. That is real work and
 * is not done.
 */
public class AttachCapabilitiesEvent<T> extends GenericEvent<T> {

    private final T object;
    private final Map<ResourceLocation, ICapabilityProvider> attached = new LinkedHashMap<>();

    public AttachCapabilitiesEvent(Class<T> type, T object) {
        super(type);
        this.object = object;
    }

    public T getObject() {
        return object;
    }

    /** Recorded, never consulted. See the class javadoc. 84 corpus jars call this. */
    public void addCapability(ResourceLocation key, ICapabilityProvider provider) {
        attached.put(key, provider);
    }

    public Map<ResourceLocation, ICapabilityProvider> getCapabilities() {
        return java.util.Collections.unmodifiableMap(attached);
    }

    /**
     * Forge's invalidation hook.
     *
     * A no-op: nothing here holds a capability long enough to need invalidating, because nothing
     * ever resolves one.
     */
    public void addListener(Runnable listener) {
        // intentionally empty; see javadoc
    }
}
