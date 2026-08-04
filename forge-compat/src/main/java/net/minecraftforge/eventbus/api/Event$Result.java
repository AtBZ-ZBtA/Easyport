package net.minecraftforge.eventbus.api;

/**
 * Shim for {@code Event.Result}, referenced by 72 corpus jars.
 *
 * <h2>The name</h2>
 *
 * This is a top-level type whose name literally contains a dollar sign, which Java permits
 * because {@code $} is a legal identifier character. It compiles to
 * {@code net/minecraftforge/eventbus/api/Event$Result.class} — byte for byte the internal name
 * a mod's bytecode already refers to.
 *
 * The trick is necessary rather than clever. Forge declared {@code Result} nested inside
 * {@code Event}, and {@code Event} is one of the types that must be *renamed* to NeoForge's
 * rather than shimmed, because the bus dispatches on it. So there is no shimmed {@code Event}
 * to nest this inside, and the only way to supply the name is to spell it out. The JVM does not
 * require a nested class to be declared nested; the {@code InnerClasses} attribute is metadata,
 * and resolution goes by internal name alone.
 *
 * <h2>Behaviour</h2>
 *
 * Values only. Forge used this as a three-state override on events marked {@code @HasResult} —
 * a listener could force vanilla behaviour to happen or not happen. NeoForge removed the
 * mechanism and gave each event its own explicit outcome type instead, so there is no single
 * thing to delegate to and no general way to map a result back onto whichever NeoForge event
 * replaced the Forge one.
 *
 * In practice this lets {@code setResult} calls link. Whether the result is *honoured* depends
 * on the specific event: where forge-compat supplies a link-only event that nothing posts
 * ({@code FillBucketEvent}), nothing reads the result either and the question does not arise.
 * Where a real NeoForge event is involved, the result is dropped — which is a behavioural gap,
 * recorded rather than hidden.
 */
public enum Event$Result {
    DENY,
    DEFAULT,
    ALLOW
}
