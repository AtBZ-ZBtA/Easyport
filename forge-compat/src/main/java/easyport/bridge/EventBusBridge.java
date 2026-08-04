package easyport.bridge;

import net.neoforged.bus.api.Event;
import net.neoforged.bus.api.ICancellableEvent;
import net.neoforged.bus.api.IEventBus;

/**
 * Static replacement for {@code IEventBus.post}, called by 110 corpus jars.
 *
 * <h2>Why this cannot be a method on the shim</h2>
 *
 * Forge's {@code post} returns {@code boolean} -- true if the event was cancelled. NeoForge's
 * returns the event itself. Same name, same parameter, different return type, which the JVM
 * allows and Java does not: the shimmed {@code IEventBus} extends NeoForge's interface, so
 * declaring {@code boolean post(Event)} alongside the inherited {@code <T extends Event> T post(T)}
 * is a clashing-erasure compile error.
 *
 * So the call site moves instead. {@code METHOD_TO_STATIC} rewrites it to this, passing the bus
 * as the first argument -- which is free, because INVOKEINTERFACE already leaves the receiver
 * exactly where INVOKESTATIC reads its first parameter.
 *
 * This is the second use of that rule kind and the case that justifies it best: no shim and no
 * rename can express a return-type change on an inherited method.
 */
public final class EventBusBridge {

    /**
     * Posts and reports cancellation, reproducing Forge's contract.
     *
     * Cancellation moved from a method on every event to the {@code ICancellableEvent} interface,
     * so an event that cannot be cancelled answers false rather than throwing -- which is what
     * Forge did for an event without {@code @Cancelable}.
     */
    public static boolean post(IEventBus bus, Event event) {
        bus.post(event);
        return event instanceof ICancellableEvent cancellable && cancellable.isCanceled();
    }

    private EventBusBridge() {}
}
