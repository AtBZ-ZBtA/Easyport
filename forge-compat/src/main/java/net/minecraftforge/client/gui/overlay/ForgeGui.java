package net.minecraftforge.client.gui.overlay;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Gui;

/**
 * Shim for {@code ForgeGui}, Forge's subclass of vanilla's {@code Gui}.
 *
 * NeoForge kept no equivalent -- the HUD is assembled from layers now -- so this exists to give
 * {@link IGuiOverlay} a parameter type and to carry the two fields mods read off it.
 */
public class ForgeGui extends Gui {

    public int leftHeight = 39;
    public int rightHeight = 39;

    public ForgeGui(Minecraft minecraft) {
        super(minecraft);
    }
}
