package com.douai.module.config;

import org.junit.Test;

import static org.junit.Assert.*;

public final class PlaybackSpeedPresetsTest {
    @Test public void sevenChoicesAndLabelsAreCanonicalAndDetached() {
        String[] expected = {"0.75", "1", "1.25", "1.5", "2", "3", "4"};
        assertArrayEquals(expected, PlaybackSpeedPresets.values());
        assertArrayEquals(new String[]{"0.75x", "1x", "1.25x", "1.5x", "2x", "3x", "4x"},
                PlaybackSpeedPresets.labels());
        String[] copy = PlaybackSpeedPresets.values();
        copy[0] = "custom";
        assertArrayEquals(expected, PlaybackSpeedPresets.values());
        for (String value : expected) assertEquals(value, PlaybackSpeedPresets.normalize(value));
    }

    @Test public void legacyCustomValuesUseTheNearestChoiceAndLowerChoiceForTies() {
        String[][] cases = {{"3.5", "3"}, {"2.75", "3"}, {"1.75", "1.5"}, {"2.5", "2"},
                {"0.875", "0.75"}, {"0.25", "0.75"}, {"-10", "0.75"}, {"5", "4"},
                {" 2.0 ", "2"}, {"1e0", "1"}, {"3.4028235e38", "4"}, {"-3.4028235e38", "0.75"}};
        for (String[] sample : cases) {
            assertEquals(sample[0], sample[1], PlaybackSpeedPresets.normalize(sample[0]));
            assertEquals(sample[0], Float.parseFloat(sample[1]),
                    PlaybackSpeedPresets.normalize(Float.parseFloat(sample[0])), 0f);
        }
    }

    @Test public void invalidValuesAlwaysUseTheDefaultTwo() {
        for (String value : new String[]{null, "", "text", "NaN", "Infinity", "-Infinity", "1e100"}) {
            assertEquals("2", PlaybackSpeedPresets.normalize(value));
        }
        for (float value : new float[]{Float.NaN, Float.POSITIVE_INFINITY, Float.NEGATIVE_INFINITY}) {
            assertEquals(2f, PlaybackSpeedPresets.normalize(value), 0f);
        }
    }
}
