package easyport.neobridge;

import java.util.function.Consumer;

import net.minecraftforge.eventbus.api.Event;
import net.minecraftforge.eventbus.api.EventPriority;

/**
 * Wraps a Forge 1.20.1 event bus as the NeoForge interface a translated mod expects.
 *
 * A wrapper rather than an alias, and that is forced rather than chosen. The forward direction
 * could alias — {@code MinecraftForge.EVENT_BUS} *is* {@code NeoForge.EVENT_BUS}, verified as
 * reference equality — because NeoForge's bus already implemented the interface Forge mods name.
 * Nothing in Forge 1.20.1 implements {@code net.neoforged.bus.api.IEventBus}, so the only way to
 * present one is to build it.
 *
 * The distinction matters for exactly one reason, and it is the reason the forward note exists:
 * listeners must land on the bus the loader actually dispatches from. They do — every method
 * here forwards to the real Forge bus and nothing is queued or copied.
 */
public final class EventBusShim implements net.neoforged.bus.api.IEventBus {

    private final net.minecraftforge.eventbus.api.IEventBus delegate;

    public EventBusShim(net.minecraftforge.eventbus.api.IEventBus delegate) {
        this.delegate = delegate;
    }

    /** The bus being wrapped, for shims that need to hand Forge code the real thing. */
    public net.minecraftforge.eventbus.api.IEventBus unwrap() {
        return delegate;
    }

    @Override public void register(Object target) { delegate.register(target); }

    @Override public void unregister(Object target) { delegate.unregister(target); }

    @Override public <T extends Event> void addListener(Consumer<T> consumer) {
        delegate.addListener(consumer);
    }

    @Override public <T extends Event> void addListener(Class<T> eventType, Consumer<T> consumer) {
        delegate.addListener(EventPriority.NORMAL, false, eventType, consumer);
    }

    @Override public <T extends Event> void addListener(EventPriority priority, Consumer<T> consumer) {
        delegate.addListener(priority, consumer);
    }

    @Override public <T extends Event> void addListener(EventPriority priority, Class<T> eventType,
                                                        Consumer<T> consumer) {
        delegate.addListener(priority, false, eventType, consumer);
    }

    @Override public <T extends Event> void addListener(EventPriority priority,
                                                        boolean receiveCancelled,
                                                        Consumer<T> consumer) {
        delegate.addListener(priority, receiveCancelled, consumer);
    }

    @Override public <T extends Event> void addListener(EventPriority priority,
                                                        boolean receiveCancelled,
                                                        Class<T> eventType, Consumer<T> consumer) {
        delegate.addListener(priority, receiveCancelled, eventType, consumer);
    }

    @Override public <T extends Event> void addListener(boolean receiveCancelled,
                                                        Consumer<T> consumer) {
        delegate.addListener(EventPriority.NORMAL, receiveCancelled, consumer);
    }

    @Override public <T extends Event> void addListener(boolean receiveCancelled,
                                                        Class<T> eventType, Consumer<T> consumer) {
        delegate.addListener(EventPriority.NORMAL, receiveCancelled, eventType, consumer);
    }

    /**
     * Posts, and returns the event rather than a cancellation flag.
     *
     * NeoForge changed the return type, and mods use it — {@code var result = bus.post(e)} then
     * reads fields off the result. Returning the argument is exactly right: the bus mutates the
     * event in place on both loaders.
     */
    @Override public <T extends Event> T post(T event) {
        delegate.post(event);
        return event;
    }

    /**
     * Forge 1.20.1 has no priority-scoped post. Posting normally is the closest honest answer:
     * every listener still runs, in the bus's own priority order, which is what the caller wanted
     * a subset of.
     */
    @Override public <T extends Event> T post(EventPriority priority, T event) {
        delegate.post(event);
        return event;
    }

    /** Forge 1.20.1 buses have no explicit start; they dispatch from construction. */
    @Override public void start() { }
}
