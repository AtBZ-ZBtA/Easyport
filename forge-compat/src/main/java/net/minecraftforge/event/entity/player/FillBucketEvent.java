package net.minecraftforge.event.entity.player;

import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.HitResult;
import net.neoforged.bus.api.ICancellableEvent;

/**
 * Link-only shim for {@code FillBucketEvent}, which NeoForge removed outright.
 *
 * <h2>Why a shim and not a rename</h2>
 *
 * There is nothing to rename it to. NeoForge replaced bucket interaction with fluid
 * capabilities, so no equivalent event exists and nothing posts one. A rename would need a
 * target; a bridge would need a source. Only the class itself can be supplied.
 *
 * That makes this the first instance of a category worth naming: **an event the target platform
 * removed gets a link-only shim.** The mod links, the listener registers, and the event never
 * fires — which is accurate rather than merely convenient, because on NeoForge there is no such
 * event to fire. The alternative is the mod not loading at all, which is what was happening:
 * this single class blocks architectury, and architectury blocks twelve other mods.
 *
 * <h2>What is lost</h2>
 *
 * Real behaviour, not just a link. Architectury forwards this to its own {@code FILL_BUCKET}
 * event, so every architectury-based mod listening for bucket fills stops receiving them. That
 * is a genuine functional gap and it is not silent by accident — it is recorded here, in
 * STATE.md, and in the gap report, because a shim that quietly does nothing is exactly the
 * failure mode this project spends most of its effort avoiding.
 *
 * Closing it properly means detecting bucket interaction on the NeoForge side and posting this
 * event from a bridge, the same shape as {@code TickEventBridge}. Not done: the fluid capability
 * model it would have to hook is itself unported.
 *
 * <h2>Shape</h2>
 *
 * Extends NeoForge's {@code PlayerEvent} so it is a valid bus type, and implements
 * {@code ICancellableEvent} because Forge's {@code @Cancelable} annotation became an interface.
 * The accessors are the ones the corpus actually calls.
 */
public class FillBucketEvent extends net.neoforged.neoforge.event.entity.player.PlayerEvent
        implements ICancellableEvent {

    private final ItemStack emptyBucket;
    private final Level level;
    private final HitResult target;
    private ItemStack filledBucket;

    /**
     * Forge's result field.
     *
     * Typed as the shimmed {@code Event$Result} rather than a local enum, because the descriptor
     * has to match what callers already compiled against:
     * {@code setResult(Lnet/minecraftforge/eventbus/api/Event$Result;)V}. A local enum would
     * link cleanly here and then miss every real call site.
     */
    private net.minecraftforge.eventbus.api.Event$Result result =
            net.minecraftforge.eventbus.api.Event$Result.DEFAULT;

    public FillBucketEvent(Player player, ItemStack emptyBucket, Level level, HitResult target) {
        super(player);
        this.emptyBucket = emptyBucket;
        this.level = level;
        this.target = target;
    }

    public ItemStack getEmptyBucket() {
        return emptyBucket;
    }

    public Level getLevel() {
        return level;
    }

    public HitResult getTarget() {
        return target;
    }

    public ItemStack getFilledBucket() {
        return filledBucket;
    }

    public void setFilledBucket(ItemStack filledBucket) {
        this.filledBucket = filledBucket;
    }

    public net.minecraftforge.eventbus.api.Event$Result getResult() {
        return result;
    }

    public void setResult(net.minecraftforge.eventbus.api.Event$Result result) {
        this.result = result;
    }
}
