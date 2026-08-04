package net.minecraftforge.common.capabilities;

import net.minecraft.core.Direction;
import net.minecraftforge.common.util.LazyOptional;

/**
 * Shim for {@code ICapabilityProvider}, implemented by 106 corpus jars.
 *
 * <h2>Why this is a shim and not a rename</h2>
 *
 * NeoForge has an interface of the same name at
 * {@code net.neoforged.neoforge.capabilities.ICapabilityProvider}, and a prefix rule mapping the
 * package onto it looked correct for about an hour. The shapes do not match:
 *
 * <pre>
 *   Forge     ICapabilityProvider          { LazyOptional&lt;T&gt; getCapability(Capability&lt;T&gt;, Direction) }
 *   NeoForge  ICapabilityProvider&lt;O, C, T&gt; { T getCapability(O, C) }
 * </pre>
 *
 * Renaming makes every implementor declare a method that overrides nothing and leaves NeoForge's
 * abstract method unimplemented. The class still loads; it throws AbstractMethodError the first
 * time anything asks it for a capability, which may be a long way into a game. The rule was
 * withdrawn and the type shimmed instead.
 *
 * <h2>Getting the calls to arrive</h2>
 *
 * Implementing this interface is not enough on its own -- nothing in NeoForge knows to call it.
 * {@code easyport.bridge.CapabilityBridge} registers a NeoForge capability provider for every
 * block entity and item type that turns out to implement this, and forwards.
 */
public interface ICapabilityProvider {

    <T> LazyOptional<T> getCapability(Capability<T> cap, Direction side);

    /**
     * Forge's side-less overload, defaulting to null.
     *
     * Declared as a default so the many implementors that only override the two-argument form
     * still satisfy both -- which is how Forge declared it too.
     */
    default <T> LazyOptional<T> getCapability(Capability<T> cap) {
        return getCapability(cap, null);
    }
}
