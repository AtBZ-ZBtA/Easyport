package easyport.vanilla;

import com.google.gson.JsonObject;

import net.minecraft.resources.ResourceLocation;

/**
 * Relocated stand-in for {@code net.minecraft.advancements.critereon.AbstractCriterionTriggerInstance},
 * 34 corpus mods.
 *
 * 1.20.5 rewrote advancement criteria onto codecs: trigger instances became records with a
 * {@code Codec}, the JSON-serialising base class was deleted, and {@code ContextAwarePredicate}
 * went with it.
 *
 * <h2>What this restores, and what it does not</h2>
 *
 * Loading. A mod's custom trigger extends this, and without it the whole class fails to link,
 * taking with it whatever else that class registers -- which is how one deleted advancement type
 * silently removes a mod's blocks.
 *
 * It does not restore the triggers. Vanilla dispatches criteria through codecs now and will never
 * construct one of these, so a mod's custom advancement conditions will not fire. That is a
 * genuine partial translation and the per-jar report should say so rather than letting a clean
 * load imply a clean port.
 *
 * Doing better means generating a codec from the mod's {@code serializeToJson} pair, which is the
 * same unsolved problem as {@link LootSerializer} and belongs with it.
 */
public abstract class AbstractCriterionTriggerInstance {

    private final ResourceLocation criterion;
    private final ContextAwarePredicate player;

    /**
     * Second parameter is the relocated {@link ContextAwarePredicate}, which 1.21 deleted too.
     * It has to appear here by name rather than as Object, because the corpus's super(...) calls
     * carry that descriptor and the JVM resolves constructors by descriptor.
     */
    public AbstractCriterionTriggerInstance(ResourceLocation criterion, ContextAwarePredicate player) {
        this.criterion = criterion;
        this.player = player;
    }

    public ResourceLocation getCriterion() {
        return criterion;
    }

    public ContextAwarePredicate getPlayerPredicate() {
        return player;
    }

    public JsonObject serializeToJson(Object context) {
        return new JsonObject();
    }

    @Override
    public String toString() {
        return "AbstractCriterionTriggerInstance{" + criterion + "}";
    }
}
