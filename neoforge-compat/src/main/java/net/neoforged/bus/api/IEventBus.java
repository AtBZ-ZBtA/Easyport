package net.neoforged.bus.api;

import java.util.function.Consumer;

import net.minecraftforge.eventbus.api.Event;
import net.minecraftforge.eventbus.api.EventPriority;

/**
 * Shim: NeoForge's event bus interface, expressed in Forge 1.20.1's types.
 *
 * <h2>Why the signatures name Forge types</h2>
 *
 * {@code net.neoforged.bus.api.Event} and {@code EventPriority} are on the must-rename list —
 * the bus dispatches on a posted object's exact class, so a shimmed copy would be a type nothing
 * ever posts. By the time a translated mod calls anything here, its references already say
 * {@code net.minecraftforge.eventbus.api.Event}. Declaring this interface in terms of NeoForge's
 * own types would therefore produce descriptors no call site matches, and the mod would fail to
 * link against the very shim written for it.
 *
 * <h2>The one signature that is not a pass-through</h2>
 *
 * NeoForge's {@code post} returns the event; Forge's returns a boolean saying whether it was
 * cancelled. Mods use the returned event, so the shim posts and returns the argument.
 *
 * Generic {@code addListener} overloads erase to {@code Consumer}, so most of this surface is
 * identical once erased and only needs forwarding.
 */
public interface IEventBus {

    void register(Object target);

    void unregister(Object target);

    <T extends Event> void addListener(Consumer<T> consumer);

    <T extends Event> void addListener(Class<T> eventType, Consumer<T> consumer);

    <T extends Event> void addListener(EventPriority priority, Consumer<T> consumer);

    <T extends Event> void addListener(EventPriority priority, Class<T> eventType,
                                       Consumer<T> consumer);

    <T extends Event> void addListener(EventPriority priority, boolean receiveCancelled,
                                       Consumer<T> consumer);

    <T extends Event> void addListener(EventPriority priority, boolean receiveCancelled,
                                       Class<T> eventType, Consumer<T> consumer);

    <T extends Event> void addListener(boolean receiveCancelled, Consumer<T> consumer);

    <T extends Event> void addListener(boolean receiveCancelled, Class<T> eventType,
                                       Consumer<T> consumer);

    <T extends Event> T post(T event);

    <T extends Event> T post(EventPriority priority, T event);

    void start();
}
