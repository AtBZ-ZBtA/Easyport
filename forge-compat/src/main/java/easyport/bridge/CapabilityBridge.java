package easyport.bridge;

import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.item.Item;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.minecraftforge.common.capabilities.Capability;
import net.minecraftforge.common.capabilities.ForgeCapabilities;
import net.minecraftforge.common.capabilities.ICapabilityProvider;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.capabilities.BlockCapability;
import net.neoforged.neoforge.capabilities.Capabilities;
import net.neoforged.neoforge.capabilities.ItemCapability;
import net.neoforged.neoforge.capabilities.RegisterCapabilitiesEvent;

/**
 * Makes NeoForge ask Forge-style capability providers for their capabilities.
 *
 * <h2>The two models</h2>
 *
 * Forge asked the object: a block entity implemented {@code ICapabilityProvider} and answered
 * {@code getCapability(cap, side)} itself. NeoForge asks a registry: a provider is registered
 * against a specific capability and a specific block entity or item type, and the lookup goes
 * through the level or the stack. Nothing calls a block entity's own method, so a translated mod
 * implements {@code ICapabilityProvider} into the void.
 *
 * This closes that. For every registered block entity type and item, it registers a NeoForge
 * provider that checks whether the object is a Forge-style provider and forwards to it.
 *
 * <h2>Registering against everything</h2>
 *
 * Deliberate, and the only option available. Forge's model has no registration step, so there is
 * no list of which types support which capability -- the information simply does not exist in a
 * translated mod. Iterating the registries and offering every type to every capability
 * reconstructs it: the {@code instanceof} check costs nothing for types that are not providers,
 * and a Forge provider returning an empty LazyOptional is exactly the "no such capability" answer
 * NeoForge expects from a null return.
 *
 * The cost is registration-time work proportional to (block entity types + items) x capabilities,
 * paid once at startup.
 *
 * <h2>Not covered</h2>
 *
 * Entities, and anything attached through {@code AttachCapabilitiesEvent} rather than implemented
 * directly. The event has no NeoForge counterpart -- capabilities are no longer attached to
 * objects at all -- so a mod that adds a capability to something it does not own still gets
 * nothing. Persistence is likewise unbridged; see {@code ICapabilitySerializable}.
 */
@EventBusSubscriber(modid = "forge_compat")
public final class CapabilityBridge {

    @SubscribeEvent
    public static void onRegisterCapabilities(RegisterCapabilitiesEvent event) {
        registerBlocks(event, Capabilities.ItemHandler.BLOCK, ForgeCapabilities.ITEM_HANDLER);
        registerBlocks(event, Capabilities.FluidHandler.BLOCK, ForgeCapabilities.FLUID_HANDLER);
        registerBlocks(event, Capabilities.EnergyStorage.BLOCK, ForgeCapabilities.ENERGY);

        registerItems(event, Capabilities.ItemHandler.ITEM, ForgeCapabilities.ITEM_HANDLER);
        registerItems(event, Capabilities.FluidHandler.ITEM, ForgeCapabilities.FLUID_HANDLER_ITEM);
        registerItems(event, Capabilities.EnergyStorage.ITEM, ForgeCapabilities.ENERGY);
    }

    /**
     * Block entities, which take a side.
     *
     * The side is passed straight through: it means the same thing on both platforms, and a
     * Forge provider that ignores it behaves identically either way.
     */
    private static <T> void registerBlocks(RegisterCapabilitiesEvent event,
                                           BlockCapability<T, net.minecraft.core.Direction> capability,
                                           Capability<?> forgeCapability) {
        for (BlockEntityType<?> type : BuiltInRegistries.BLOCK_ENTITY_TYPE) {
            event.registerBlockEntity(capability, type, (blockEntity, side) ->
                    ask(blockEntity, forgeCapability, side));
        }
    }

    /**
     * Items, which do not.
     *
     * The capability is asked of the stack's item rather than the stack, because a Forge
     * ItemStack capability lived on a provider the item created per stack. Items that implement
     * the interface directly are the case this reaches; the per-stack provider factory is part
     * of the unbridged AttachCapabilities path.
     */
    private static <T> void registerItems(RegisterCapabilitiesEvent event,
                                          ItemCapability<T, Void> capability,
                                          Capability<?> forgeCapability) {
        for (Item item : BuiltInRegistries.ITEM) {
            event.registerItem(capability, (stack, context) ->
                    ask(item, forgeCapability, null), item);
        }
    }

    /**
     * Forwards to a Forge provider, or returns null when the object is not one.
     *
     * Null is NeoForge's "no capability here", and an empty LazyOptional is Forge's, so the
     * translation between them happens on this line.
     */
    @SuppressWarnings("unchecked")
    private static <T> T ask(Object target, Capability<?> capability, net.minecraft.core.Direction side) {
        if (!(target instanceof ICapabilityProvider provider)) return null;
        return (T) provider.getCapability((Capability<Object>) capability, side).orElse(null);
    }

    private CapabilityBridge() {}
}
