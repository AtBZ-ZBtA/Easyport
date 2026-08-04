package net.minecraftforge.client.event;

import net.minecraft.client.DeltaTracker;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraftforge.client.gui.overlay.NamedGuiOverlay;
import net.neoforged.bus.api.ICancellableEvent;

/**
 * Shim for {@code RenderGuiOverlayEvent}, fired once per HUD element. 19 and 18 corpus jars for
 * its Pre and Post halves.
 *
 * NeoForge's equivalent is {@code RenderGuiLayerEvent}, which carries a {@code ResourceLocation}
 * where Forge carried a {@link NamedGuiOverlay}. That difference rules out a rename -- the
 * accessor mods call would return the wrong type -- so this is the shim-and-bridge shape the
 * event work settled on during Phase 3, with {@code GuiOverlayBridge} subscribing to NeoForge's
 * event and re-posting this one.
 *
 * Cancelling matters here and is wired through: suppressing a vanilla HUD element is most of what
 * mods use the Pre event for, and a shim that silently dropped the cancellation would leave the
 * hotbar drawn twice rather than replaced.
 */
public abstract class RenderGuiOverlayEvent extends net.neoforged.bus.api.Event {

    private final GuiGraphics guiGraphics;
    private final DeltaTracker partialTick;
    private final NamedGuiOverlay overlay;

    protected RenderGuiOverlayEvent(GuiGraphics guiGraphics, DeltaTracker partialTick,
                                    NamedGuiOverlay overlay) {
        this.guiGraphics = guiGraphics;
        this.partialTick = partialTick;
        this.overlay = overlay;
    }

    public GuiGraphics getGuiGraphics() {
        return guiGraphics;
    }

    public NamedGuiOverlay getOverlay() {
        return overlay;
    }

    /**
     * Forge handed listeners a float. 1.21 replaced it with {@code DeltaTracker}, and mods read
     * this to scale animations, so returning the real value rather than a constant matters.
     */
    public float getPartialTick() {
        return partialTick == null ? 0.0F : partialTick.getGameTimeDeltaPartialTick(false);
    }

    public int getWindowWidth() {
        return net.minecraft.client.Minecraft.getInstance().getWindow().getGuiScaledWidth();
    }

    public int getWindowHeight() {
        return net.minecraft.client.Minecraft.getInstance().getWindow().getGuiScaledHeight();
    }

    public static class Pre extends RenderGuiOverlayEvent implements ICancellableEvent {
        public Pre(GuiGraphics guiGraphics, DeltaTracker partialTick, NamedGuiOverlay overlay) {
            super(guiGraphics, partialTick, overlay);
        }
    }

    public static class Post extends RenderGuiOverlayEvent {
        public Post(GuiGraphics guiGraphics, DeltaTracker partialTick, NamedGuiOverlay overlay) {
            super(guiGraphics, partialTick, overlay);
        }
    }
}
