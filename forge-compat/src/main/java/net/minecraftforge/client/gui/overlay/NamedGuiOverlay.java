package net.minecraftforge.client.gui.overlay;

import net.minecraft.resources.ResourceLocation;

/**
 * Shim for {@code NamedGuiOverlay}, 15 corpus mods.
 *
 * A record pairing an overlay's id with its renderer. What mods actually do with one is compare
 * it: {@code event.getOverlay() == VanillaGuiOverlay.HOTBAR.type()} to decide whether to suppress
 * or augment a particular piece of the HUD. {@code id()} alone accounts for 12 of the 15 jars.
 *
 * So identity is the part that has to be right, and interning by id gives it -- two references to
 * the hotbar overlay are the same object, and {@code equals} agrees with {@code ==} either way.
 */
public record NamedGuiOverlay(ResourceLocation id, IGuiOverlay overlay) {

    private static final java.util.Map<ResourceLocation, NamedGuiOverlay> BY_ID =
            new java.util.concurrent.ConcurrentHashMap<>();

    /** Interning lookup, so overlays compared by identity keep comparing equal. */
    public static NamedGuiOverlay of(ResourceLocation id) {
        return BY_ID.computeIfAbsent(id, k -> new NamedGuiOverlay(k, null));
    }
}
