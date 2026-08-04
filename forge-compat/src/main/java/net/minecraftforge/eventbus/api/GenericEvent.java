package net.minecraftforge.eventbus.api;

import net.neoforged.bus.api.Event;

/**
 * Shim for {@code GenericEvent<T>}, which NeoForge's event bus dropped entirely.
 *
 * Forge's bus could filter dispatch on a generic type argument the JVM has erased, by having the
 * event carry its own type token and having listeners register a matching filter. It let one
 * event class serve many payload types -- {@code AttachCapabilitiesEvent<Entity>} and
 * {@code AttachCapabilitiesEvent<ItemStack>} were the same class, distinguished only by the
 * token. NeoForge replaced the pattern with separate event classes and removed the machinery.
 *
 * <h2>Why this is a shim rather than a rename</h2>
 *
 * There is nothing to rename it to. {@code bus-8.0.5.jar} contains no generic-event type at all,
 * so the class has to be supplied outright. It extends NeoForge's {@code Event} so instances are
 * still postable -- the same trick as {@link IEventBus} and {@code TickEvent}.
 *
 * <h2>Who needs it</h2>
 *
 * Five corpus jars *define* generic events by subclassing this: placebo, create, gtceu, kubejs
 * and titanium. Placebo is the one that matters -- eight mods depend on it, and its
 * {@code RegistryEvent} extends this directly, so the whole library fails to load without it.
 *
 * A further 35 jars *listen* for generic events via {@code addGenericListener}. Their side is
 * handled on {@link IEventBus}, and is more compromised than this class is; see the note there.
 */
public class GenericEvent<T> extends Event {

    private final Class<T> type;

    /**
     * The no-arg form, for subclasses that never carried a token.
     *
     * Forge allowed this and left the type null, which then matched no filter. Reproduced rather
     * than rejected: a subclass compiled against Forge may well call it, and throwing here would
     * turn a mod that merely posts an unfilterable event into a mod that fails to construct.
     */
    public GenericEvent() {
        this.type = null;
    }

    protected GenericEvent(Class<T> type) {
        this.type = type;
    }

    public Class<T> getGenericType() {
        return type;
    }
}
