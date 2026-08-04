package net.minecraftforge.client.gui.overlay;

import net.minecraft.client.gui.GuiGraphics;

/**
 * Shim for {@code IGuiOverlay}, the render callback behind Forge's named HUD overlays.
 *
 * NeoForge replaced the whole overlay registry with {@code LayeredDraw} layers keyed by
 * {@code ResourceLocation}, so there is nothing to rename to. Mods overwhelmingly use these types
 * to *identify* an overlay -- "am I being asked to draw the hotbar" -- rather than to register
 * one, which is why the identity half is reproduced faithfully and the registration half is not.
 */
@FunctionalInterface
public interface IGuiOverlay {

    void render(net.minecraftforge.client.gui.overlay.ForgeGui gui, GuiGraphics guiGraphics,
                float partialTick, int screenWidth, int screenHeight);
}
