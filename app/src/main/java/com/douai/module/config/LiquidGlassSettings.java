package com.douai.module.config;

/** Immutable choices for the optional host glass theme. */
public final class LiquidGlassSettings {
    public static final String STYLE = "theme_style";
    public static final String GRAVITY = "theme_gravity_enabled";
    public static final String ORIGINAL = "original";
    public static final String LIQUID_GLASS = "liquid_glass";

    public final boolean enabled;
    public final boolean gravity;

    private LiquidGlassSettings(boolean enabled, boolean gravity) {
        this.enabled = enabled;
        this.gravity = gravity;
    }

    public static LiquidGlassSettings read(LocalOptions options) {
        if (options == null) return new LiquidGlassSettings(false, true);
        return new LiquidGlassSettings(LIQUID_GLASS.equals(options.text(STYLE, ORIGINAL)),
                options.withDefault(GRAVITY, true).enabled(GRAVITY));
    }
}
