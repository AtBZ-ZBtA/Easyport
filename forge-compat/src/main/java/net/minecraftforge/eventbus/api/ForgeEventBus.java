package net.minecraftforge.eventbus.api;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.HashSet;
import java.util.Set;
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
        if (supertypeHasHandlers(target)) {
            registerAcrossHierarchy(target);
            return;
        }
        delegate.register(target);
    }

    /**
     * True if a class *above* the target declares handlers, which NeoForge rejects outright.
     *
     * Forge collected {@code @SubscribeEvent} methods from the whole hierarchy, so putting shared
     * handlers on an abstract base and registering the subclass was an ordinary pattern -- it is
     * how cyclopscore's {@code ModBase} works, and how every mod built on it works by extension.
     * NeoForge's bus throws IllegalArgumentException on sight of it, during mod construction,
     * which takes the mod down.
     */
    private static boolean supertypeHasHandlers(Object target) {
        Class<?> type = (target instanceof Class<?> c) ? c : target.getClass();
        for (Class<?> k = type.getSuperclass(); k != null && k != Object.class; k = k.getSuperclass()) {
            if (declaresHandler(k)) return true;
        }
        return false;
    }

    /**
     * Registers each handler method individually, reproducing Forge's inherited-handler support.
     *
     * NeoForge's own {@code register} cannot be used here at all -- it is the thing that throws --
     * so the hierarchy is walked and every handler bound as a separate listener. That is what
     * Forge did internally, so the resulting dispatch is the same set of calls.
     *
     * Taken only when the fast path is unavailable. NeoForge generates listeners rather than
     * reflecting, and reflective invocation on every event is a real cost; paying it for the
     * mods that need it beats paying it for all of them, and beats those mods not loading.
     */
    private void registerAcrossHierarchy(Object target) {
        boolean staticOnly = target instanceof Class<?>;
        Class<?> type = staticOnly ? (Class<?>) target : target.getClass();

        // Subclass first, so an override registers before the method it overrides -- and track
        // signatures already bound, or an overridden handler fires twice.
        Set<String> bound = new HashSet<>();
        for (Class<?> k = type; k != null && k != Object.class; k = k.getSuperclass()) {
            for (Method method : k.getDeclaredMethods()) {
                var annotation = method.getAnnotation(net.neoforged.bus.api.SubscribeEvent.class);
                if (annotation == null) continue;
                if (method.getParameterCount() != 1) continue;
                boolean isStatic = Modifier.isStatic(method.getModifiers());
                if (staticOnly && !isStatic) continue;

                Class<?> eventType = method.getParameterTypes()[0];
                if (!Event.class.isAssignableFrom(eventType)) continue;
                if (!bound.add(method.getName() + eventType.getName())) continue;

                method.setAccessible(true);
                bind(method, isStatic ? null : target, eventType, annotation.priority(),
                     annotation.receiveCanceled());
            }
        }
    }

    /** Separate method so the generic capture on the event type is expressible. */
    @SuppressWarnings("unchecked")
    private <T extends Event> void bind(Method method, Object instance, Class<?> eventType,
                                        EventPriority priority, boolean receiveCancelled) {
        delegate.addListener(priority, receiveCancelled, (Class<T>) eventType, event -> {
            try {
                method.invoke(instance, event);
            } catch (IllegalAccessException e) {
                throw new IllegalStateException("could not invoke handler " + method, e);
            } catch (InvocationTargetException e) {
                // Unwrap, so a mod's own exception surfaces as itself rather than buried in a
                // reflection wrapper the mod author has no way to recognise.
                Throwable cause = e.getCause();
                if (cause instanceof RuntimeException re) throw re;
                if (cause instanceof Error err) throw err;
                throw new RuntimeException(cause);
            }
        });
    }

    /** True if this exact class declares at least one handler, ignoring its supertypes. */
    private static boolean declaresHandler(Class<?> type) {
        for (Method method : type.getDeclaredMethods()) {
            if (method.isAnnotationPresent(net.neoforged.bus.api.SubscribeEvent.class)) return true;
        }
        return false;
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

    // ------------------------------------------------------------------ generic listeners

    @Override public <T extends GenericEvent<? extends F>, F> void addGenericListener(
            Class<F> filter, Consumer<T> consumer) {
        addGeneric(filter, EventPriority.NORMAL, false, null, consumer);
    }

    @Override public <T extends GenericEvent<? extends F>, F> void addGenericListener(
            Class<F> filter, EventPriority priority, Consumer<T> consumer) {
        addGeneric(filter, priority, false, null, consumer);
    }

    @Override public <T extends GenericEvent<? extends F>, F> void addGenericListener(
            Class<F> filter, EventPriority priority, boolean receiveCancelled,
            Consumer<T> consumer) {
        addGeneric(filter, priority, receiveCancelled, null, consumer);
    }

    @Override public <T extends GenericEvent<? extends F>, F> void addGenericListener(
            Class<F> filter, EventPriority priority, boolean receiveCancelled,
            Class<T> eventType, Consumer<T> consumer) {
        addGeneric(filter, priority, receiveCancelled, eventType, consumer);
    }

    /**
     * Registers a generic listener, filtering dispatch on the event's type token.
     *
     * NeoForge's bus has no notion of generic filtering, so the filter is applied here: the
     * listener is registered for the event class and drops anything whose token does not match.
     * That reproduces Forge's dispatch exactly for any event that actually gets posted.
     *
     * <h2>The inferred-type overloads</h2>
     *
     * Three of the four Forge overloads never took the event class -- Forge recovered it from
     * the lambda's generic signature. NeoForge's bus does the same thing internally via a
     * bundled {@code TypeResolver}, so this borrows it reflectively rather than reimplementing
     * a constant-pool hack. Reflectively because it is an implementation detail of another
     * module: if it moves or is not visible, that must degrade rather than crash.
     *
     * When inference fails there is no event class to register against, and the listener is
     * dropped with a warning. Dropping is the least-bad option -- registering it against
     * {@code GenericEvent} itself would deliver every mod's generic events to every other mod's
     * listener, and the receiving lambda would fail its own cast.
     *
     * <h2>What this cannot fix</h2>
     *
     * Most of the 35 jars listen for Forge events with no NeoForge counterpart --
     * {@code AttachCapabilitiesEvent} above all. Nothing posts those, so those listeners will
     * not fire no matter how faithfully they are registered. That is the capability gap
     * recorded on {@code CapabilityToken}, not a defect in this method.
     */
    @SuppressWarnings("unchecked")
    private <T extends GenericEvent<? extends F>, F> void addGeneric(
            Class<F> filter, EventPriority priority, boolean receiveCancelled,
            Class<T> eventType, Consumer<T> consumer) {

        Class<T> type = eventType != null ? eventType : (Class<T>) inferEventClass(consumer);
        if (type == null) {
            System.err.println("[forge-compat] dropped a generic listener whose event type could "
                    + "not be inferred: " + consumer.getClass().getName()
                    + " (filter " + filter.getName() + ")");
            return;
        }

        Consumer<T> filtered = event -> {
            if (event.getGenericType() == filter) consumer.accept(event);
        };
        delegate.addListener(priority, receiveCancelled, type, filtered);
    }

    /** Null when the type cannot be recovered; see {@link #addGeneric}. */
    private static Class<?> inferEventClass(Consumer<?> consumer) {
        try {
            Class<?> resolver = Class.forName("net.jodah.typetools.TypeResolver");
            Class<?> unknown = Class.forName("net.jodah.typetools.TypeResolver$Unknown");
            Object resolved = resolver.getMethod("resolveRawArgument", Class.class, Class.class)
                    .invoke(null, Consumer.class, consumer.getClass());
            if (resolved == null || resolved == unknown) return null;
            Class<?> c = (Class<?>) resolved;
            return GenericEvent.class.isAssignableFrom(c) ? c : null;
        } catch (ReflectiveOperationException | RuntimeException e) {
            return null;
        }
    }
}
