package easyport.bridge;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import net.minecraft.core.Holder;
import net.minecraft.core.Registry;
import net.minecraft.core.registries.BuiltInRegistries;

/**
 * Converts between a 1.20.1 registry value and the {@code Holder} 1.21 wraps it in.
 *
 * <h2>What changed</h2>
 *
 * 1.21 wrapped a large family of vanilla registry constants: {@code MobEffects.POISON} went from
 * a {@code MobEffect} to a {@code Holder<MobEffect>}, and every vanilla method that consumed one
 * changed to match. Measured on the corpus, the family is worth about 1,800 jar-references --
 * {@code MobEffect} (757), {@code Attribute} (430), {@code SoundEvent} (129),
 * {@code Potion} (106), plus {@code ArmorMaterial} and {@code GameEvent}.
 *
 * <h2>Why translation adapts rather than propagates</h2>
 *
 * The obvious fix is to let the new type spread: retype the field, and let the {@code Holder}
 * flow onward. It does not work, because it does not stop at the vanilla boundary. A mod that
 * reads {@code MobEffects.POISON} into its own field, passes it to its own method, or calls
 * {@code getDisplayName()} on it now has a {@code Holder} where its own bytecode says
 * {@code MobEffect}, and the class fails verification. Chasing that through mod signatures is a
 * whole-program data-flow problem.
 *
 * So Easyport does the opposite. Every vanilla value crossing into mod code is unwrapped back to
 * what 1.20.1 called it, and wrapped again on the way back in. The mod's own view of the world is
 * left exactly as its author compiled it, and every adaptation is local to one instruction.
 *
 * <h2>Wrapping without being told the registry</h2>
 *
 * {@code Registry.wrapAsHolder} needs the registry the value lives in, which the call site does
 * not know -- the bytecode only has a value. Rather than maintaining a type-to-registry table
 * that would need an entry for every wrapped type ever added, this searches the registries once
 * per value class and remembers the answer.
 *
 * The search is not free, but it happens once per distinct class, and the result is cached
 * against the class rather than the value.
 */
public final class HolderBridge {

    private HolderBridge() {}

    /**
     * The registry a given value class belongs to, or {@code null} once a search has failed.
     *
     * Keyed on {@code Class} rather than on the value: two {@code MobEffect}s live in the same
     * registry, and caching per value would grow without bound for things like {@code ItemStack}.
     * A null value is stored as a sentinel so a failed search is not repeated.
     */
    private static final Map<Class<?>, Registry<?>> REGISTRY_OF = new ConcurrentHashMap<>();
    private static final Registry<?> NONE = BuiltInRegistries.REGISTRY;

    /**
     * The holder for a registry value.
     *
     * Single erased signature on purpose. One method with descriptor
     * {@code (Ljava/lang/Object;)Lnet/minecraft/core/Holder;} covers every wrapped type, so the
     * transformer needs no per-type rule and no list to keep current -- it discovers which
     * arguments need wrapping by comparing the call site against the platform's own signature.
     */
    @SuppressWarnings("unchecked")
    public static <T> Holder<T> wrap(T value) {
        if (value == null) return null;
        if (value instanceof Holder<?> already) return (Holder<T>) already;

        Registry<?> cached = REGISTRY_OF.get(value.getClass());
        if (cached == null) {
            cached = search(value);
            REGISTRY_OF.put(value.getClass(), cached);
        }
        if (cached != NONE) {
            Registry<T> reg = (Registry<T>) cached;
            if (reg.getKey(value) != null) return reg.wrapAsHolder(value);
        }

        // Not in any built-in registry. Two real cases end up here, and a direct holder is right
        // for both: a value from a *dynamic* registry (1.21 made enchantments and potions
        // data-driven, and those are not reachable without a RegistryAccess), and a value a mod
        // constructed itself and never registered. A direct holder is accepted everywhere a
        // holder is taken; what it cannot do is serialise by id, which is a narrower failure than
        // refusing to translate.
        return Holder.direct(value);
    }

    /** The value inside a holder, for the return side of the same boundary. */
    @SuppressWarnings("unchecked")
    public static <T> T unwrap(Object holder) {
        if (holder == null) return null;
        return holder instanceof Holder<?> h ? (T) h.value() : (T) holder;
    }

    private static Registry<?> search(Object value) {
        for (Registry<?> candidate : BuiltInRegistries.REGISTRY) {
            try {
                @SuppressWarnings("unchecked")
                Registry<Object> reg = (Registry<Object>) candidate;
                if (reg.getKey(value) != null) return candidate;
            } catch (ClassCastException | IllegalArgumentException ignored) {
                // A registry whose value type this is not. Cheaper to let the lookup fail than
                // to reflect over the registry's type parameter, which is erased anyway.
            }
        }
        return NONE;
    }
}
