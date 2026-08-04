package net.minecraftforge.common.capabilities;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Shim for {@code CapabilityManager}, whose {@code get(CapabilityToken)} appears in 92 jars.
 *
 * Forge recovered a capability from the erased generic parameter of an anonymous
 * {@link CapabilityToken} subclass -- {@code CapabilityManager.get(new CapabilityToken<IItemHandler>(){})}.
 * The type argument survives in the subclass's signature attribute, which is what made the trick
 * work.
 *
 * The same trick works here, and it is the only way to identify which capability was meant: the
 * token carries no other information. Tokens for the same type return the same
 * {@link Capability} instance, because mods compare capability tokens by identity.
 */
public class CapabilityManager {

    public static final CapabilityManager INSTANCE = new CapabilityManager();

    private static final Map<String, Capability<?>> BY_TYPE = new ConcurrentHashMap<>();

    /**
     * The capability for a token's generic parameter.
     *
     * Falls back to the token's own class name when the type argument cannot be recovered --
     * a raw or non-anonymous token. That yields a distinct capability rather than a wrong one,
     * so a mod using it gets nothing rather than another mod's handler.
     */
    @SuppressWarnings("unchecked")
    public static <T> Capability<T> get(CapabilityToken<T> token) {
        return (Capability<T>) BY_TYPE.computeIfAbsent(typeNameOf(token), Capability::new);
    }

    private static String typeNameOf(CapabilityToken<?> token) {
        java.lang.reflect.Type superclass = token.getClass().getGenericSuperclass();
        if (superclass instanceof java.lang.reflect.ParameterizedType p) {
            java.lang.reflect.Type[] args = p.getActualTypeArguments();
            if (args.length == 1) return args[0].getTypeName();
        }
        return token.getClass().getName();
    }
}
