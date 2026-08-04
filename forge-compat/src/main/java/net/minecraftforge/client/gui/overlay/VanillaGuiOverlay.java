package net.minecraftforge.client.gui.overlay;

import net.minecraft.resources.ResourceLocation;

/**
 * Shim for {@code VanillaGuiOverlay}, the enum naming each piece of the vanilla HUD.
 *
 * Ids match NeoForge's {@code VanillaGuiLayers} constants, so an overlay identified here is the
 * same location NeoForge's layer system uses. That makes the identity comparisons mods perform
 * against these constants agree with what NeoForge is actually drawing, even though the
 * registration mechanism behind them is entirely different.
 */
public enum VanillaGuiOverlay {

    HELMET("helmet"),
    PORTAL("portal"),
    HOTBAR("hotbar"),
    CROSSHAIR("crosshair"),
    BOSS_EVENT_PROGRESS("boss_bar"),
    PLAYER_HEALTH("player_health"),
    ARMOR_LEVEL("armor_level"),
    FOOD_LEVEL("food_level"),
    AIR_LEVEL("air_level"),
    JUMP_BAR("jump_meter"),
    EXPERIENCE_BAR("experience_bar"),
    ITEM_NAME("held_item_tooltip"),
    SLEEP_FADE("sleep_overlay"),
    DEBUG_TEXT("debug_overlay"),
    POTION_ICONS("effects"),
    CHAT_PANEL("chat"),
    PLAYER_LIST("player_list"),
    SUBTITLES("subtitle_overlay");

    private final NamedGuiOverlay type;

    VanillaGuiOverlay(String path) {
        this.type = NamedGuiOverlay.of(ResourceLocation.withDefaultNamespace(path));
    }

    public NamedGuiOverlay type() {
        return type;
    }

    public ResourceLocation id() {
        return type.id();
    }
}
