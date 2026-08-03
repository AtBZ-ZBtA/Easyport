package net.minecraftforge.common;

import java.util.List;
import java.util.function.Supplier;

import net.neoforged.neoforge.common.ModConfigSpec;

/**
 * Shim shape 3 of 3: instance delegation with self-return.
 *
 * The important and awkward shape. Builder methods return the builder for chaining, so the
 * shim cannot hand back the delegate's return value — that would leak a NeoForge type into
 * code expecting a Forge one and break the very next call in the chain. Every chaining method
 * must discard the delegate's return and hand back {@code this}.
 *
 * This pattern recurs across most of forge-compat, which is why it is proven here first.
 *
 * {@code ForgeConfigSpec$Builder} is used by 137 of the 288 corpus mods, ninth on the shim
 * work list. Coverage below is deliberately limited to the methods the corpus shows mods
 * actually calling; the rest arrives in Phase 3.
 */
public class ForgeConfigSpec {

    private final ModConfigSpec delegate;

    private ForgeConfigSpec(ModConfigSpec delegate) {
        this.delegate = delegate;
    }

    /** Unwraps to the NeoForge spec, for shim code that has to hand the real object onward. */
    public ModConfigSpec unwrap() {
        return delegate;
    }

    // ---- Builder -----------------------------------------------------------------------

    public static class Builder {

        private final ModConfigSpec.Builder delegate = new ModConfigSpec.Builder();

        public Builder comment(String comment) {
            delegate.comment(comment);
            return this;
        }

        public Builder comment(String... comment) {
            delegate.comment(comment);
            return this;
        }

        public Builder translation(String translationKey) {
            delegate.translation(translationKey);
            return this;
        }

        public Builder push(String path) {
            delegate.push(path);
            return this;
        }

        public Builder pop() {
            delegate.pop();
            return this;
        }

        public Builder pop(int count) {
            delegate.pop(count);
            return this;
        }

        public <T> ConfigValue<T> define(String path, T defaultValue) {
            return new ConfigValue<>(delegate.define(path, defaultValue));
        }

        public <T> ConfigValue<T> define(List<String> path, T defaultValue) {
            return new ConfigValue<>(delegate.define(path, defaultValue));
        }

        public BooleanValue define(String path, boolean defaultValue) {
            return new BooleanValue(delegate.define(path, defaultValue));
        }

        public <V extends Comparable<? super V>> ConfigValue<V> defineInRange(
                String path, V defaultValue, V min, V max, Class<V> clazz) {
            return new ConfigValue<>(delegate.defineInRange(path, defaultValue, min, max, clazz));
        }

        public IntValue defineInRange(String path, int defaultValue, int min, int max) {
            return new IntValue(delegate.defineInRange(path, defaultValue, min, max, Integer.class));
        }

        public ForgeConfigSpec build() {
            return new ForgeConfigSpec(delegate.build());
        }
    }

    // ---- Values ------------------------------------------------------------------------

    /**
     * Wraps a NeoForge config value.
     *
     * Forge exposes IntValue and BooleanValue as subclasses of ConfigValue, and mods call
     * {@code get()} on all three (75-79 corpus mods each), so the hierarchy has to be
     * reproduced rather than collapsed — mods hold references at the subclass type.
     */
    public static class ConfigValue<T> implements Supplier<T> {

        protected final ModConfigSpec.ConfigValue<T> delegate;

        ConfigValue(ModConfigSpec.ConfigValue<T> delegate) {
            this.delegate = delegate;
        }

        @Override
        public T get() {
            return delegate.get();
        }

        public void set(T value) {
            delegate.set(value);
        }

        public List<String> getPath() {
            return delegate.getPath();
        }

        public ModConfigSpec.ConfigValue<T> unwrap() {
            return delegate;
        }
    }

    public static class IntValue extends ConfigValue<Integer> {
        IntValue(ModConfigSpec.ConfigValue<Integer> delegate) {
            super(delegate);
        }
    }

    public static class BooleanValue extends ConfigValue<Boolean> {
        BooleanValue(ModConfigSpec.ConfigValue<Boolean> delegate) {
            super(delegate);
        }
    }
}
