package net.minecraftforge.common.capabilities;

/**
 * Shim for {@code ForgeCapabilities}, the top unresolved type in the corpus at 164 jars.
 *
 * The constants Forge shipped, as {@link Capability} tokens. Each names the NeoForge capability
 * it corresponds to; {@code easyport.bridge.CapabilityBridge} holds the actual mapping, because
 * NeoForge splits every capability into block, item and entity variants with different lookup
 * signatures and this class has to present one token per capability the way Forge did.
 *
 * Counts are corpus jars reading the field: ITEM_HANDLER 137, FLUID_HANDLER 83, ENERGY 78,
 * FLUID_HANDLER_ITEM 49.
 */
public class ForgeCapabilities {

    public static final Capability<Object> ITEM_HANDLER = new Capability<>("item_handler");
    public static final Capability<Object> FLUID_HANDLER = new Capability<>("fluid_handler");
    public static final Capability<Object> FLUID_HANDLER_ITEM = new Capability<>("fluid_handler_item");
    public static final Capability<Object> ENERGY = new Capability<>("energy");

    private ForgeCapabilities() {}
}
