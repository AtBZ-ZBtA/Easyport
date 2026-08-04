package easyport.bridge;

import net.minecraftforge.event.TickEvent;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.event.tick.LevelTickEvent;
import net.neoforged.neoforge.event.tick.PlayerTickEvent;
import net.neoforged.neoforge.event.tick.ServerTickEvent;

/**
 * Re-posts NeoForge tick events in the Forge shape, so translated listeners still fire.
 *
 * The event bus dispatches by the posted object's exact class, so a mod listening for
 * {@code TickEvent.ClientTickEvent} is unreachable unless something posts an object of that
 * class. Nothing does after translation — NeoForge posts its own {@code Pre}/{@code Post}
 * types. This subscribes to those and posts the Forge-shaped equivalent alongside.
 *
 * <h2>Cost</h2>
 *
 * Every tick event is now dispatched twice: once as NeoForge's own type, once as the Forge
 * shape. Tick events are the highest-frequency events in the game, so this is real overhead
 * rather than a rounding error, and it is paid by every mod in the instance rather than only
 * translated ones.
 *
 * The alternative was rewriting each listener's parameter type and deleting its phase check,
 * which is per-listener bytecode surgery the transformer cannot currently do. Bridging is the
 * cheaper correct option; narrowing it to only fire when a translated mod actually listens for
 * the Forge type would be the obvious optimisation.
 *
 * <h2>Not covered</h2>
 *
 * Client tick events are deliberately absent. They live in a client-only package, and
 * referencing them from a class loaded on both sides crashes a dedicated server — the exact
 * failure {@code DistExecutor} exists to prevent. Doing it properly needs a separate
 * client-only bridge registered behind a dist check, which is not written yet, so
 * {@code TickEvent.ClientTickEvent} still will not fire.
 */
@EventBusSubscriber(modid = "forge_compat", bus = EventBusSubscriber.Bus.GAME)
public final class TickEventBridge {

    @SubscribeEvent
    public static void onServerPre(ServerTickEvent.Pre event) {
        NeoForge.EVENT_BUS.post(new TickEvent.ServerTickEvent(TickEvent.Phase.START));
    }

    @SubscribeEvent
    public static void onServerPost(ServerTickEvent.Post event) {
        NeoForge.EVENT_BUS.post(new TickEvent.ServerTickEvent(TickEvent.Phase.END));
    }

    @SubscribeEvent
    public static void onLevelPre(LevelTickEvent.Pre event) {
        NeoForge.EVENT_BUS.post(new TickEvent.LevelTickEvent(TickEvent.Phase.START, event.getLevel()));
    }

    @SubscribeEvent
    public static void onLevelPost(LevelTickEvent.Post event) {
        NeoForge.EVENT_BUS.post(new TickEvent.LevelTickEvent(TickEvent.Phase.END, event.getLevel()));
    }

    @SubscribeEvent
    public static void onPlayerPre(PlayerTickEvent.Pre event) {
        NeoForge.EVENT_BUS.post(new TickEvent.PlayerTickEvent(TickEvent.Phase.START, event.getEntity()));
    }

    @SubscribeEvent
    public static void onPlayerPost(PlayerTickEvent.Post event) {
        NeoForge.EVENT_BUS.post(new TickEvent.PlayerTickEvent(TickEvent.Phase.END, event.getEntity()));
    }

    private TickEventBridge() {}
}
