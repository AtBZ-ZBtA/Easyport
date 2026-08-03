package net.minecraftforge.eventbus.api;

import java.util.function.Consumer;

import net.neoforged.bus.api.Event;
import net.neoforged.bus.api.EventPriority;

/**
 * Delegating implementation of the Forge-facing {@link IEventBus}.
 *
 * Pure forwarding — every listener registered here lands on the real NeoForge bus, so events
 * dispatch exactly as they would without the shim. The wrapper exists only so that the *type*
 * a translated mod names in its descriptors resolves, since NeoForge's own bus implementations
 * cannot be retrofitted to implement a sub-interface declared here.
 *
 * Consequence worth knowing: {@code MinecraftForge.EVENT_BUS != NeoForge.EVENT_BUS} by
 * identity, though they are the same bus underneath. Nothing in the corpus compares buses by
 * reference, and correctness depends on dispatch rather than identity.
 */
final class ForgeEventBus implements IEventBus {

    private final net.neoforged.bus.api.IEventBus delegate;

    ForgeEventBus(net.neoforged.bus.api.IEventBus delegate) {
        this.delegate = delegate;
    }

    /**
     * Preserves Forge's laxer contract: registering an object with no handlers is a no-op.
     *
     * NeoForge throws IllegalArgumentException here, Forge 1.20.1 accepted it silently. That
     * difference is not hypothetical — it is a hard load failure on real corpus mods
     * (additional_lights calls {@code EVENT_BUS.register(this)} from a class with no
     * handlers at all), and it fires during mod construction, so it takes the whole mod down.
     *
     * Checking up front rather than catching: the exception message is not part of any
     * contract, and swallowing a broad IllegalArgumentException would also hide real errors
     * raised from inside a legitimate registration.
     */
    @Override public void register(Object target) {
        if (!hasEventHandlers(target)) return;
        delegate.register(target);
    }

    /** True if the target declares at least one {@code @SubscribeEvent} method. */
    private static boolean hasEventHandlers(Object target) {
        Class<?> type = (target instanceof Class<?> c) ? c : target.getClass();
        for (Class<?> k = type; k != null && k != Object.class; k = k.getSuperclass()) {
            for (var method : k.getDeclaredMethods()) {
                if (method.isAnnotationPresent(net.neoforged.bus.api.SubscribeEvent.class)) return true;
            }
        }
        return false;
    }
    @Override public void unregister(Object target) { delegate.unregister(target); }
    @Override public void start() { delegate.start(); }

    @Override public <T extends Event> void addListener(Consumer<T> consumer) {
        delegate.addListener(consumer);
    }
    @Override public <T extends Event> void addListener(Class<T> type, Consumer<T> consumer) {
        delegate.addListener(type, consumer);
    }
    @Override public <T extends Event> void addListener(EventPriority priority, Consumer<T> consumer) {
        delegate.addListener(priority, consumer);
    }
    @Override public <T extends Event> void addListener(EventPriority priority, Class<T> type,
                                                        Consumer<T> consumer) {
        delegate.addListener(priority, type, consumer);
    }
    @Override public <T extends Event> void addListener(EventPriority priority, boolean receiveCancelled,
                                                        Consumer<T> consumer) {
        delegate.addListener(priority, receiveCancelled, consumer);
    }
    @Override public <T extends Event> void addListener(EventPriority priority, boolean receiveCancelled,
                                                        Class<T> type, Consumer<T> consumer) {
        delegate.addListener(priority, receiveCancelled, type, consumer);
    }
    @Override public <T extends Event> void addListener(boolean receiveCancelled, Consumer<T> consumer) {
        delegate.addListener(receiveCancelled, consumer);
    }
    @Override public <T extends Event> void addListener(boolean receiveCancelled, Class<T> type,
                                                        Consumer<T> consumer) {
        delegate.addListener(receiveCancelled, type, consumer);
    }

    @Override public <T extends Event> T post(T event) { return delegate.post(event); }
    @Override public <T extends Event> T post(EventPriority priority, T event) {
        return delegate.post(priority, event);
    }
}
