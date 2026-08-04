package net.minecraftforge.common.capabilities;

import net.minecraft.core.Direction;
import net.minecraftforge.common.util.LazyOptional;

/**
 * Shim for {@code CapabilityProvider}, the base class Forge's own capability-bearing objects
 * extended.
 *
 * Mods subclass it when they write something that both holds capabilities and is not already a
 * block entity or item stack. NeoForge has nothing equivalent -- capabilities are looked up from
 * a registry rather than asked of an object -- so this pairs with the {@link ICapabilityProvider}
 * shim and {@code CapabilityBridge}, which reconstructs Forge's ask-the-object model on top of
 * NeoForge's register-it-first one.
 *
 * The {@code Class} the constructor takes was Forge's own dispatch key and has no use here; it is
 * accepted and ignored so the subclass's {@code super(...)} call resolves.
 */
public class CapabilityProvider<B extends CapabilityProvider<B>> implements ICapabilityProvider {

    protected CapabilityProvider(Class<B> baseClass) {}

    protected CapabilityProvider(Class<B> baseClass, boolean isLazy) {}

    @Override
    public <T> LazyOptional<T> getCapability(Capability<T> capability, Direction side) {
        return LazyOptional.empty();
    }

    public <T> LazyOptional<T> getCapability(Capability<T> capability) {
        return getCapability(capability, null);
    }

    protected void invalidateCaps() {}

    protected void reviveCaps() {}
}
