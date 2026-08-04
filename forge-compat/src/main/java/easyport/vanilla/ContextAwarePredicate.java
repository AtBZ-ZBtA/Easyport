package easyport.vanilla;

/**
 * Relocated stand-in for {@code net.minecraft.advancements.critereon.ContextAwarePredicate}.
 *
 * Exists only because {@link AbstractCriterionTriggerInstance}'s constructor takes one, and a
 * constructor's descriptor has to match the call site exactly. Typing that parameter as
 * {@code Object} instead would have been simpler and would not have linked: the corpus's
 * {@code super(...)} calls name this type, and the JVM resolves constructors by descriptor.
 *
 * Opaque on purpose. 1.21 replaced the whole predicate model, so there is nothing behind this to
 * be faithful to -- it is a placeholder that lets the surrounding class load.
 */
public final class ContextAwarePredicate {

    public static final ContextAwarePredicate ANY = new ContextAwarePredicate();

    public ContextAwarePredicate() {}
}
