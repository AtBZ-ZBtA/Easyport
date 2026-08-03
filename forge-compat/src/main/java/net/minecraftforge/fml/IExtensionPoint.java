package net.minecraftforge.fml;

import java.util.function.BiPredicate;
import java.util.function.Supplier;

/**
 * Shim for {@code IExtensionPoint} and its {@code DisplayTest} member.
 *
 * NeoForge keeps {@code IExtensionPoint} as an empty marker but dropped {@code DisplayTest}
 * entirely: the server-list compatibility check it expressed is now declared in
 * {@code neoforge.mods.toml} rather than registered at runtime.
 *
 * So this is a shim with no delegate — it exists purely so the type resolves and the mod's
 * registration call links. The registration itself is a no-op (see
 * {@link ModLoadingContext#registerExtensionPoint}).
 *
 * That is a real, if minor, behaviour change and worth being honest about: a mod that declared
 * itself client-only for server-list purposes silently loses that declaration. The correct fix
 * is for the transformer to emit the equivalent {@code displayTest} field into the migrated
 * descriptor. Until then the NeoForge default applies, which is the conservative choice — it
 * assumes the mod matters for compatibility rather than assuming it does not.
 */
public interface IExtensionPoint {

    /**
     * Kept as a record with the Forge shape so construction sites link unchanged. The supplier
     * and predicate are accepted and never invoked.
     */
    record DisplayTest(Supplier<String> suppliedVersion,
                       BiPredicate<String, Boolean> remoteVersionTest) implements IExtensionPoint {
    }
}
