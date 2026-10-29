package com.douai.module.config;

/** The module's supported playback choices and migration of older custom values. */
public final class PlaybackSpeedPresets {
    public static final String DEFAULT = "2";
    public static final float DEFAULT_SPEED = 2f;
    private static final String[] VALUES = {"0.75", "1", "1.25", "1.5", "2", "3", "4"};
    private static final float[] SPEEDS = {0.75f, 1f, 1.25f, 1.5f, 2f, 3f, 4f};

    public static String[] values() { return VALUES.clone(); }

    public static String[] labels() {
        String[] labels = new String[VALUES.length];
        for (int index = 0; index < VALUES.length; index++) labels[index] = VALUES[index] + "x";
        return labels;
    }

    /** A tie keeps the lower preset; nonfinite values use the shared default. */
    public static float normalize(float value) {
        if (!Float.isFinite(value)) return DEFAULT_SPEED;
        if (value <= SPEEDS[0]) return SPEEDS[0];
        if (value >= SPEEDS[SPEEDS.length - 1]) return SPEEDS[SPEEDS.length - 1];
        float nearest = SPEEDS[0];
        double distance = Math.abs((double) value - nearest);
        for (int index = 1; index < SPEEDS.length; index++) {
            double candidate = Math.abs((double) value - SPEEDS[index]);
            if (candidate < distance) {
                nearest = SPEEDS[index];
                distance = candidate;
            }
        }
        return nearest;
    }

    public static String normalize(String value) {
        final float speed;
        try { speed = normalize(Float.parseFloat(value == null ? "" : value.trim())); }
        catch (NumberFormatException failure) { return DEFAULT; }
        for (int index = 0; index < SPEEDS.length; index++) {
            if (speed == SPEEDS[index]) return VALUES[index];
        }
        return DEFAULT;
    }

    private PlaybackSpeedPresets() { }
}
