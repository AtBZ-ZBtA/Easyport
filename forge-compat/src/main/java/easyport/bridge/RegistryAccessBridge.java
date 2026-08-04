package easyport.bridge;

import net.minecraft.core.HolderLookup;
import net.minecraft.core.RegistryAccess;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.server.ServerAboutToStartEvent;
import net.neoforged.neoforge.event.server.ServerStoppedEvent;

/**
 * Supplies the {@code HolderLookup.Provider} that 1.20.5 added to serialization signatures.
 *
 * A large family of vanilla methods grew a registry-access parameter when NBT gave way to data
 * components -- {@code ItemStack.save}, {@code BlockEntity.saveAdditional} and
 * {@code loadAdditional} among them, which between them are the single heaviest cluster in the
 * vanilla drift report. None of the 1.20.1 call sites can supply one, because in 1.20.1 the
 * registries were reachable statically and the parameter did not exist.
 *
 * <h2>Where the value comes from</h2>
 *
 * The running server's, captured when it starts and released when it stops. That is the same
 * object vanilla itself passes at these call sites on the server, so a translated mod serialising
 * a stack gets the registries it would have got natively.
 *
 * <h2>What happens when there is not one</h2>
 *
 * Client-side code with no integrated server running, and anything on a dedicated client
 * connected to a remote server, will find none. Returning null is deliberate: vanilla will throw
 * a NullPointerException naming the call, which is a loud, locatable failure. An empty
 * provider would be quiet and would silently drop every registry-backed component from whatever
 * was being written -- the failure mode this project most wants to avoid.
 */
@EventBusSubscriber(modid = "forge_compat")
public final class RegistryAccessBridge {

    private RegistryAccessBridge() {}

    private static volatile RegistryAccess current;

    public static HolderLookup.Provider provider() {
        return current;
    }

    @SubscribeEvent
    public static void capture(ServerAboutToStartEvent event) {
        current = event.getServer().registryAccess();
    }

    @SubscribeEvent
    public static void release(ServerStoppedEvent event) {
        current = null;
    }
}
