package net.minecraftforge.common.capabilities;

import net.neoforged.bus.api.Event;
import net.neoforged.fml.event.IModBusEvent;

/**
 * Link-only shim for {@code RegisterCapabilitiesEvent}, called by 68 corpus jars.
 *
 * <h2>Why doing nothing is the correct behaviour here</h2>
 *
 * Unusually for a shim in this project, the emptiness is not a gap.
 *
 * Forge required a mod to declare which classes could carry capabilities --
 * {@code event.register(IItemHandler.class)} -- so its capability system knew what to allocate
 * storage for. {@code easyport.bridge.CapabilityBridge} does not need that list: it registers a
 * forwarding provider against *every* block entity type and item, because Forge's model has no
 * per-type registration to translate and iterating the registries is the only way to
 * reconstruct one. Whatever a mod would have declared here is already covered.
 *
 * So the listener never firing costs nothing. That is a genuinely different situation from
 * {@code AttachCapabilitiesEvent}, which is also link-only and does lose real functionality --
 * worth keeping the two apart rather than filing both under "unimplemented".
 *
 * NeoForge has a class with this name, and it is not this one: its API is
 * {@code registerBlock}/{@code registerItem}/{@code registerEntity}, and {@code register(Class)}
 * does not exist on it. Renaming would resolve the type and then fail on the call, which is what
 * the member check in RenameGaps flagged before this shim was written.
 */
public class RegisterCapabilitiesEvent extends Event implements IModBusEvent {

    /** Accepted and discarded. See the class javadoc for why that loses nothing. */
    public void register(Class<?> capabilityType) {
        // intentionally empty
    }
}
