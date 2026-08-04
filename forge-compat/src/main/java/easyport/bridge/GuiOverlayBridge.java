package easyport.bridge;

import net.minecraftforge.client.event.RenderGuiOverlayEvent;
import net.minecraftforge.client.gui.overlay.NamedGuiOverlay;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.RenderGuiLayerEvent;

/**
 * Re-posts NeoForge's HUD layer events in Forge's shape.
 *
 * The event bus dispatches by the posted object's exact class, so a mod listening for
 * {@code RenderGuiOverlayEvent} is unreachable unless something posts that exact type. This is
 * the same bridging pattern the tick and network events use.
 *
 * Cancellation is forwarded in both directions rather than only outward. A mod cancelling the Pre
 * event means "do not draw this element", and NeoForge only honours that if the cancellation
 * lands back on its own event -- without the return trip the mod's listener runs, appears to
 * work, and the element is drawn anyway.
 */
@EventBusSubscriber(modid = "forge_compat", value = Dist.CLIENT)
public final class GuiOverlayBridge {

    private GuiOverlayBridge() {}

    @SubscribeEvent
    public static void pre(RenderGuiLayerEvent.Pre event) {
        RenderGuiOverlayEvent.Pre shim = new RenderGuiOverlayEvent.Pre(
                event.getGuiGraphics(), event.getPartialTick(),
                NamedGuiOverlay.of(event.getName()));
        net.neoforged.neoforge.common.NeoForge.EVENT_BUS.post(shim);
        if (shim.isCanceled()) event.setCanceled(true);
    }

    @SubscribeEvent
    public static void post(RenderGuiLayerEvent.Post event) {
        net.neoforged.neoforge.common.NeoForge.EVENT_BUS.post(new RenderGuiOverlayEvent.Post(
                event.getGuiGraphics(), event.getPartialTick(),
                NamedGuiOverlay.of(event.getName())));
    }
}
